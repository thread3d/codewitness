package dev.codewitness.core.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Default sandbox: executes project code as a child process with a sanitised environment, an
 * isolated working directory, capped output and a hard timeout.
 *
 * <p>This is not a security boundary against deliberately malicious code - a process can still read
 * files the operating system permits it to read. It is documented as
 * {@link IsolationLevel#PROCESS} so that reports never overstate the guarantees. Use
 * {@link DockerSandbox} when a real boundary is required.
 */
public final class LocalProcessSandbox implements ExecutionSandbox {

    /** Environment variables that are safe to forward to a build process. */
    private static final List<String> PASSTHROUGH = List.of(
            "PATH", "JAVA_HOME", "LANG", "LC_ALL", "TZ", "TMPDIR", "HOME", "GRADLE_USER_HOME",
            "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "SDKMAN_DIR");

    private final List<Path> permittedRoots;
    private final Map<String, String> baseEnvironment;
    private final boolean allowNetwork; // informational: local processes cannot enforce this

    public LocalProcessSandbox(List<Path> permittedRoots) {
        this(permittedRoots, System.getenv(), false);
    }

    public LocalProcessSandbox(List<Path> permittedRoots, Map<String, String> hostEnvironment, boolean allowNetwork) {
        this.permittedRoots = permittedRoots.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        this.allowNetwork = allowNetwork;
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : PASSTHROUGH) {
            String value = hostEnvironment.get(key);
            if (value != null && !value.isBlank()) {
                env.put(key, value);
            }
        }
        // Never forward credentials, tokens or proxy configuration to project code by default.
        env.put("CODEWITNESS_SANDBOX", "local-process");
        env.remove("MAVEN_OPTS");
        this.baseEnvironment = Map.copyOf(env);
    }

    @Override
    public String id() {
        return "local-process";
    }

    @Override
    public IsolationLevel isolationLevel() {
        return IsolationLevel.PROCESS;
    }

    @Override
    public String description() {
        return "child process with sanitised environment (credentials and proxy variables removed), "
                + "hard timeout and capped output; network access is " + (allowNetwork ? "allowed" : "not blocked")
                + ". This is a resource-safety boundary, NOT a security boundary: a hostile build can still "
                + "read anything the current user can read. Use sandbox.mode=docker for a real boundary.";
    }

    @Override
    public ProcessRunner runner() {
        return this::execute;
    }

    @Override
    public boolean permits(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return permittedRoots.stream().anyMatch(normalized::startsWith);
    }

    /** Environment applied to every command executed by this sandbox. */
    public Map<String, String> baseEnvironment() {
        return baseEnvironment;
    }

    private ProcessResult execute(ProcessSpec spec) {
        if (!permits(spec.workingDirectory())) {
            return ProcessResult.notStarted(spec.command(), spec.workingDirectory(),
                    "working directory " + spec.workingDirectory() + " is outside the sandbox roots " + permittedRoots);
        }
        Path workingDirectory = spec.workingDirectory();
        if (!Files.isDirectory(workingDirectory)) {
            return ProcessResult.notStarted(spec.command(), workingDirectory,
                    "working directory does not exist: " + workingDirectory);
        }

        ProcessBuilder builder = new ProcessBuilder(spec.command());
        builder.directory(workingDirectory.toFile());
        Map<String, String> environment = builder.environment();
        if (spec.inheritEnvironment()) {
            // Explicit opt-in only.
        } else {
            environment.clear();
            environment.putAll(baseEnvironment);
        }
        environment.putAll(spec.environment());

        Instant start = Instant.now();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return ProcessResult.notStarted(spec.command(), workingDirectory,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        ExecutorService readers = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "codewitness-stream-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> stdout = readers.submit(() -> read(spec.maxOutputBytes(), process.getInputStream()));
            Future<String> stderr = readers.submit(() -> read(spec.maxOutputBytes(), process.getErrorStream()));

            boolean finished;
            try {
                finished = process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new ProcessResult(spec.command(), workingDirectory, true, -1, "", "",
                        Duration.between(start, Instant.now()), false, "interrupted");
            }
            if (!finished) {
                process.destroyForcibly();
                try {
                    process.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String out = get(stdout);
                String err = get(stderr);
                return new ProcessResult(spec.command(), workingDirectory, true, -1, out, err,
                        Duration.between(start, Instant.now()), true, "timeout after " + spec.timeout());
            }

            String out = get(stdout);
            String err = get(stderr);
            return new ProcessResult(spec.command(), workingDirectory, true, process.exitValue(), out, err,
                    Duration.between(start, Instant.now()), false, null);
        } finally {
            readers.shutdownNow();
        }
    }

    private static String get(Future<String> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            return "";
        }
    }

    private static String read(int maxBytes, InputStream stream) {
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        boolean truncated = false;
        byte[] buffer = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (total + read > maxBytes) {
                    int remaining = Math.max(0, maxBytes - total);
                    if (remaining > 0) {
                        byte[] chunk = new byte[remaining];
                        System.arraycopy(buffer, 0, chunk, 0, remaining);
                        chunks.add(chunk);
                        total += remaining;
                    }
                    truncated = true;
                    continue; // keep draining so the process never blocks on a full pipe
                }
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                chunks.add(chunk);
                total += read;
            }
        } catch (IOException e) {
            return "<stream read failed: " + e.getMessage() + ">".toLowerCase(Locale.ROOT);
        }
        byte[] all = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, all, offset, chunk.length);
            offset += chunk.length;
        }
        String text = new String(all, StandardCharsets.UTF_8);
        return truncated ? text + System.lineSeparator() + "[... output truncated at " + maxBytes + " bytes]" : text;
    }
}

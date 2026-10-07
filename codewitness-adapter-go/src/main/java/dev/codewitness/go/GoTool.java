package dev.codewitness.go;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.codewitness.support.Text;
import dev.codewitness.support.Platform;

/**
 * Locates the {@code go} executable used to build a project, and describes what it reported.
 *
 * <p>Go is resolved once, during inspection, and recorded in the project attributes so every phase
 * uses the same toolchain. Everything else in the adapter is a thin wrapper over a {@code go}
 * subcommand: {@code go} is the single, universal Go build tool, so unlike Java there is no
 * build-system selection to make.
 *
 * <p>Go insists on writable caches, but the sandbox deliberately drops the host environment. The
 * adapter therefore states the build cache and module cache explicitly, both inside the run
 * directory rather than in the user's home, so a verification run cannot read or write a developer's
 * global Go state. Network access is governed by the run's sandbox configuration: when
 * {@code sandbox.allow_network} is false, {@code GOPROXY} is forced to {@code off}, turning "Go
 * silently downloaded a module" into a loud, reproducible failure.
 */
public final class GoTool {

    /** Project attributes set by this class. */
    public static final String ATTR_GO = "go";
    public static final String ATTR_VERSION = "goVersion";
    public static final String ATTR_MODULE = "goModule";

    private static final Pattern VERSION = Pattern.compile("go(\\d+(?:\\.\\d+){1,2})");

    private GoTool() {
    }

    /**
     * Resolves a {@code go} executable without running anything.
     *
     * @param projectRoot project directory, used to find a project-local toolchain
     * @param hostEnvironment environment to read {@code GOROOT}, {@code GOPATH} and {@code PATH} from
     * @return an absolute path when one can be found, otherwise a bare command name the sandbox
     *     resolves through {@code PATH}
     */
    public static String resolve(Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : candidates(projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return isWindows() ? "go.exe" : "go";
    }

    private static List<Path> candidates(Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        String goroot = hostEnvironment == null ? null : hostEnvironment.get("GOROOT");
        if (goroot != null && !goroot.isBlank()) {
            candidates.add(Path.of(goroot, "bin", executableName()));
        }
        // The conventional install locations, checked before PATH so a project pinned to one of them
        // is verified with the toolchain the developer actually uses.
        candidates.add(Path.of("/usr/local/go/bin", executableName()));
        String home = hostEnvironment == null ? null : hostEnvironment.get("HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, "go", "bin", executableName()));
            candidates.add(Path.of(home, "sdk", executableName()));
        }
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (!element.isBlank()) {
                    candidates.add(Path.of(element, executableName()));
                }
            }
        }
        return candidates;
    }

    private static String executableName() {
        return isWindows() ? "go.exe" : "go";
    }

    /**
     * Asks Go for its version.
     *
     * @param context run context, providing the sandboxed runner
     * @param goCommand go command or absolute path
     * @return what Go reported; a missing toolchain is a reported fact, not a failure
     */
    public static Probe probe(VerificationContext context, String goCommand) {
        ProcessResult version = context.runner().run(ProcessSpec.builder(
                        List.of(goCommand, "version"), context.projectRoot())
                .environment(environment(context))
                .timeout(Duration.ofSeconds(60))
                .build());
        if (!version.started() || !version.success()) {
            return new Probe(null, version.describeOutcome());
        }
        Matcher matcher = VERSION.matcher(version.combinedOutput());
        return new Probe(matcher.find() ? matcher.group(1) : firstLine(version.combinedOutput()), null);
    }

    /**
     * Builds the environment for a Go command.
     *
     * <p>The sandbox drops the host environment, so everything Go needs is stated explicitly. The
     * caches live under {@code cacheRoot} (the run's artefact directory) instead of the user's home.
     *
     * @param allowNetwork whether the run's sandbox configuration permits network access
     * @param cacheRoot    writable directory for the build and module caches
     * @return environment additions for a {@link ProcessSpec}
     */
    public static Map<String, String> environment(boolean allowNetwork, Path cacheRoot) {
        Map<String, String> environment = new LinkedHashMap<>();
        Path base = cacheRoot == null ? Path.of(".") : cacheRoot;
        environment.put("GOCACHE", base.resolve("go-build").toString());
        environment.put("GOPATH", base.resolve("go-path").toString());
        // Never let Go fetch a different toolchain in the middle of a verification run: a network
        // fetch is not reproducible and is exactly what the sandbox is meant to prevent.
        environment.put("GOTOOLCHAIN", "local");
        environment.put("GOFLAGS", "-mod=mod");
        environment.put("GO111MODULE", "on");
        // A verification run does not need cgo, and disabling it removes any dependency on a host C
        // compiler being present and trusted.
        environment.put("CGO_ENABLED", "0");
        if (!allowNetwork) {
            environment.put("GOPROXY", "off");
            environment.put("GOSUMDB", "off");
        }
        return environment;
    }

    /** @return the environment a Go command should run with for this context. */
    public static Map<String, String> environment(VerificationContext context) {
        return environment(context.config().sandbox().allowNetwork(), context.artifacts().artifactsDirectory());
    }

    /** @return the go command recorded during inspection, or a PATH-resolved default. */
    public static String go(ProjectInfo project, VerificationContext context) {
        String resolved = project.attributes().get(ATTR_GO);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return resolve(context.projectRoot(), System.getenv());
    }

    /** @return {@code true} when the inspected project has a module manifest and can be built. */
    public static boolean hasManifest(VerificationContext context) {
        return Files.isRegularFile(context.projectRoot().resolve("go.mod"));
    }

    /** @return the last {@code count} lines of a captured output, for reports and diagnostics. */
    public static String lastLines(String text, int count) {
        return Text.lastLines(text, count);
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.strip().split("\\R")[0].strip();
    }

    static boolean isWindows() {
        return Platform.isWindows();
    }

    /**
     * What Go reported about itself.
     *
     * @param version       the toolchain version, for example {@code 1.27.1}, or {@code null}
     * @param failureReason why the probe failed, or {@code null}
     */
    public record Probe(String version, String failureReason) {

        public boolean available() {
            return failureReason == null;
        }
    }
}

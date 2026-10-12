package dev.codewitness.pascal;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;
import dev.codewitness.support.Text;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and describes the Free Pascal compiler.
 *
 * <p>Resolution never executes anything: it looks for the executable the way a developer's shell would,
 * preferring a toolchain vendored in the project, then an explicit override, then the conventional
 * install locations and {@code PATH}. Only {@link #probe} runs the compiler, and a missing compiler is a
 * reported fact rather than a failure - the adapter has no evidence about code it could not build, and
 * says so.
 *
 * <p>A Free Pascal installation that was unpacked somewhere other than the prefix it was configured for
 * needs its {@code fpc.cfg} pointed at explicitly, so the resolver records the configuration directory
 * beside the executable and every child process receives it as {@code PPC_CONFIG_PATH}. A normal system
 * install finds its own configuration and needs nothing.
 */
public final class PascalTool {

    /** Project attributes written by this class. */
    public static final String ATTR_FPC = "fpc";
    public static final String ATTR_VERSION = "fpcVersion";
    public static final String ATTR_CONFIG_PATH = "fpcConfigPath";
    public static final String ATTR_PROBE_FAILURE = "pascalProbeFailure";

    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    private PascalTool() {
    }

    /** @return the {@code fpc} executable to invoke, preferring the one resolved during inspection. */
    public static String fpc(ProjectInfo project, VerificationContext context) {
        String recorded = project.attributes().get(ATTR_FPC);
        if (recorded != null && !recorded.isBlank()) {
            return recorded;
        }
        return resolve("fpc", context.projectRoot(), System.getenv());
    }

    /**
     * Resolves the compiler without running it.
     *
     * @param name            the executable's base name, normally {@code fpc}
     * @param projectRoot     project directory, used to find a project-local toolchain
     * @param hostEnvironment environment to read the override variable and {@code PATH} from
     * @return an absolute path when one can be found, otherwise the bare command name, which the sandbox
     *     resolves through {@code PATH}
     */
    public static String resolve(String name, Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : candidates(name, projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return isWindows() ? name + ".exe" : name;
    }

    private static List<Path> candidates(String name, Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        String executable = isWindows() ? name + ".exe" : name;
        if (projectRoot != null) {
            candidates.add(projectRoot.resolve("tools").resolve(executable));
            candidates.add(projectRoot.resolve("tools").resolve("bin").resolve(executable));
            candidates.add(projectRoot.resolve(".tools").resolve("bin").resolve(executable));
            // A Free Pascal distribution unpacked under the project keeps its compiler in bin/ too.
            candidates.add(projectRoot.resolve(".tools").resolve("fpc").resolve("bin").resolve(executable));
        }
        String configured = hostEnvironment == null ? null : hostEnvironment.get("FPC");
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        for (String directory : List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin")) {
            candidates.add(Path.of(directory, executable));
        }
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (!element.isBlank()) {
                    candidates.add(Path.of(element, executable));
                }
            }
        }
        return candidates;
    }

    /**
     * @return the directory holding the compiler's {@code fpc.cfg}, when the executable sits in a
     *     distribution tree that keeps one, or {@code null} for a system install that finds its own
     */
    public static Path configDirectory(String fpcCommand) {
        if (fpcCommand == null || fpcCommand.isBlank()) {
            return null;
        }
        Path executable = Path.of(fpcCommand);
        Path bin = executable.isAbsolute() ? executable.getParent() : null;
        if (bin == null) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(bin.getParent() == null ? null : bin.getParent().resolve("etc"));
        candidates.add(bin.getParent() == null ? null : bin.getParent().resolve("lib").resolve("fpc").resolve("etc"));
        candidates.add(bin);
        for (Path directory : candidates) {
            if (directory != null && Files.isRegularFile(directory.resolve("fpc.cfg"))) {
                return directory.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * Asks the compiler for its version.
     *
     * @param context  run context, providing the sandboxed runner
     * @param fpcCommand the resolved {@code fpc} command
     * @return what the compiler reported; a missing compiler is a reported fact, not a failure
     */
    public static Probe probe(VerificationContext context, String fpcCommand) {
        ProcessResult version = context.runner().run(ProcessSpec.builder(
                        List.of(fpcCommand, "-iV"), context.projectRoot())
                .timeout(java.time.Duration.ofSeconds(60))
                .build());
        if (!version.started() || !version.success()) {
            return new Probe(null, version.describeOutcome());
        }
        Matcher matcher = VERSION.matcher(version.combinedOutput());
        return new Probe(matcher.find() ? matcher.group(1) : firstLine(version.combinedOutput()), null);
    }

    /**
     * @return the environment a Pascal command should run with: the compiler's configuration directory
     *     when the distribution keeps one, and nothing else
     */
    public static Map<String, String> environment(VerificationContext context) {
        Map<String, String> environment = new LinkedHashMap<>();
        String recorded = context.projectInfo().attributes().get(ATTR_CONFIG_PATH);
        if (recorded != null && !recorded.isBlank()) {
            environment.put("PPC_CONFIG_PATH", recorded);
        }
        return environment;
    }

    /** @return {@code true} when a compiler was found and reported a version during inspection. */
    public static boolean isAvailable(VerificationContext context) {
        String recorded = context.projectInfo().attributes().get(ATTR_FPC);
        return recorded != null && !recorded.isBlank();
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
     * What the compiler reported about itself.
     *
     * @param version       the version string, for example {@code 3.2.2}, or {@code null}
     * @param failureReason why the probe failed, or {@code null}
     */
    public record Probe(String version, String failureReason) {

        public boolean available() {
            return failureReason == null;
        }
    }
}

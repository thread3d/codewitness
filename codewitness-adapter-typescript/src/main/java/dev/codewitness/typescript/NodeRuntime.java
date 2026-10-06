package dev.codewitness.typescript;

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

/**
 * Locates the Node executable and the TypeScript compiler used to verify a project, and describes what
 * the runtime can do.
 *
 * <p>Node is resolved once, during inspection, and recorded in the project attributes so every phase
 * uses the same one. Node 22.6 and later can execute TypeScript directly by stripping the type
 * annotations, which is what lets this adapter run tests and generated tests with no build step and no
 * third-party package; the optional TypeScript compiler is only used for type checking when a project
 * already has one installed.
 *
 * <p>Nothing is downloaded. A project that has no {@code node_modules} is still built (syntax), tested
 * and mutated from the toolchain the environment already has. Network access is governed by the run's
 * sandbox configuration: when {@code sandbox.allow_network} is false the package manager is told to
 * stay offline, so a command that would reach the registry fails loudly instead of silently doing so.
 */
public final class NodeRuntime {

    /** Project attributes set by this class. */
    public static final String ATTR_NODE = "node";
    public static final String ATTR_VERSION = "nodeVersion";
    public static final String ATTR_TSC = "tsc";
    public static final String ATTR_TSC_SOURCE = "tscSource";
    public static final String ATTR_TYPE_SCRIPT_VERSION = "typescriptVersion";

    /** Node versions before this cannot execute TypeScript sources at all. */
    public static final String MINIMUM_TYPE_STRIPPING_VERSION = "22.6.0";

    private NodeRuntime() {
    }

    /**
     * Resolves a Node executable without running anything.
     *
     * @param projectRoot project directory, used to find a project-local runtime such as a Volta shim
     * @param hostEnvironment environment to read {@code NODE_HOME} and {@code PATH} from
     * @return an absolute path when one can be found, otherwise a bare command name the sandbox
     *     resolves through {@code PATH}
     */
    public static String resolve(Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : nodeCandidates(projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return isWindows() ? "node.exe" : "node";
    }

    private static List<Path> nodeCandidates(Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        if (projectRoot != null) {
            candidates.add(projectRoot.resolve("node_modules/.bin").resolve(executableName()));
        }
        String nodeHome = hostEnvironment == null ? null : hostEnvironment.get("NODE_HOME");
        if (nodeHome != null && !nodeHome.isBlank()) {
            candidates.add(Path.of(nodeHome, "bin", executableName()));
            candidates.add(Path.of(nodeHome, executableName()));
        }
        addPathCandidates(candidates, hostEnvironment, executableName());
        return candidates;
    }

    /**
     * Resolves a TypeScript compiler without installing anything.
     *
     * @return an absolute path to a project-local or PATH {@code tsc}, or {@code null} when TypeScript
     *     is not installed; the caller reports the absence rather than fetching it
     */
    public static String resolveTsc(Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : tscCandidates(projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private static List<Path> tscCandidates(Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        if (projectRoot != null) {
            // The compiler a project pins in its own node_modules is always preferred over a global one.
            candidates.add(projectRoot.resolve("node_modules/.bin").resolve(tscExecutableName()));
        }
        addPathCandidates(candidates, hostEnvironment, tscExecutableName());
        return candidates;
    }

    private static void addPathCandidates(
            List<Path> candidates, Map<String, String> hostEnvironment, String executable) {
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path == null) {
            return;
        }
        for (String element : path.split(File.pathSeparator)) {
            if (!element.isBlank()) {
                candidates.add(Path.of(element, executable));
            }
        }
    }

    private static String executableName() {
        return isWindows() ? "node.exe" : "node";
    }

    private static String tscExecutableName() {
        return isWindows() ? "tsc.cmd" : "tsc";
    }

    /**
     * Asks Node for its version and decides what the runtime can do.
     *
     * @param context run context, providing the sandboxed runner
     * @param node    node command or absolute path
     * @return what Node reported; a missing runtime is a reported fact, never an exception
     */
    public static Probe probe(VerificationContext context, String node) {
        Map<String, String> environment = environment(context.config().sandbox().allowNetwork());
        ProcessResult version = context.runner().run(ProcessSpec.builder(
                        List.of(node, "--version"), context.projectRoot())
                .environment(environment)
                .timeout(Duration.ofSeconds(60))
                .maxOutputBytes(64_000)
                .build());
        if (!version.started() || !version.success()) {
            return new Probe(null, false, version.describeOutcome());
        }
        String reported = firstLine(version.combinedOutput());
        return new Probe(reported, supportsTypeStripping(reported), null);
    }

    /**
     * @param version the string printed by {@code node --version}, or {@code null}
     * @return {@code true} when the runtime is new enough to execute TypeScript by stripping types
     */
    public static boolean supportsTypeStripping(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        int[] parsed = parseVersion(version);
        int[] minimum = parseVersion(MINIMUM_TYPE_STRIPPING_VERSION);
        for (int i = 0; i < 3; i++) {
            if (parsed[i] != minimum[i]) {
                return parsed[i] > minimum[i];
            }
        }
        return true;
    }

    private static int[] parseVersion(String version) {
        String[] parts = version.replaceFirst("^[^0-9]*", "").split("[.\\-+]");
        int[] numbers = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException _) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    /**
     * Builds the environment for a Node command.
     *
     * <p>The sandbox drops the host environment, so everything Node needs is stated explicitly. Colour
     * is disabled so captured output stays readable and parseable, and a run that is not allowed
     * network access tells the package manager to stay offline.
     *
     * @param allowNetwork whether the run's sandbox configuration permits network access
     * @return environment additions for a {@link ProcessSpec}
     */
    public static Map<String, String> environment(boolean allowNetwork) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("NO_COLOR", "1");
        environment.put("FORCE_COLOR", "0");
        environment.put("TZ", "UTC");
        if (!allowNetwork) {
            environment.put("npm_config_offline", "true");
            environment.put("YARN_ENABLE_NETWORK", "false");
            environment.put("PNPM_OFFLINE", "true");
        }
        return environment;
    }

    /** @return the Node command recorded during inspection, or a PATH-resolved default. */
    public static String node(ProjectInfo project, VerificationContext context) {
        String resolved = project.attributes().get(ATTR_NODE);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return resolve(context.projectRoot(), System.getenv());
    }

    /** @return the environment a Node command should run with. */
    public static Map<String, String> environment(VerificationContext context) {
        return environment(context.config().sandbox().allowNetwork());
    }

    /** @return the last {@code count} lines of a captured output, for reports and diagnostics. */
    public static String lastLines(String text, int count) {
        if (text == null || text.isBlank()) {
            return "(no output)";
        }
        String[] lines = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
            sb.append(lines[i]).append(" | ");
        }
        return sb.toString();
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.strip().split("\\R")[0].strip();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * What Node reported about itself.
     *
     * @param version             the string printed by {@code node --version}, or {@code null}
     * @param typeStripping       whether the runtime can execute TypeScript by stripping types
     * @param failureReason       why the probe failed, or {@code null}
     */
    public record Probe(String version, boolean typeStripping, String failureReason) {

        public boolean available() {
            return failureReason == null;
        }
    }
}

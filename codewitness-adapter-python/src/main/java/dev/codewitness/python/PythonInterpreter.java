package dev.codewitness.python;

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
import java.util.Map;

/**
 * Locates the Python interpreter used to run a project, and describes what that interpreter can do.
 *
 * <p>The interpreter is resolved once, during inspection, and recorded in the project attributes so
 * that every phase uses the same one. Resolution prefers a project virtual environment over the
 * ambient interpreter, because that is where a project's dependencies actually live; when there is
 * no virtual environment the ambient {@code python3}/{@code python} is used.
 *
 * <p>Every probe is a plain process run through the sandbox and every probe result is recorded as a
 * fact. Nothing here guesses that a tool is available: an unavailable tool is discovered by asking
 * the interpreter and is reported as unavailable.
 */
public final class PythonInterpreter {

    /** Project attributes set by this class. */
    public static final String ATTR_INTERPRETER = "interpreter";
    public static final String ATTR_VERSION = "interpreterVersion";
    public static final String ATTR_HAS_PYTEST = "hasPytest";
    public static final String ATTR_HAS_COVERAGE = "hasCoverage";
    public static final String ATTR_HAS_MUTMUT = "hasMutmut";

    private PythonInterpreter() {
    }

    /**
     * Resolves an interpreter without running anything.
     *
     * @param projectRoot project directory, used to find a project virtual environment
     * @param hostEnvironment environment to read {@code VIRTUAL_ENV} and {@code PATH} from
     * @return an absolute interpreter path when one can be found, otherwise a bare command name that
     *     the sandbox will resolve through {@code PATH}
     */
    public static String resolve(Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : candidates(projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return isWindows() ? "python" : "python3";
    }

    private static List<Path> candidates(Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        String virtualEnv = hostEnvironment == null ? null : hostEnvironment.get("VIRTUAL_ENV");
        if (virtualEnv != null && !virtualEnv.isBlank()) {
            candidates.add(Path.of(virtualEnv, "bin", "python"));
            candidates.add(Path.of(virtualEnv, "Scripts", "python.exe"));
        }
        if (projectRoot != null) {
            for (String directory : List.of(".venv", "venv", "env", ".env")) {
                candidates.add(projectRoot.resolve(directory).resolve("bin").resolve("python"));
                candidates.add(projectRoot.resolve(directory).resolve("Scripts").resolve("python.exe"));
            }
        }
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (element.isBlank()) {
                    continue;
                }
                candidates.add(Path.of(element, "python3"));
                candidates.add(Path.of(element, "python"));
                candidates.add(Path.of(element, "python3.exe"));
                candidates.add(Path.of(element, "python.exe"));
            }
        }
        return candidates;
    }

    /**
     * Builds the environment for a Python command.
     *
     * <p>{@code PYTHONPATH} is assembled from the project's own source roots so that a project which
     * is not installed can still be imported by its tests. The sandbox drops the host environment,
     * so this is the only way the project's modules become importable, and it is derived from
     * detected layout rather than inherited from whatever happened to be set.
     *
     * @param info        project information, already rebased into the workspace
     * @param projectRoot root the command runs in
     * @param extraEntries additional directories to put on {@code PYTHONPATH} (for example generated tests)
     * @return environment additions for a {@link ProcessSpec}
     */
    public static Map<String, String> environment(ProjectInfo info, Path projectRoot, List<Path> extraEntries) {
        List<Path> entries = new ArrayList<>();
        entries.addAll(info.sourceDirs());
        entries.addAll(info.testSourceDirs());
        if (extraEntries != null) {
            entries.addAll(extraEntries);
        }
        // A flat layout uses the project root as its source root, which is already in the list; adding
        // it again is harmless and helps projects whose detector returned no source directory.
        entries.add(projectRoot);

        Map<String, String> environment = new LinkedHashMap<>();
        String pythonPath = joinPaths(entries);
        if (!pythonPath.isBlank()) {
            environment.put("PYTHONPATH", pythonPath);
        }
        // Byte-code caches and buffered output both make a sandboxed run harder to reason about: the
        // first litters the workspace, the second can lose the tail of a crashed interpreter.
        environment.put("PYTHONDONTWRITEBYTECODE", "1");
        environment.put("PYTHONUNBUFFERED", "1");
        environment.put("PYTHONIOENCODING", "utf-8");
        return environment;
    }

    private static String joinPaths(List<Path> entries) {
        List<String> seen = new ArrayList<>();
        for (Path entry : entries) {
            if (entry == null) {
                continue;
            }
            String value = entry.toAbsolutePath().normalize().toString();
            if (!seen.contains(value)) {
                seen.add(value);
            }
        }
        return String.join(File.pathSeparator, seen);
    }

    /**
     * Asks the interpreter for its version and which optional tools it can import.
     *
     * @param context     run context, providing the sandboxed runner
     * @param interpreter interpreter command or absolute path
     * @return what the interpreter reported; every optional tool defaults to unavailable
     */
    public static Probe probe(VerificationContext context, String interpreter) {
        String script = """
                import platform, sys
                try:
                    import importlib.util as _u
                    _pytest = _u.find_spec("pytest") is not None
                    _coverage = _u.find_spec("coverage") is not None
                    _mutmut = _u.find_spec("mutmut") is not None
                except Exception:
                    _pytest = _coverage = _mutmut = False
                print("version=" + platform.python_version())
                print("executable=" + sys.executable)
                print("pytest=" + str(_pytest))
                print("coverage=" + str(_coverage))
                print("mutmut=" + str(_mutmut))
                """;
        ProcessResult result = context.runner().run(ProcessSpec.builder(
                        List.of(interpreter, "-c", script), context.projectRoot())
                .timeout(Duration.ofSeconds(60))
                .build());
        if (!result.started() || !result.success()) {
            return new Probe(null, null, false, false, false, result.describeOutcome());
        }
        Map<String, String> values = parseKeyValues(result.stdout());
        return new Probe(
                values.get("version"),
                values.get("executable"),
                Boolean.parseBoolean(values.getOrDefault("pytest", "false")),
                Boolean.parseBoolean(values.getOrDefault("coverage", "false")),
                Boolean.parseBoolean(values.getOrDefault("mutmut", "false")),
                null);
    }

    private static Map<String, String> parseKeyValues(String output) {
        Map<String, String> values = new LinkedHashMap<>();
        if (output == null) {
            return values;
        }
        for (String line : output.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return values;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * What an interpreter reported about itself.
     *
     * @param version      Python version such as {@code 3.11.5}, or {@code null} when unknown
     * @param executable   the interpreter's own path, or {@code null}
     * @param pytest       whether pytest is importable
     * @param coverage     whether coverage.py is importable
     * @param mutmut       whether mutmut is importable
     * @param failureReason why the probe failed, or {@code null}
     */
    public record Probe(
            String version, String executable, boolean pytest, boolean coverage, boolean mutmut, String failureReason) {

        public boolean available() {
            return failureReason == null;
        }
    }
}

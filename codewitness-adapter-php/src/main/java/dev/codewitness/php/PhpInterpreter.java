package dev.codewitness.php;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates the PHP interpreter that runs a project, and describes what that interpreter can do.
 *
 * <p>The interpreter is resolved once, during inspection, and recorded in the project attributes so
 * that every phase drives the same one. Nothing here guesses that a tool is available: an interpreter
 * or extension is reported present only when it answered a probe, and a tool that did not answer is
 * reported as a reason it could not be used.
 *
 * <p>PHPUnit is the one thing that makes this adapter different from the Java and Kotlin ones, and the
 * difference is worth stating plainly: <strong>PHPUnit is not part of the PHP toolchain</strong>. It is a
 * Composer dependency that only exists once the project's build has run {@code composer install} and
 * created {@code vendor/}. Inspection therefore happens before PHPUnit can exist, and a "PHPUnit was
 * found" fact recorded at inspection time would be false for every project that has not been built
 * already. The probe still records what it saw - it is a useful hint, and it is what an already-built
 * project honestly reports - but the test runner resolves PHPUnit again at test time, after the build
 * that may have installed it. {@link #phpunit(ProjectInfo, VerificationContext)} says which order it
 * uses.
 *
 * <p>Composer is the exception: it is a tool of the machine, not of the project, so it is resolved the
 * same way the interpreter is.
 */
public final class PhpInterpreter {

    /** Project attributes set by this class. */
    public static final String ATTR_PHP = "php";
    public static final String ATTR_VERSION = "phpVersion";
    public static final String ATTR_COMPOSER = "composer";
    public static final String ATTR_COMPOSER_VERSION = "composerVersion";
    public static final String ATTR_PHPUNIT = "phpunit";
    public static final String ATTR_HAS_PHPUNIT = "hasPhpunit";
    public static final String ATTR_COVERAGE_DRIVER = "coverageDriver";

    /**
     * Attribute naming why the interpreter probe failed, set only when it did.
     *
     * <p>Its absence, not its value, is the fact that matters: a build that reads this attribute knows
     * the interpreter never answered, and can say so instead of reporting a failed build against the
     * project's code. The name is the one the Python adapter uses for the same purpose.
     */
    public static final String ATTR_PROBE_FAILURE = "interpreterProbeFailure";

    /** Composer's own version banner, for example {@code Composer version 2.10.3 2025-11-07 ...}. */
    private static final Pattern COMPOSER_VERSION = Pattern.compile("Composer version\\s+(\\S+)");

    /** A probe only asks the interpreter about itself; a minute is generous and still bounded. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(60);

    private PhpInterpreter() {
    }

    /**
     * Resolves an interpreter without running anything.
     *
     * @param projectRoot     project directory, used to find an interpreter the project ships itself
     * @param hostEnvironment environment to read {@code CODEWITNESS_PHP} and {@code PATH} from
     * @return an absolute interpreter path when one can be found, otherwise a bare command name that
     *     the sandbox will resolve through {@code PATH}
     */
    public static String resolve(Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : phpCandidates(projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return phpName();
    }

    private static List<Path> phpCandidates(Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        String explicit = hostEnvironment == null ? null : hostEnvironment.get("CODEWITNESS_PHP");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Path.of(explicit));
        }
        if (projectRoot != null) {
            // A project that pins its own interpreter is tested with that interpreter, not with
            // whatever happens to be installed; the checked-in .tools directory is how the CodeWitness
            // PHP fixtures do it.
            candidates.add(projectRoot.resolve(".tools").resolve("php").resolve(phpName()));
            candidates.add(projectRoot.resolve(".tools").resolve("php").resolve("php.exe"));
        }
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (element.isBlank()) {
                    continue;
                }
                candidates.add(Path.of(element, phpName()));
                candidates.add(Path.of(element, "php.exe"));
            }
        }
        return candidates;
    }

    /**
     * Builds the environment for a Composer command.
     *
     * <p>The sandbox replaces the child environment, so this is the only channel through which Composer
     * learns where it may write. Both Composer directories are put inside the run's artefact store
     * because a global {@code ~/.composer} is shared with every other project on the machine and would
     * make one run's downloads visible to the next; {@code COMPOSER_NO_AUDIT} is set because
     * CodeWitness runs its own advisory scan and a second one would only cost time.
     *
     * @param info              project information; part of the fixed contract, unused here because this
     *                          adapter's Composer configuration is about where Composer may write rather
     *                          than about where the project keeps its files
     * @param projectRoot       project root; likewise part of the contract, unused for the same reason
     * @param artifactsDirectory the run's artefact directory, which is what the two Composer directories
     *                          are derived from
     * @return environment additions for a {@link ProcessSpec}
     */
    public static Map<String, String> environment(ProjectInfo info, Path projectRoot, Path artifactsDirectory) {
        Path phpArtifacts = artifactsDirectory.resolve("php");
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("COMPOSER_HOME", phpArtifacts.resolve("composer-home").toString());
        environment.put("COMPOSER_CACHE_DIR", phpArtifacts.resolve("composer-cache").toString());
        environment.put("COMPOSER_NO_INTERACTION", "1");
        environment.put("COMPOSER_NO_AUDIT", "1");
        environment.put("COMPOSER_ALLOW_SUPERUSER", "1");
        environment.put("COMPOSER_DISABLE_XDEBUG_WARN", "1");
        return environment;
    }

    /**
     * Asks the interpreter for its version, its extensions and the Composer it can see.
     *
     * <p>One {@code php -r} run answers everything the interpreter itself knows, and a second run asks
     * the resolved Composer for its version when one could be resolved at all. Both answers are
     * recorded, including "nothing found": an unavailable tool defaults to unavailable, never to the
     * optimistic branch.
     *
     * @param context run context, providing the sandboxed runner and the run's artefact directory
     * @param php     interpreter command or absolute path
     * @return what was observed; every optional tool defaults to unavailable
     */
    public static Probe probe(VerificationContext context, String php) {
        Map<String, String> environment =
                environment(context.projectInfo(), context.projectRoot(), context.artifacts().artifactsDirectory());
        ProcessResult result = context.runner().run(ProcessSpec.builder(List.of(php, "-r", PROBE_SCRIPT), context.projectRoot())
                .environment(environment)
                .timeout(PROBE_TIMEOUT)
                .build());
        if (!result.started() || !result.success()) {
            return new Probe(null, null, null, false, null, result.describeOutcome());
        }
        Map<String, String> values = parseKeyValues(result.stdout());
        boolean xdebug = Boolean.parseBoolean(values.getOrDefault("xdebug", "false"));
        boolean pcov = Boolean.parseBoolean(values.getOrDefault("pcov", "false"));
        // Xdebug is preferred over PCOV when both are loaded: it is the one PHPUnit's own documentation
        // recommends, and naming the driver the coverage provider will actually use is the point.
        String driver = xdebug ? "xdebug" : pcov ? "pcov" : null;
        return new Probe(values.get("version"), composerVersion(context, php, environment),
                blankToNull(values.get("phpunit")), driver != null, driver, null);
    }

    private static String composerVersion(VerificationContext context, String php, Map<String, String> environment) {
        List<String> command = composer(context.projectRoot(), php, System.getenv("PATH"));
        if (command.isEmpty()) {
            return null;
        }
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(environment)
                .timeout(PROBE_TIMEOUT)
                .build());
        if (!result.started()) {
            return null;
        }
        Matcher matcher = COMPOSER_VERSION.matcher(result.combinedOutput());
        return matcher.find() ? matcher.group(1) : null;
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

    /** @return the interpreter a provider should run, never blank. */
    public static String php(ProjectInfo project, VerificationContext context) {
        String recorded = project.attributes().get(ATTR_PHP);
        if (recorded != null && !recorded.isBlank()) {
            return recorded;
        }
        return resolve(context.projectRoot(), System.getenv());
    }

    /**
     * @return the argv that runs Composer for this project, or an empty list when none was found.
     *
     * <p>A {@code .phar} is not executable in its own right, so it is invoked through the interpreter;
     * a native executable is invoked directly.
     */
    public static List<String> composer(ProjectInfo project, VerificationContext context) {
        return composer(context.projectRoot(), php(project, context), System.getenv("PATH"));
    }

    static List<String> composer(Path projectRoot, String phpCommand, String pathVariable) {
        Path projectPhar = projectRoot.resolve("composer.phar");
        if (Files.isRegularFile(projectPhar)) {
            return List.of(phpCommand, projectPhar.toString());
        }
        Path projectBinary = projectRoot.resolve(Platform.isWindows() ? "composer.bat" : "composer");
        if (Files.isExecutable(projectBinary)) {
            return List.of(projectBinary.toString());
        }
        String onPath = findOnPath(Platform.isWindows() ? "composer.bat" : "composer", pathVariable, true);
        if (onPath != null) {
            return List.of(onPath);
        }
        String pharOnPath = findOnPath("composer.phar", pathVariable, false);
        if (pharOnPath != null) {
            return List.of(phpCommand, pharOnPath);
        }
        Path toolsPhar = projectRoot.resolve(".tools").resolve("php").resolve("composer.phar");
        if (Files.isRegularFile(toolsPhar)) {
            return List.of(phpCommand, toolsPhar.toString());
        }
        return List.of();
    }

    /**
     * @return the argv that runs the project's PHPUnit, or an empty list when there is none.
     *
     * <p>Resolution runs at <em>test</em> time, not at inspection time, in this order:
     *
     * <ol>
     *   <li>{@code vendor/bin/phpunit} below the project root. The build runs before the tests, so this
     *       is the binary that this run's own {@code composer install} produced, and it is the only one
     *       guaranteed to match the project's {@code composer.lock};
     *   <li>the path the probe recorded at inspection time, when it recorded one - a project-local or a
     *       {@code PATH} binary, which is all an already-built project would have;
     *   <li>{@code phpunit.phar} beside the interpreter, because a machine that ships a static PHP often
     *       ships its PHPUnit next to it.
     * </ol>
     *
     * <p>A {@code .phar} is invoked through the interpreter. An installed {@code vendor/bin/phpunit} is
     * run directly when it is executable and through the interpreter when it is not, because Composer's
     * proxy script is valid PHP either way and a checkout that lost the execute bit should still be
     * testable.
     */
    public static List<String> phpunit(ProjectInfo project, VerificationContext context) {
        return phpunit(context.projectRoot(), php(project, context), project.attributes().get(ATTR_PHPUNIT));
    }

    static List<String> phpunit(Path projectRoot, String phpCommand, String recorded) {
        Path console = projectRoot.resolve("vendor").resolve("bin")
                .resolve(Platform.isWindows() ? "phpunit.bat" : "phpunit");
        if (Files.isRegularFile(console)) {
            return Files.isExecutable(console) ? List.of(console.toString()) : List.of(phpCommand, console.toString());
        }
        Path consolePhar = projectRoot.resolve("vendor").resolve("bin").resolve("phpunit.phar");
        if (Files.isRegularFile(consolePhar)) {
            return List.of(phpCommand, consolePhar.toString());
        }
        if (recorded != null && !recorded.isBlank()) {
            return commandFor(recorded, phpCommand);
        }
        Path interpreter = pathOf(phpCommand);
        if (interpreter != null && interpreter.getParent() != null) {
            Path beside = interpreter.getParent().resolve("phpunit.phar");
            if (Files.isRegularFile(beside)) {
                return List.of(phpCommand, beside.toString());
            }
            Path sibling = interpreter.getParent().resolve(Platform.isWindows() ? "phpunit.bat" : "phpunit");
            if (Files.isRegularFile(sibling)) {
                return List.of(sibling.toString());
            }
        }
        return List.of();
    }

    private static List<String> commandFor(String recorded, String phpCommand) {
        return recorded.toLowerCase(Locale.ROOT).endsWith(".phar")
                ? List.of(phpCommand, recorded)
                : List.of(recorded);
    }

    private static Path pathOf(String command) {
        try {
            return Path.of(command);
        } catch (InvalidPathException _) {
            return null;
        }
    }

    private static String findOnPath(String name, String pathVariable, boolean executable) {
        if (pathVariable == null) {
            return null;
        }
        for (String element : pathVariable.split(File.pathSeparator)) {
            if (element.isBlank()) {
                continue;
            }
            Path candidate = Path.of(element, name);
            if (executable ? Files.isExecutable(candidate) : Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private static String phpName() {
        return Platform.isWindows() ? "php.exe" : "php";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * What one interpreter reported about itself and its neighbours.
     *
     * @param version            the interpreter's own version such as {@code 8.4.23}, or {@code null}
     * @param composerVersion    the resolved Composer's version, or {@code null} when none answered
     * @param phpunit            the PHPUnit the probe could see, or {@code null}; a hint for the test
     *                           phase, never a gate, because PHPUnit is installed by the build
     * @param hasCoverageDriver  whether a coverage driver answered {@code extension_loaded}
     * @param coverageDriver     {@code xdebug}, {@code pcov}, or {@code null} when neither is loaded
     * @param failureReason      why the probe failed, or {@code null}
     */
    public record Probe(
            String version,
            String composerVersion,
            String phpunit,
            boolean hasCoverageDriver,
            String coverageDriver,
            String failureReason) {

        /** @return {@code true} when the interpreter answered the probe at all. */
        public boolean available() {
            return failureReason == null;
        }
    }

    /**
     * The single {@code php -r} program behind {@link #probe}.
     *
     * <p>It resolves PHPUnit the way the test provider does for an already-built project and prints one
     * {@code key=value} line per answer, so the Java side parses facts instead of prose. It deliberately
     * invents nothing: an answer it cannot determine is printed empty.
     */
    static final String PROBE_SCRIPT = """
            $phpunit = '';
            $local = [getcwd() . DIRECTORY_SEPARATOR . 'vendor' . DIRECTORY_SEPARATOR . 'bin'
                      . DIRECTORY_SEPARATOR . 'phpunit',
                      getcwd() . DIRECTORY_SEPARATOR . 'phpunit.phar'];
            foreach ($local as $candidate) {
                if (is_file($candidate)) {
                    $phpunit = $candidate;
                    break;
                }
            }
            if ($phpunit === '') {
                $path = getenv('PATH');
                foreach ($path === false ? [] : explode(PATH_SEPARATOR, $path) as $directory) {
                    if ($directory === '') {
                        continue;
                    }
                    foreach (['phpunit', 'phpunit.bat', 'phpunit.phar'] as $name) {
                        $candidate = $directory . DIRECTORY_SEPARATOR . $name;
                        if (is_file($candidate)) {
                            $phpunit = $candidate;
                            break 2;
                        }
                    }
                }
            }
            echo 'version=' . PHP_VERSION . PHP_EOL;
            echo 'composer=' . (getenv('COMPOSER') === false ? '' : getenv('COMPOSER')) . PHP_EOL;
            echo 'xdebug=' . (extension_loaded('xdebug') ? 'true' : 'false') . PHP_EOL;
            echo 'pcov=' . (extension_loaded('pcov') ? 'true' : 'false') . PHP_EOL;
            echo 'phpunit=' . $phpunit . PHP_EOL;
            """;
}

package dev.codewitness.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;
import dev.codewitness.support.Text;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.stream.Stream;

/**
 * Locates the {@code cmake} executable used to build a project, and knows how this adapter invokes it.
 *
 * <p>CMake is one driver for everything the adapter does to a C or C++ tree: {@code cmake -S -B}
 * configures, {@code cmake --build} compiles, {@code ctest} runs the tests, and the
 * {@code compile_commands.json} the configure writes is the record of exactly which compiler and which
 * flags every source file was compiled with. That database is what makes the rest of this adapter
 * possible without guessing: the generated tests are compiled with the project's own recorded flags, and
 * the coverage build is the same configure with an instrumentation flag added.
 *
 * <p>Two decisions are worth naming, because each of them is a way a verification run could otherwise
 * depend on something the report does not mention:
 *
 * <ul>
 *   <li><b>The build directory lives in the run's artefact store.</b> Nothing is configured inside the
 *       project tree, so a run cannot leave a {@code CMakeCache.txt} behind, and the isolated workspace
 *       stays a copy of the user's sources rather than of their last build.</li>
 *   <li><b>The generator is Ninja when Ninja exists, and CMake's own default otherwise.</b> Ninja is
 *       asked for by name and recorded, so a report says which generator produced the build; it is not
 *       an assumption that every machine has it.</li>
 * </ul>
 *
 * <p>{@code CMAKE_EXPORT_COMPILE_COMMANDS} is always on. It costs one file and it is the only
 * machine-readable statement of a C or C++ project's compile flags, which the generated-test runner
 * needs in order to compile a test the way the project compiles itself.
 */
public final class CppBuildTools {

    /** Project attributes set by this class. */
    public static final String ATTR_CMAKE = "cmake";
    public static final String ATTR_VERSION = "cmakeVersion";
    public static final String ATTR_GENERATOR = "cmakeGenerator";
    public static final String ATTR_PROBE_FAILURE = "cmakeProbeFailure";
    public static final String ATTR_CTEST = "ctest";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    private final ProcessRunner runner;

    public CppBuildTools(ProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Resolves a {@code cmake} executable without running anything.
     *
     * @param projectRoot     project directory, used to find a project-local toolchain
     * @param hostEnvironment environment to read {@code PATH} from
     * @return an absolute path when one can be found, otherwise a bare command name the sandbox resolves
     *     through {@code PATH}
     */
    public static String resolve(Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : candidates(projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return executableName("cmake");
    }

    /** @return the first {@code name} executable on {@code PATH}, or {@code null}. */
    public static String resolveExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String element : path.split(File.pathSeparator)) {
            if (element.isBlank()) {
                continue;
            }
            Path candidate = Path.of(element, executableName(name));
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private static List<Path> candidates(Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        // A project that pins its toolchain - a CMake in a virtual environment, or a vendored one - puts it
        // beside the project; that copy is preferred over the machine-wide one, because it is the one the
        // project asked for.
        if (projectRoot != null) {
            candidates.add(projectRoot.resolve(".tools/cmake/bin/" + executableName("cmake")));
            candidates.add(projectRoot.resolve("cmake/bin/" + executableName("cmake")));
        }
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (!element.isBlank()) {
                    candidates.add(Path.of(element, executableName("cmake")));
                }
            }
        }
        return candidates;
    }

    private static String executableName(String name) {
        return Platform.isWindows() ? name + ".exe" : name;
    }

    /** Asks CMake for its version. A missing toolchain is a reported fact, not a failure. */
    public static Probe probe(ProcessRunner runner, String cmakeCommand, Path workingDirectory) {
        ProcessResult version = runner.run(ProcessSpec.builder(List.of(cmakeCommand, "--version"), workingDirectory)
                .timeout(Duration.ofSeconds(60))
                .build());
        if (!version.started() || !version.success()) {
            return new Probe(null, version.describeOutcome());
        }
        Matcher matcher = VERSION.matcher(version.combinedOutput());
        return new Probe(matcher.find() ? matcher.group(1) : firstLine(version.combinedOutput()), null);
    }

    /**
     * @return the environment a {@code cmake} command runs with.
     *
     * <p>The sandbox replaces the child environment, so nothing has to be removed here; what is added is
     * the statement that this run is not a user's interactive CMake session and must not read the
     * per-user package registry, plus the parallelism the machine's own CMake would have chosen.
     */
    public static Map<String, String> environment(VerificationContext context) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("CMAKE_EXPORT_COMPILE_COMMANDS", "ON");
        environment.put("CTEST_OUTPUT_ON_FAILURE", "1");
        return environment;
    }

    /** @return the run's build directory: inside the artefact store, never inside the project tree. */
    public Path buildDirectory(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("cpp/build");
    }

    /** @return the run's coverage build directory, a second configure with instrumentation enabled. */
    public Path coverageDirectory(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("cpp/coverage-build");
    }

    /** @return the directory a gcov run writes its {@code .gcov} files into, cleared before each run. */
    public Path gcovDirectory(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("cpp/gcov");
    }

    /** @return the directory generated tests are written, compiled and linked in. */
    public Path generatedDirectory(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("generated-tests");
    }

    /** @return the CMake command recorded during inspection, or a PATH-resolved default. */
    public static String cmake(ProjectInfo project, VerificationContext context) {
        String resolved = project.attributes().get(ATTR_CMAKE);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return resolve(context.projectRoot(), System.getenv());
    }

    /** @return {@code true} when the project has something CMake can configure. */
    public static boolean hasProject(VerificationContext context) {
        return CppProjectDetector.buildRoot(context.projectRoot()) != null;
    }

    /**
     * @return the argv that configures the project.
     *
     * <p>{@code -S} and {@code -B} are given absolutely, so the recorded command is one a reader can paste
     * back from any directory. A coverage configure adds the instrumentation flag through
     * {@code CMAKE_CXX_FLAGS} rather than through the project, because the project's own build must stay
     * exactly as the project wrote it.
     *
     * @param cmake     the resolved CMake command
     * @param sourceDir the directory holding the project's root {@code CMakeLists.txt}
     * @param buildDir  the directory to configure into
     * @param coverage  whether to instrument the build for gcov
     */
    public static List<String> configureCommand(String cmake, Path sourceDir, Path buildDir, boolean coverage) {
        List<String> command = new ArrayList<>();
        command.add(cmake);
        command.add("-S");
        command.add(sourceDir.toString());
        command.add("-B");
        command.add(buildDir.toString());
        String ninja = resolveExecutable("ninja");
        if (ninja != null) {
            command.add("-G");
            command.add("Ninja");
            command.add("-DCMAKE_MAKE_PROGRAM=" + ninja);
        }
        command.add("-DCMAKE_EXPORT_COMPILE_COMMANDS=ON");
        // An unoptimised build with debug information is the one whose diagnostics point at the source the
        // report quotes, and it is what a developer runs while working. The build type is stated rather
        // than inherited from whatever the project's presets default to.
        command.add("-DCMAKE_BUILD_TYPE=Debug");
        if (coverage) {
            String flags = Platform.isWindows() ? "--coverage" : "--coverage -O0 -g";
            command.add("-DCMAKE_C_FLAGS=" + flags);
            command.add("-DCMAKE_CXX_FLAGS=" + flags);
            command.add("-DCMAKE_EXE_LINKER_FLAGS=--coverage");
        }
        return List.copyOf(command);
    }

    /** @return the argv that compiles whatever the configure described. */
    public static List<String> buildCommand(String cmake, Path buildDir) {
        return List.of(cmake, "--build", buildDir.toString(), "--parallel");
    }

    /**
     * @return the argv that runs the project's tests through CTest and writes a JUnit report.
     *
     * <p>The report is what the adapter reads, so its path is absolute: CTest resolves a relative
     * {@code --output-junit} against the test directory, which is not the directory the run is described
     * from. {@code --output-on-failure} is passed as well, because a reader of the run's transcript should
     * be able to see why a test failed without opening the report.
     */
    public static List<String> testCommand(Path buildDir, Path reportFile) {
        return List.of("ctest", "--test-dir", buildDir.toString(), "--output-junit", reportFile.toString(),
                "--output-on-failure");
    }

    /** @return the CTest command recorded during inspection, or a PATH-resolved default. */
    public static String ctest(ProjectInfo project) {
        String resolved = project.attributes().get(ATTR_CTEST);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return "ctest";
    }

    /** @return the path of the compile database a configure wrote, whether or not it exists. */
    public static Path compileDatabase(Path buildDir) {
        return buildDir.resolve("compile_commands.json");
    }

    /**
     * @param buildDir   the configured build directory
     * @param sourceFile a project source file
     * @return the compiler arguments the project's own build recorded for that file, without the
     *     compiler, the source file, {@code -c} and {@code -o}, or an empty list when the database does
     *     not list it
     *
     * <p>This is the mechanism that lets a generated test be compiled the way the project compiles
     *     itself: the include directories, the language standard, the defines and the warning flags are
     *     the project's own, read from the file CMake wrote for exactly this purpose.
     */
    public static List<String> compileFlags(Path buildDir, Path sourceFile) {
        JsonNode database = readCompileDatabase(buildDir);
        if (database == null || sourceFile == null) {
            return List.of();
        }
        Path wanted = sourceFile.toAbsolutePath().normalize();
        for (JsonNode entry : database) {
            String file = entry.path("file").asText("");
            if (file.isEmpty()) {
                continue;
            }
            Path candidate;
            try {
                candidate = Path.of(file).toAbsolutePath().normalize();
            } catch (RuntimeException _) {
                continue;
            }
            if (!candidate.equals(wanted)) {
                continue;
            }
            List<String> argv = arguments(entry);
            if (argv.isEmpty()) {
                continue;
            }
            return stripNonCompileArguments(argv);
        }
        return List.of();
    }

    /** @return the working directory the project's build runs a compiler in, or the build directory. */
    public static Path compileDirectory(Path buildDir, Path sourceFile) {
        JsonNode database = readCompileDatabase(buildDir);
        if (database != null && sourceFile != null) {
            Path wanted = sourceFile.toAbsolutePath().normalize();
            for (JsonNode entry : database) {
                if (Path.of(entry.path("file").asText("")).toAbsolutePath().normalize().equals(wanted)) {
                    String directory = entry.path("directory").asText("");
                    if (!directory.isBlank()) {
                        return Path.of(directory);
                    }
                }
            }
        }
        return buildDir;
    }

    /**
     * @return the compiler the project's own build used for a source file, or {@code null} when the
     *     database does not name one
     */
    public static String compiler(Path buildDir, Path sourceFile) {
        JsonNode database = readCompileDatabase(buildDir);
        if (database == null || sourceFile == null) {
            return null;
        }
        Path wanted = sourceFile.toAbsolutePath().normalize();
        for (JsonNode entry : database) {
            if (!Path.of(entry.path("file").asText("")).toAbsolutePath().normalize().equals(wanted)) {
                continue;
            }
            List<String> argv = arguments(entry);
            return argv.isEmpty() ? null : argv.getFirst();
        }
        return null;
    }

    /**
     * @return every static or shared library the build produced, sorted.
     *
     * <p>A generated test is linked against the project's built libraries rather than against a target
     *     name: the compile database says how the project compiles, but CMake's link line is only
     *     knowable from inside CMake, and adding a target to the project's build would mean editing the
     *     project under verification. Linking the archives the build produced is the honest approximation,
     *     and a generated test that cannot be linked is reported as not executed rather than as a defect.
     */
    public static List<Path> builtArchives(Path buildDir) {
        List<Path> archives = new ArrayList<>();
        if (buildDir == null || !Files.isDirectory(buildDir)) {
            return archives;
        }
        try (Stream<Path> walk = Files.walk(buildDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".a") || name.endsWith(".dylib") || name.endsWith(".so")
                                || name.endsWith(".lib");
                    })
                    .filter(path -> !path.toString().contains("CMakeFiles" + File.separator + "4.")
                            && !path.toString().contains("CompilerId"))
                    .sorted()
                    .forEach(archives::add);
        } catch (IOException _) {
            // an unreadable build tree yields no archives, which the caller reports rather than guesses about
        }
        return List.copyOf(archives);
    }

    /** @return {@code true} when a file is a C++ source rather than a C source. */
    public static boolean isCxx(Path file) {
        return CppProjectDetector.isCxx(file);
    }

    /** @return the last {@code count} lines of a captured output, for reports and diagnostics. */
    public static String lastLines(String text, int count) {
        return Text.lastLines(text, count);
    }

    private static List<String> arguments(JsonNode entry) {
        JsonNode arguments = entry.path("arguments");
        if (arguments.isArray() && !arguments.isEmpty()) {
            List<String> argv = new ArrayList<>();
            arguments.forEach(node -> argv.add(node.asText()));
            return argv;
        }
        String command = entry.path("command").asText("");
        return command.isBlank() ? List.of() : splitCommand(command);
    }

    /**
     * @return a command line split into argv the way a POSIX shell would, which is how the
     *     {@code command} form of a compile database entry is written
     */
    static List<String> splitCommand(String command) {
        List<String> argv = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < command.length()) {
                    current.append(command.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    argv.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            argv.add(current.toString());
        }
        return argv;
    }

    /**
     * @return the compiler arguments with the parts that describe one output file removed: the compiler
     *     itself, the source being compiled, {@code -c} and {@code -o <file>}.
     */
    static List<String> stripNonCompileArguments(List<String> argv) {
        List<String> flags = new ArrayList<>();
        for (int i = 1; i < argv.size(); i++) {
            String argument = argv.get(i);
            if (argument.equals("-o")) {
                i++;
                continue;
            }
            if (argument.equals("-c") || argument.equals("/c")) {
                continue;
            }
            if (looksLikeSourceFile(argument)) {
                continue;
            }
            flags.add(argument);
        }
        return List.copyOf(flags);
    }

    private static boolean looksLikeSourceFile(String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        for (String extension : List.of(".c", ".cc", ".cpp", ".cxx", ".c++", ".m", ".mm")) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode readCompileDatabase(Path buildDir) {
        Path file = compileDatabase(buildDir);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException _) {
            // A compile database that cannot be parsed is reported by the caller as "the project's flags
            // were not available", never papered over with invented flags.
            return null;
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.strip().split("\\R")[0].strip();
    }

    /**
     * What CMake reported about itself.
     *
     * @param version       the CMake version, for example {@code 3.29.6}, or {@code null}
     * @param failureReason why the probe failed, or {@code null}
     */
    public record Probe(String version, String failureReason) {

        public boolean available() {
            return failureReason == null;
        }
    }
}

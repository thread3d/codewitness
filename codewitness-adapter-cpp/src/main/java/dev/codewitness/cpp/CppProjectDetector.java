package dev.codewitness.cpp;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects C and C++ projects and describes how CMake will build them.
 *
 * <p>Only files are read here; nothing is executed. A C or C++ tree announces itself in one of two
 * ways, and only the first is one this adapter can verify.
 *
 * <ul>
 *   <li>A {@code CMakeLists.txt} that declares a project. That is the descriptor the adapter claims a
 *       tree by, and every later phase drives the build tool over it.</li>
 *   <li>C or C++ sources with no CMake project at all - a hand-written {@code Makefile}, a
 *       {@code meson.build}, or a directory of source files. The layout is reported with a warning so
 *       the report says which language the tree holds, and nothing is built, tested or mutated: the
 *       adapter drives one build system, and a build it guessed at would be a build nobody runs.</li>
 * </ul>
 *
 * <p>The top-level project file is the shallowest {@code CMakeLists.txt} that calls {@code project()}.
 * That is CMake's own rule for which file is the root of a build, and it is why a nested
 * {@code add_subdirectory} project does not become a second build: it is part of the first.
 *
 * <p>Test sources are C and C++ files under a directory named {@code test} or {@code tests}, or named
 * {@code test_*} / {@code *_test}. That is a convention rather than a declaration, and it is stated as
 * one: CMake has no notion of a test source directory, so the alternative would be to treat every file
 * as production code and let the requirement mapper read a test as the code it tests.
 */
public final class CppProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_CMAKE_LISTS = "cmakeLists";
    public static final String ATTR_TARGETS = "targetCount";
    public static final String ATTR_LIBRARY_TARGETS = "libraryTargets";
    public static final String ATTR_TEST_TARGETS = "testTargets";
    public static final String ATTR_CTEST_TESTS = "ctestTestCount";
    public static final String ATTR_CPP_STANDARD = "cppStandard";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";
    public static final String ATTR_CXX_SOURCES = "cxxSourceCount";
    public static final String ATTR_C_SOURCES = "cSourceCount";

    /**
     * Descriptor files that identify a C or C++ project, in the order they are preferred.
     *
     * <p>{@code CMakeLists.txt} is not in this list: it is found by walking, because a project's root
     * file is the shallowest one that declares a project rather than necessarily one at the top.
     */
    private static final List<String> ROOT_DESCRIPTORS = List.of(
            "CMakePresets.json", "CMakeUserPresets.json", "vcpkg.json", "conanfile.txt", "conanfile.py",
            "conan.lock", ".clang-tidy", ".clang-format", ".clangd");

    /** Extensions that are C or C++ source, in the order a report lists them. */
    private static final List<String> SOURCE_EXTENSIONS = List.of(
            "cc", "cpp", "cxx", "c++", "c", "hpp", "hh", "hxx", "h", "ipp", "tpp");

    /** How deep to look for a CMake project file. Nested projects use {@code src/Product/CMakeLists.txt}. */
    private static final int MAX_PROJECT_DEPTH = 6;

    /** {@code project(Name ...)} or {@code project(Name)}. */
    private static final Pattern PROJECT_DECLARATION =
            Pattern.compile("(?m)^\\s*project\\s*\\(\\s*([A-Za-z0-9_.\\-]+)");
    private static final Pattern ADD_LIBRARY = Pattern.compile("\\badd_library\\s*\\(\\s*([A-Za-z0-9_.\\-]+)");
    private static final Pattern ADD_EXECUTABLE =
            Pattern.compile("\\badd_executable\\s*\\(\\s*([A-Za-z0-9_.\\-]+)");
    /** {@code add_test(NAME name ...)} and the older {@code add_test(name ...)}. */
    private static final Pattern ADD_TEST =
            Pattern.compile("\\badd_test\\s*\\(\\s*(?:NAME\\s+)?([A-Za-z0-9_.\\-]+)");
    private static final Pattern CXX_STANDARD =
            Pattern.compile("(?m)^\\s*set\\s*\\(\\s*CMAKE_CXX_STANDARD\\s+(\\d+)");

    private CppProjectDetector() {
    }

    /** @return {@code true} when the directory looks like a C or C++ project this adapter can verify. */
    public static boolean looksLikeCppProject(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        return buildRoot(root) != null && !sourceFiles(root).isEmpty();
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        List<Path> cmakeFiles = cmakeListsFiles(root);
        Path buildRoot = buildRoot(root, cmakeFiles);

        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.CPP)
                .buildRoot(buildRoot == null ? root : buildRoot)
                .buildSystem(buildRoot == null ? BuildSystemId.NONE : BuildSystemId.CMAKE);

        List<String> descriptors = new ArrayList<>();
        for (String descriptor : ROOT_DESCRIPTORS) {
            Path file = root.resolve(descriptor);
            if (Files.isRegularFile(file)) {
                descriptors.add(descriptor);
                builder.addDetectedDescriptor(file);
            }
        }
        for (Path cmakeFile : cmakeFiles) {
            builder.addDetectedDescriptor(cmakeFile);
        }
        if (buildRoot != null) {
            descriptors.add(0, relative(root, buildRoot.resolve("CMakeLists.txt")));
            builder.attribute(ATTR_CMAKE_LISTS, relative(root, buildRoot.resolve("CMakeLists.txt")));
        }
        builder.attribute(ATTR_DESCRIPTOR, descriptors.isEmpty() ? "none" : String.join(",", descriptors));

        List<Path> sources = sourceFiles(root);
        Set<String> extensions = extensionsOf(sources);
        builder.sourceExtensions(extensions);
        builder.attribute(ATTR_SOURCE_EXTENSIONS, String.join(",", extensions));

        // Only implementation files decide which of the two languages a tree holds. A header belongs to
        // whichever language includes it and is frequently included by both, so counting headers would put
        // "C" in a report about a project that is entirely C++.
        int cSources = 0;
        int cxxSources = 0;
        for (Path source : sources) {
            switch (extensionOf(source)) {
                case "c" -> cSources++;
                case "cc", "cpp", "cxx", "c++", "ipp", "tpp" -> cxxSources++;
                default -> {
                    // a header: the report still lists its extension, but it names neither language
                }
            }
        }
        builder.attribute(ATTR_C_SOURCES, String.valueOf(cSources));
        builder.attribute(ATTR_CXX_SOURCES, String.valueOf(cxxSources));

        if (buildRoot == null) {
            builder.attribute(ATTR_DETECTION_WARNING,
                    "no CMakeLists.txt declaring a project was found; the C or C++ sources can be analysed "
                            + "but there is no CMake project to configure, build, test or mutate");
        }

        String cmakeText = read(buildRoot == null ? null : buildRoot.resolve("CMakeLists.txt"));
        List<String> libraries = targets(root, ADD_LIBRARY);
        List<String> executables = targets(root, ADD_EXECUTABLE);
        List<Path> tests = testSourceFiles(root);
        List<String> ctestTests = ctestTests(root);

        List<String> testTargets = new ArrayList<>();
        for (String executable : executables) {
            if (looksLikeTestTarget(executable, ctestTests)) {
                testTargets.add(executable);
            }
        }
        builder.attribute(ATTR_LIBRARY_TARGETS, String.join(",", libraries));
        builder.attribute(ATTR_TEST_TARGETS, String.join(",", testTargets));
        builder.attribute(ATTR_TARGETS, String.valueOf(libraries.size() + executables.size()));
        builder.attribute(ATTR_CTEST_TESTS, String.valueOf(ctestTests.size()));

        String standard = cxxStandard(cmakeText);
        if (standard != null) {
            builder.attribute(ATTR_CPP_STANDARD, standard);
        }
        String framework = testFramework(root, ctestTests);
        builder.addTestFramework(framework);
        builder.attribute(ATTR_TEST_FRAMEWORK, framework);
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!tests.isEmpty() || !ctestTests.isEmpty()));
        builder.attribute(ATTR_PACKAGE_MANAGER, packageManager(root));

        List<Path> mainDirs = mainSourceDirs(root);
        List<Path> testDirs = testSourceDirs(root);
        if (mainDirs.isEmpty() && testDirs.isEmpty()) {
            // Nothing to analyse anywhere, but a module keeps the layout explicit rather than leaving the
            // report with no module at all.
            mainDirs = List.of(root);
        }
        builder.addModule(new ModuleInfo(projectName(cmakeText, root), buildRoot == null ? root : buildRoot,
                "cmake", mainDirs, testDirs, null));
        return builder.build();
    }

    /**
     * @return the directory whose {@code CMakeLists.txt} is the root of the build, or {@code null} when
     *     the tree has no CMake project.
     */
    public static Path buildRoot(Path root) {
        return buildRoot(root, cmakeListsFiles(root));
    }

    private static Path buildRoot(Path root, List<Path> cmakeFiles) {
        Path declaring = null;
        for (Path file : cmakeFiles) {
            if (PROJECT_DECLARATION.matcher(read(file)).find()) {
                declaring = file.getParent();
                break;
            }
        }
        if (declaring != null) {
            return declaring;
        }
        // A CMakeLists.txt with no project() is still a build file, and CMake accepts a root that omits
        // it. The shallowest one is the best available answer, and the detector says nothing it cannot
        // support: the build either works or fails as a build.
        return cmakeFiles.isEmpty() ? null : cmakeFiles.getFirst().getParent();
    }

    /**
     * @return every {@code CMakeLists.txt} below {@code root}, sorted shallowest first and then by path
     *     so that nested projects are found before their own subdirectories.
     */
    public static List<Path> cmakeListsFiles(Path root) {
        List<Path> files = filesWithSuffix(root, "cmakelists.txt", MAX_PROJECT_DEPTH);
        files.sort(java.util.Comparator
                .comparingInt((Path path) -> path.getNameCount())
                .thenComparing(Path::toString));
        return files;
    }

    /** @return every C or C++ source file below {@code root}, sorted, skipping build output and vendors. */
    public static List<Path> sourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        for (String extension : SOURCE_EXTENSIONS) {
            files.addAll(filesWithSuffix(root, "." + extension, Integer.MAX_VALUE));
        }
        files.sort(java.util.Comparator.comparing(Path::toString));
        return files;
    }

    /** @return every C or C++ file below {@code root} that the naming convention marks as a test. */
    public static List<Path> testSourceFiles(Path root) {
        List<Path> tests = new ArrayList<>();
        for (Path file : sourceFiles(root)) {
            if (isTestFile(root, file)) {
                tests.add(file);
            }
        }
        return List.copyOf(tests);
    }

    /** @return {@code true} when the path lies under a test directory or carries a test file name. */
    public static boolean isTestFile(Path root, Path file) {
        if (file == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String stem = stripExtension(name);
        if (stem.startsWith("test_") || stem.startsWith("test-") || stem.endsWith("_test")
                || stem.endsWith("-test") || stem.endsWith("test")) {
            return true;
        }
        for (Path element : relativeElements(root, file)) {
            String directory = element.toString().toLowerCase(Locale.ROOT);
            if (directory.equals("test") || directory.equals("tests") || directory.equals("testing")
                    || directory.equals("unittest") || directory.equals("unittests")) {
                return true;
            }
        }
        return false;
    }

    /** @return every test name a tree declares with {@code add_test}, in first-seen order. */
    public static List<String> ctestTests(Path root) {
        Set<String> names = new LinkedHashSet<>();
        for (Path cmakeFile : cmakeListsFiles(root)) {
            Matcher matcher = ADD_TEST.matcher(stripComments(read(cmakeFile)));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return List.copyOf(names);
    }

    /** @return the names of every library target the CMake files declare, in first-seen order. */
    public static List<String> libraryTargets(Path root) {
        return targets(root, ADD_LIBRARY);
    }

    /** @return the names of every executable target the CMake files declare, in first-seen order. */
    public static List<String> executableTargets(Path root) {
        return targets(root, ADD_EXECUTABLE);
    }

    /** @return the directories holding non-test sources, in path order. */
    static List<Path> mainSourceDirs(Path root) {
        List<Path> main = new ArrayList<>();
        for (Path file : sourceFiles(root)) {
            if (!isTestFile(root, file)) {
                main.add(file);
            }
        }
        return directoriesOf(main);
    }

    /** @return the directories holding test sources, in path order. */
    static List<Path> testSourceDirs(Path root) {
        return directoriesOf(testSourceFiles(root));
    }

    /**
     * @return the CMake project's own name, or the directory name when the file declares none.
     *
     * <p>{@code project(name CXX)} is the project's statement of what it is called, and using it means a
     * report names the project the way its build does rather than the way its checkout directory happens
     * to be named.
     */
    public static String projectName(String cmakeText, Path root) {
        Matcher matcher = PROJECT_DECLARATION.matcher(cmakeText == null ? "" : cmakeText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Path name = root == null ? null : root.getFileName();
        return name == null ? "cpp-project" : name.toString();
    }

    /** @return the declared {@code CMAKE_CXX_STANDARD}, or {@code null} when the project declares none. */
    static String cxxStandard(String cmakeText) {
        if (cmakeText == null || cmakeText.isEmpty()) {
            return null;
        }
        Matcher matcher = CXX_STANDARD.matcher(stripComments(cmakeText));
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * @return the test framework the tree uses, as the report names it: the compiled test framework a
     *     target links when the CMake files name one, otherwise CTest, which every CMake project with
     *     {@code add_test} already uses.
     */
    static String testFramework(Path root, List<String> ctestTests) {
        StringBuilder text = new StringBuilder();
        for (Path cmakeFile : cmakeListsFiles(root)) {
            text.append(read(cmakeFile)).append('\n');
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("googletest") || lower.contains("gtest")) {
            return "googletest";
        }
        if (lower.contains("catch2") || lower.contains("catch_discover_tests")) {
            return "catch2";
        }
        if (lower.contains("doctest")) {
            return "doctest";
        }
        if (lower.contains("boost_test") || lower.contains("boost/test")) {
            return "boost.test";
        }
        return "ctest";
    }

    /** @return the package manager whose manifest the tree carries, for the report's own words. */
    static String packageManager(Path root) {
        if (Files.isRegularFile(root.resolve("vcpkg.json")) || Files.isRegularFile(root.resolve("vcpkg-configuration.json"))) {
            return "vcpkg";
        }
        if (Files.isRegularFile(root.resolve("conanfile.txt")) || Files.isRegularFile(root.resolve("conanfile.py"))) {
            return "conan";
        }
        if (Files.isRegularFile(root.resolve("CMakeLists.txt"))
                && read(root.resolve("CMakeLists.txt")).contains("FetchContent")) {
            return "fetchcontent";
        }
        return "system";
    }

    /** @return the named targets of one kind across every CMake file, in first-seen order. */
    static List<String> targets(Path root, Pattern pattern) {
        Set<String> names = new LinkedHashSet<>();
        for (Path cmakeFile : cmakeListsFiles(root)) {
            Matcher matcher = pattern.matcher(stripComments(read(cmakeFile)));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return List.copyOf(names);
    }

    /**
     * @return {@code true} when an executable target is a test: its own name ends in {@code test}, or
     *     CTest registers it.
     */
    private static boolean looksLikeTestTarget(String target, List<String> ctestTests) {
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.endsWith("test") || lower.endsWith("tests") || lower.startsWith("test_")) {
            return true;
        }
        return ctestTests.stream().anyMatch(name -> name.equalsIgnoreCase(target));
    }

    /**
     * @param files a set of source files
     * @return the directories that hold them, deduplicated and sorted
     */
    private static List<Path> directoriesOf(List<Path> files) {
        Set<Path> directories = new LinkedHashSet<>();
        for (Path file : files) {
            Path directory = file.getParent();
            if (directory != null) {
                directories.add(directory.toAbsolutePath().normalize());
            }
        }
        List<Path> sorted = new ArrayList<>(directories);
        sorted.sort(java.util.Comparator.comparing(Path::toString));
        return List.copyOf(sorted);
    }

    /** @return the extensions of the given files, in the canonical order, without the dot. */
    static Set<String> extensionsOf(List<Path> files) {
        Set<String> present = new LinkedHashSet<>();
        for (Path file : files) {
            present.add(extensionOf(file));
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String extension : SOURCE_EXTENSIONS) {
            if (present.contains(extension)) {
                ordered.add(extension);
            }
        }
        return ordered;
    }

    /** @return {@code true} when the file is compiled as C++ rather than as C. */
    static boolean isCxx(Path file) {
        String extension = extensionOf(file);
        return extension.equals("cc") || extension.equals("cpp") || extension.equals("cxx")
                || extension.equals("c++") || extension.equals("hpp") || extension.equals("hh")
                || extension.equals("hxx") || extension.equals("ipp") || extension.equals("tpp");
    }

    public static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * @return the file with its CMake comments removed, so that a commented-out {@code add_executable}
     *     is not read as a target and a {@code #} inside a string does not truncate the text.
     */
    public static String stripComments(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        boolean inQuote = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (inQuote) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    out.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inQuote = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inQuote = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '#') {
                int end = text.indexOf('\n', i);
                if (end < 0) {
                    break;
                }
                // The newline is kept so that a match's reported position still points at the right line.
                out.append('\n');
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * @param root   the directory to walk
     * @param suffix the lower-case file name suffix to collect, including the dot
     * @param depth  maximum depth, or {@link Integer#MAX_VALUE} for the whole tree
     * @return the matching files, sorted
     */
    static List<Path> filesWithSuffix(Path root, String suffix, int depth) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root, depth)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .filter(path -> !isExcluded(root, path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /**
     * @return {@code true} when a path element below the walked root is build output, a package cache or
     *     VCS metadata.
     *
     * <p>The check is relative to the walked root rather than absolute on purpose: a run's isolated
     * workspace lives under the project's {@code .codewitness} directory, so an absolute check would
     * reject every file in the workspace.
     */
    static boolean isExcluded(Path root, Path path) {
        for (Path element : relativeElements(root, path)) {
            if (EXCLUDED_DIRECTORIES.contains(element.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> relativeElements(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        List<Path> elements = new ArrayList<>();
        for (Path element : relative) {
            elements.add(element);
        }
        return elements;
    }

    /** Directories that never hold the project's own code, relative to the walked root. */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "build", "cmakefiles", "_deps", "vcpkg_installed", "conan", "third_party", "3rdparty",
            "external", "extern", "vendor", "node_modules", ".git", ".codewitness", ".cache",
            "cmake-build-debug", "cmake-build-release", "debug", "release", "out", "target");

    private static String relative(Path root, Path file) {
        if (root == null || file == null) {
            return String.valueOf(file);
        }
        Path normalized = file.toAbsolutePath().normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString() : normalized.toString();
    }

    public static String read(Path file) {
        if (file == null) {
            return "";
        }
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }
}

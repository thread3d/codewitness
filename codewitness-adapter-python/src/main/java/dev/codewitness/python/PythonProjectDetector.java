package dev.codewitness.python;

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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects Python projects and describes their layout.
 *
 * <p>Only files are read here; nothing is executed. Detection is deliberately conservative: when a
 * descriptor cannot be parsed the detector reports that through {@link ProjectInfo#attributes()}
 * rather than guessing a layout that later phases would trust.
 *
 * <p>Python has no single build file, so the detector recognises the common ones and works from the
 * source tree when none is present. A project without a descriptor can still be syntax-checked,
 * tested and analysed; it simply has nothing to install.
 */
public final class PythonProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";
    public static final String ATTR_PYTHON_VERSION = "pythonVersion";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";

    /** Descriptor files that identify a Python project, in the order they are preferred. */
    private static final List<String> DESCRIPTORS = List.of(
            "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile", "tox.ini", "pytest.ini");

    private static final Pattern REQUIRES_PYTHON = Pattern.compile(
            "(?m)^\\s*requires-python\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PYPROJECT_NAME = Pattern.compile(
            "(?m)^\\s*name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern VERSION_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+){1,2})");

    private PythonProjectDetector() {
    }

    /** @return {@code true} when the directory looks like a Python project this adapter can handle. */
    public static boolean looksLikePythonProject(Path root) {
        for (String descriptor : DESCRIPTORS) {
            if (Files.isRegularFile(root.resolve(descriptor))) {
                return true;
            }
        }
        return containsPythonSource(root, 3);
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.PYTHON)
                .sourceExtensions(Set.of("py", "pyi"))
                .attribute(ATTR_SOURCE_EXTENSIONS, "py,pyi");

        List<String> descriptors = new ArrayList<>();
        for (String descriptor : DESCRIPTORS) {
            Path file = root.resolve(descriptor);
            if (Files.isRegularFile(file)) {
                descriptors.add(descriptor);
                builder.addDetectedDescriptor(file);
            }
        }
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none")
                    .attribute(ATTR_DETECTION_WARNING,
                            "no Python build descriptor (pyproject.toml, setup.py, requirements.txt, ...) found; "
                                    + "the project can be syntax-checked, tested and analysed but not installed");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors));
        }
        builder.buildSystem(descriptors.isEmpty() ? BuildSystemId.NONE : BuildSystemId.PYTHON);

        List<Path> sourceDirs = detectSourceDirs(root);
        List<Path> testDirs = detectTestDirs(root);
        String name = detectProjectName(project, root);
        String pythonVersion = detectPythonVersion(root);
        if (pythonVersion != null) {
            builder.attribute(ATTR_PYTHON_VERSION, pythonVersion);
        }
        builder.attribute(ATTR_PACKAGE_MANAGER, detectPackageManager(root));

        builder.addModule(new ModuleInfo(name, root, "python", sourceDirs, testDirs, null));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testDirs.isEmpty()));

        String framework = detectTestFramework(root, descriptors);
        builder.addTestFramework(framework);
        builder.attribute(ATTR_TEST_FRAMEWORK, framework);
        return builder.build();
    }

    /** @return main source roots: {@code src} for a src-layout, otherwise the project root. */
    static List<Path> detectSourceDirs(Path root) {
        Path src = root.resolve("src");
        if (Files.isDirectory(src) && containsPythonSource(src, 6)) {
            return List.of(src);
        }
        // A flat layout keeps modules next to the descriptor. Using the project root keeps imports
        // working exactly as they do when a developer runs `python -m pytest` from the root.
        return List.of(root);
    }

    /** @return test roots: {@code tests} or {@code test} when they contain Python tests. */
    static List<Path> detectTestDirs(Path root) {
        List<Path> result = new ArrayList<>();
        for (String candidate : List.of("tests", "test")) {
            Path directory = root.resolve(candidate);
            if (Files.isDirectory(directory) && containsPythonSource(directory, 8)) {
                result.add(directory);
            }
        }
        return List.copyOf(result);
    }

    private static String detectProjectName(ProjectRef project, Path root) {
        Path pyproject = root.resolve("pyproject.toml");
        if (Files.isRegularFile(pyproject)) {
            String content = readString(pyproject);
            Matcher matcher = PYPROJECT_NAME.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return project.name();
    }

    /** @return the declared Python version, for example {@code 3.11}, or {@code null}. */
    static String detectPythonVersion(Path root) {
        Path pyproject = root.resolve("pyproject.toml");
        if (Files.isRegularFile(pyproject)) {
            Matcher matcher = REQUIRES_PYTHON.matcher(readString(pyproject));
            if (matcher.find()) {
                Matcher version = VERSION_NUMBER.matcher(matcher.group(1));
                if (version.find()) {
                    return version.group(1);
                }
            }
        }
        for (String file : List.of(".python-version", "runtime.txt")) {
            Path path = root.resolve(file);
            if (Files.isRegularFile(path)) {
                Matcher version = VERSION_NUMBER.matcher(readString(path).trim());
                if (version.find()) {
                    return version.group(1);
                }
            }
        }
        return null;
    }

    /** @return the packaging tool the project appears to use. */
    static String detectPackageManager(Path root) {
        Path pyproject = root.resolve("pyproject.toml");
        if (Files.isRegularFile(pyproject)) {
            String content = readString(pyproject).toLowerCase(Locale.ROOT);
            if (content.contains("poetry")) {
                return "poetry";
            }
            if (content.contains("hatch")) {
                return "hatch";
            }
            if (content.contains("pdm")) {
                return "pdm";
            }
            if (content.contains("setuptools")) {
                return "setuptools";
            }
            if (content.contains("flit")) {
                return "flit";
            }
        }
        if (Files.isRegularFile(root.resolve("Pipfile"))) {
            return "pipenv";
        }
        if (Files.isRegularFile(root.resolve("setup.py"))) {
            return "setuptools";
        }
        if (Files.isRegularFile(root.resolve("requirements.txt"))) {
            return "pip";
        }
        return "unknown";
    }

    /**
     * @return {@code pytest} when the project configures pytest, otherwise {@code unittest}.
     *
     * <p>This is the framework the project declares, not a statement about what is installed. The
     * test provider prefers pytest and falls back to the standard library runner, and records which
     * one it actually used.
     */
    static String detectTestFramework(Path root, List<String> descriptors) {
        for (String descriptor : List.of("pytest.ini", "tox.ini")) {
            if (Files.isRegularFile(root.resolve(descriptor))) {
                return "pytest";
            }
        }
        Path pyproject = root.resolve("pyproject.toml");
        if (Files.isRegularFile(pyproject)) {
            String content = readString(pyproject).toLowerCase(Locale.ROOT);
            if (content.contains("[tool.pytest") || content.contains("pytest")) {
                return "pytest";
            }
        }
        for (String descriptor : List.of("requirements.txt", "setup.cfg")) {
            Path file = root.resolve(descriptor);
            if (Files.isRegularFile(file) && readString(file).toLowerCase(Locale.ROOT).contains("pytest")) {
                return "pytest";
            }
        }
        return "unittest";
    }

    /** @return {@code true} when a shallow walk finds a Python source file below {@code root}. */
    static boolean containsPythonSource(Path root, int maxDepth) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            return walk.anyMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".py"));
        } catch (IOException _) {
            return false;
        }
    }

    /** @return the Python files below a root, sorted, skipping caches and virtual environments. */
    public static List<Path> pythonFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".py"))
                    .filter(path -> !isExcluded(path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /** @return {@code true} when any path element is a cache, virtual environment or VCS directory. */
    static boolean isExcluded(Path path) {
        Set<String> excluded = Set.of("__pycache__", ".venv", "venv", ".tox", ".nox", ".git",
                ".pytest_cache", ".mypy_cache", ".ruff_cache", ".eggs", "node_modules", "build", "dist",
                ".codewitness");
        for (Path element : path) {
            if (excluded.contains(element.toString()) || element.toString().endsWith(".egg-info")) {
                return true;
            }
        }
        return false;
    }

    static String readString(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }
}

package dev.codewitness.go;

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
 * Detects Go projects and describes their module layout.
 *
 * <p>Only files are read here; nothing is executed. Go modules are strongly conventional, so
 * detection is mostly a matter of reading {@code go.mod}: the module path, the declared language
 * version and the fact that packages may live in any subdirectory. Unlike Java or Rust, Go has no
 * separate test tree: tests sit beside the code as {@code *_test.go}, so the source and test roots
 * are the same directory and the package directory is discovered by walking the module.
 *
 * <p>A directory with Go sources but no {@code go.mod} is still detected, because it can be built in
 * module-less mode and analysed even though it cannot be tested with module resolution.
 */
public final class GoProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_MODULE = "goModule";
    public static final String ATTR_GO_VERSION = "goLanguageVersion";
    public static final String ATTR_PACKAGE = "rootPackage";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";
    public static final String ATTR_PACKAGE_COUNT = "packageCount";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";

    /** Descriptor files that identify a Go project, in the order they are preferred. */
    private static final List<String> DESCRIPTORS = List.of("go.mod", "go.sum", "go.work");

    private static final Pattern MODULE = Pattern.compile("(?m)^\\s*module\\s+[\"']?([^\"'\\s]+)[\"']?\\s*$");
    private static final Pattern GO_VERSION = Pattern.compile("(?m)^\\s*go\\s+(\\d+(?:\\.\\d+){0,2})\\s*$");
    private static final Pattern PACKAGE_CLAUSE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*");

    private GoProjectDetector() {
    }

    /** @return {@code true} when the directory looks like a Go project this adapter can handle. */
    public static boolean looksLikeGoProject(Path root) {
        if (Files.isRegularFile(root.resolve("go.mod"))) {
            return true;
        }
        return containsGoSource(root, 3);
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.GO)
                .sourceExtensions(Set.of("go"))
                .attribute(ATTR_SOURCE_EXTENSIONS, "go");

        List<String> descriptors = new ArrayList<>();
        for (String descriptor : DESCRIPTORS) {
            Path file = root.resolve(descriptor);
            if (Files.isRegularFile(file)) {
                descriptors.add(descriptor);
                builder.addDetectedDescriptor(file);
            }
        }
        boolean manifest = Files.isRegularFile(root.resolve("go.mod"));
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none")
                    .attribute(ATTR_DETECTION_WARNING,
                            "no go.mod was found; the Go sources can be analysed but the module cannot "
                                    + "be built, tested or mutated");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors));
        }
        builder.buildSystem(manifest ? BuildSystemId.GO : BuildSystemId.NONE);
        builder.attribute(ATTR_PACKAGE_MANAGER, manifest ? "go" : "unknown");

        String module = detectModule(root);
        if (module != null) {
            builder.attribute(ATTR_MODULE, module);
        }
        String goVersion = detectGoVersion(root);
        if (goVersion != null) {
            // The runtime version is recorded so a report can be reproduced against the same toolchain.
            builder.attribute(ATTR_GO_VERSION, goVersion).attribute("runtimeVersion", "Go " + goVersion);
        }

        List<Path> packageDirs = packageDirs(root);
        builder.attribute(ATTR_PACKAGE_COUNT, String.valueOf(packageDirs.size()));
        if (!packageDirs.isEmpty()) {
            builder.attribute(ATTR_PACKAGE, packageName(packageDirs.getFirst()));
        }

        // Go has one module directory and no separate test root: the package tree is both. The
        // requirement mapper knows that only *_test.go files in that tree are tests.
        builder.addModule(new ModuleInfo(module == null ? project.name() : module, root, "module",
                List.of(root), hasTests(root) ? List.of(root) : List.of(), null));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(hasTests(root)));
        builder.addTestFramework("testing");
        builder.attribute(ATTR_TEST_FRAMEWORK, "testing");
        return builder.build();
    }

    /** @return the module path declared in {@code go.mod}, or {@code null}. */
    static String detectModule(Path root) {
        Path manifest = root.resolve("go.mod");
        if (Files.isRegularFile(manifest)) {
            Matcher matcher = MODULE.matcher(readString(manifest));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** @return the language version declared by the {@code go} directive, for example {@code 1.21}. */
    static String detectGoVersion(Path root) {
        Path manifest = root.resolve("go.mod");
        if (Files.isRegularFile(manifest)) {
            Matcher matcher = GO_VERSION.matcher(readString(manifest));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * @return every directory that contains Go source, sorted, skipping build output, vendored code
     *     and VCS metadata.
     */
    public static List<Path> packageDirs(Path root) {
        List<Path> dirs = new ArrayList<>();
        for (Path file : goFiles(root)) {
            Path dir = file.getParent();
            if (dir != null && !dirs.contains(dir)) {
                dirs.add(dir);
            }
        }
        dirs.sort(java.util.Comparator.comparing(Path::toString));
        return List.copyOf(dirs);
    }

    /** @return the package clause of the first Go file directly in a directory, or {@code "main"}. */
    public static String packageName(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return "main";
        }
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".go")) {
                    continue;
                }
                Matcher matcher = PACKAGE_CLAUSE.matcher(readString(file));
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (IOException _) {
            // an unreadable directory yields the default package name, which the caller reports
        }
        return "main";
    }

    /** @return {@code true} when any {@code *_test.go} file exists below {@code root}. */
    static boolean hasTests(Path root) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root, 8)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("_test.go"))
                    .anyMatch(path -> !isExcluded(root, path));
        } catch (IOException _) {
            return false;
        }
    }

    /** @return {@code true} when a shallow walk finds a Go source file below {@code root}. */
    static boolean containsGoSource(Path root, int maxDepth) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            return walk.anyMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".go"));
        } catch (IOException _) {
            return false;
        }
    }

    /** @return the Go files below a root, sorted, skipping vendored code and build output. */
    public static List<Path> goFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".go"))
                    .filter(path -> !isExcluded(root, path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /**
     * @return {@code true} when a path element <em>below the walked root</em> is build output, a
     *     cache, vendored code or a VCS directory.
     *
     * <p>The check is relative to the root rather than absolute on purpose. A run's isolated workspace
     * lives under the project's {@code .codewitness} directory, so an absolute check would reject every
     * file in the workspace and silently find no source at all.
     */
    static boolean isExcluded(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        Set<String> excluded = Set.of(
                "vendor", ".git", ".codewitness", "node_modules", "testdata", "bin", ".idea", ".vscode");
        for (Path element : relative) {
            if (excluded.contains(element.toString().toLowerCase(Locale.ROOT))) {
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

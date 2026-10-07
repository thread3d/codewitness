package dev.codewitness.javascript;

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
 * Detects JavaScript projects and describes their Node layout.
 *
 * <p>Only files are read here; nothing is executed. A project is JavaScript when it has a
 * {@code package.json} beside JavaScript sources, or when it contains an unambiguous Node test file
 * ({@code *.test.js}, {@code *.spec.mjs}, …). A directory of loose {@code .js} assets with no manifest
 * and no tests is not claimed: there would be nothing to build or run.
 *
 * <p>The check is deliberately exclusive with the TypeScript adapter. A project that has a
 * {@code tsconfig.json} or any {@code .ts}/{@code .tsx}/{@code .mts}/{@code .cts} source is left to
 * {@code codewitness-adapter-typescript}, which can actually type check it; this adapter never answers
 * for code it cannot compile. Registration order makes that safe in both directions.
 *
 * <p>JSX is not supported either: {@code .jsx} is not a source or test extension here, because Node
 * cannot execute JSX without a transform. A project whose sources or tests are all JSX is therefore
 * left unclaimed, and a {@code .jsx} test is never collected as if it had run.
 *
 * <p>There is one module. The conventional {@code src} directory holds the sources when it exists, and
 * the test directories Node's own runner uses ({@code test}, {@code tests}, {@code __tests__},
 * {@code src/__tests__}) hold the tests. Generated tests are written into the first test directory, so
 * a scenario fragment imports the project exactly as a hand-written test in that directory would.
 */
public final class JavaScriptProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_PACKAGE_NAME = "packageName";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_DETECTED_TEST_FRAMEWORKS = "detectedTestFrameworks";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";
    public static final String ATTR_MODULE_TYPE = "moduleType";

    /** Descriptor files that identify a Node/JavaScript project, in the order they are preferred. */
    private static final List<String> DESCRIPTORS = List.of(
            "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lockb");

    /** Source extensions this adapter owns. */
    static final Set<String> SOURCE_EXTENSIONS = Set.of("js", "mjs", "cjs");

    /** Conventional test directories, in the order they are preferred. */
    private static final List<String> TEST_DIRECTORIES = List.of(
            "tests", "test", "__tests__", "src/__tests__", "spec");

    /** Directories that never contain project source. */
    private static final Set<String> EXCLUDED = Set.of(
            "node_modules", "dist", "build", "out", "coverage", ".git", ".codewitness", ".next",
            ".nuxt", ".cache", "vendor", ".yarn", ".pnpm-store");

    private static final Pattern PACKAGE_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PACKAGE_MANAGER = Pattern.compile("\"packageManager\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MODULE_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern KNOWN_TEST_FRAMEWORK = Pattern.compile(
            "\"(jest|vitest|mocha|jasmine|ava|tap|uvu)\"\\s*:");

    private JavaScriptProjectDetector() {
    }

    /**
     * @return {@code true} when the directory contains JavaScript this adapter should verify.
     *
     * <p>This used to refuse the whole project as soon as {@code tsconfig.json} or any TypeScript source
     * appeared, which kept a TypeScript project from being claimed twice - but it also meant a repository
     * holding a TypeScript frontend next to plain-JavaScript tooling lost the JavaScript entirely, without
     * the report saying so. The two adapters do not actually overlap: this one owns {@code js}, {@code mjs}
     * and {@code cjs}, and the TypeScript adapter owns {@code ts}, {@code tsx}, {@code mts} and
     * {@code cts}. Disjoint file sets mean both can run over one tree without either reading a file the
     * other owns, so the split belongs at the file level, which the extensions already give, and not at the
     * project level.
     *
     * <p>What is still asked for is evidence that there is a JavaScript <em>project</em> here rather than a
     * stray script: either a Node test file, or a {@code package.json} to declare what the code is. A
     * TypeScript project that happens to ship a compiled-looking {@code .js} config may therefore now get a
     * second, small JavaScript pass. That is the cost of not hiding JavaScript, and it is the cheaper of
     * the two mistakes: a redundant pass is visible in the report and can be read past, whereas a language
     * the run never looked at cannot.
     */
    public static boolean looksLikeJavaScriptProject(Path root) {
        if (containsJavaScriptTest(root, 4)) {
            return true;
        }
        return Files.isRegularFile(root.resolve("package.json")) && containsJavaScriptSource(root, 4);
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.JAVASCRIPT)
                .sourceExtensions(SOURCE_EXTENSIONS)
                .attribute(ATTR_SOURCE_EXTENSIONS, String.join(",", new java.util.TreeSet<>(SOURCE_EXTENSIONS)));

        List<String> descriptors = new ArrayList<>();
        for (String descriptor : DESCRIPTORS) {
            Path file = root.resolve(descriptor);
            if (Files.isRegularFile(file)) {
                descriptors.add(descriptor);
                builder.addDetectedDescriptor(file);
            }
        }
        boolean manifest = Files.isRegularFile(root.resolve("package.json"));
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none")
                    .attribute(ATTR_DETECTION_WARNING,
                            "no package.json was found; the JavaScript sources can still be built and tested "
                                    + "with Node, but no dependency manifest can be read");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors));
        }
        builder.buildSystem(manifest ? BuildSystemId.NODE : BuildSystemId.NONE);

        String packageName = detectPackageName(project, root);
        builder.attribute(ATTR_PACKAGE_NAME, packageName);
        builder.attribute(ATTR_PACKAGE_MANAGER, detectPackageManager(root));
        String moduleType = detectModuleType(root);
        if (moduleType != null) {
            builder.attribute(ATTR_MODULE_TYPE, moduleType);
        }
        String knownFrameworks = detectTestFrameworks(root);
        if (knownFrameworks != null) {
            // The built-in runner is always available; the attribute records it beside what the project
            // declares so a report shows both the tool CodeWitness uses and the one the project expects.
            builder.attribute(ATTR_DETECTED_TEST_FRAMEWORKS, knownFrameworks + ",node:test");
        }

        List<Path> sourceDirs = detectSourceDirs(root);
        List<Path> testDirs = detectTestDirs(root);
        builder.addModule(new ModuleInfo(packageName, root, "module", sourceDirs, testDirs, null));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testDirs.isEmpty()));
        // Node's own runner is the only test framework this adapter can execute without installing
        // anything, so that is what a run uses; a detected third-party framework is reported instead.
        builder.addTestFramework("node:test");
        builder.attribute(ATTR_TEST_FRAMEWORK, "node:test");
        if (!testDirs.isEmpty() && knownFrameworks != null) {
            builder.attribute(ATTR_DETECTION_WARNING,
                    "the manifest declares " + knownFrameworks + "; CodeWitness runs the built-in Node test "
                            + "runner (node --test) and does not install or invoke a third-party runner");
        }
        return builder.build();
    }

    /** @return main source roots: {@code src} when it exists, otherwise the project root. */
    static List<Path> detectSourceDirs(Path root) {
        Path src = root.resolve("src");
        if (Files.isDirectory(src) && containsJavaScriptSource(src, 8)) {
            return List.of(src);
        }
        return List.of(root);
    }

    /** @return the test directories that hold JavaScript tests, in the conventional order. */
    static List<Path> detectTestDirs(Path root) {
        List<Path> result = new ArrayList<>();
        for (String candidate : TEST_DIRECTORIES) {
            Path directory = root.resolve(candidate);
            if (Files.isDirectory(directory) && containsTestSource(directory)) {
                result.add(directory);
            }
        }
        if (!result.isEmpty()) {
            return List.copyOf(result);
        }
        // No conventional directory: fall back to the directories that actually hold test files, which
        // is where a generated test has to live for its imports to resolve like the project's own.
        Set<Path> parents = new LinkedHashSet<>();
        for (Path file : javascriptFiles(root)) {
            if (isTestFileName(file.getFileName().toString()) && file.getParent() != null) {
                parents.add(file.getParent());
            }
        }
        return List.copyOf(parents);
    }

    /** @return the package name from {@code package.json}, or the directory name. */
    static String detectPackageName(ProjectRef project, Path root) {
        Path manifest = root.resolve("package.json");
        if (Files.isRegularFile(manifest)) {
            Matcher matcher = PACKAGE_NAME.matcher(readString(manifest));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return project.name();
    }

    /** @return the package manager the project pins or implies, for example {@code pnpm}. */
    static String detectPackageManager(Path root) {
        Path manifest = root.resolve("package.json");
        if (Files.isRegularFile(manifest)) {
            Matcher matcher = PACKAGE_MANAGER.matcher(readString(manifest));
            if (matcher.find()) {
                String pinned = matcher.group(1);
                int at = pinned.indexOf('@');
                return at > 0 ? pinned.substring(0, at) : pinned;
            }
        }
        if (Files.isRegularFile(root.resolve("pnpm-lock.yaml"))) {
            return "pnpm";
        }
        if (Files.isRegularFile(root.resolve("yarn.lock"))) {
            return "yarn";
        }
        if (Files.isRegularFile(root.resolve("bun.lockb"))) {
            return "bun";
        }
        if (Files.isRegularFile(root.resolve("package-lock.json"))) {
            return "npm";
        }
        return Files.isRegularFile(root.resolve("package.json")) ? "npm" : "unknown";
    }

    /** @return the declared module system ({@code module} or {@code commonjs}), or {@code null}. */
    static String detectModuleType(Path root) {
        Path manifest = root.resolve("package.json");
        if (Files.isRegularFile(manifest)) {
            Matcher matcher = MODULE_TYPE.matcher(readString(manifest));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** @return the known third-party test frameworks the manifest declares, or {@code null}. */
    static String detectTestFrameworks(Path root) {
        Path manifest = root.resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = KNOWN_TEST_FRAMEWORK.matcher(readString(manifest));
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        if (found.isEmpty()) {
            return null;
        }
        return String.join(",", found);
    }

    /** @return {@code true} when a shallow walk finds a JavaScript source file below {@code root}. */
    static boolean containsJavaScriptSource(Path root, int maxDepth) {
        return containsMatch(root, maxDepth, path -> isSourceFileName(path.getFileName().toString()));
    }

    /** @return {@code true} when a shallow walk finds a Node test file below {@code root}. */
    static boolean containsJavaScriptTest(Path root, int maxDepth) {
        return containsMatch(root, maxDepth, path -> isTestFileName(path.getFileName().toString()));
    }

    private static boolean containsMatch(Path root, int maxDepth, java.util.function.Predicate<Path> matches) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            return walk.anyMatch(path -> Files.isRegularFile(path) && matches.test(path) && !isExcluded(root, path));
        } catch (IOException _) {
            return false;
        }
    }

    /** @return {@code true} when the directory contains at least one test file. */
    static boolean containsTestSource(Path root) {
        return !testFiles(root).isEmpty();
    }

    /** @return the test files below a root, sorted, skipping build output and dependencies. */
    public static List<Path> testFiles(Path root) {
        return filesMatching(root, path -> isTestFileName(path.getFileName().toString()));
    }

    /** @return the JavaScript files below a root, sorted, skipping build output and dependencies. */
    public static List<Path> javascriptFiles(Path root) {
        return filesMatching(root, path -> isSourceFileName(path.getFileName().toString()));
    }

    private static List<Path> filesMatching(Path root, java.util.function.Predicate<Path> matches) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(matches)
                    .filter(path -> !isExcluded(root, path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /** @return {@code true} when the file name has one of the source extensions. */
    static boolean isSourceFileName(String fileName) {
        return SOURCE_EXTENSIONS.contains(extensionOf(fileName));
    }

    /** @return {@code true} when the file name carries Node's own test naming convention. */
    static boolean isTestFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String stem = dot < 0 ? lower : lower.substring(0, dot);
        String extension = extensionOf(fileName);
        return SOURCE_EXTENSIONS.contains(extension) && (stem.endsWith(".test") || stem.endsWith(".spec"));
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * @return {@code true} when a path element <em>below the walked root</em> is build output, a cache
     *     or a dependency directory.
     *
     * <p>The check is relative to the root rather than absolute on purpose. A run's isolated workspace
     * lives under the project's {@code .codewitness} directory, so an absolute check would reject every
     * file in the workspace and silently find no sources at all.
     */
    static boolean isExcluded(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        for (Path element : relative) {
            if (EXCLUDED.contains(element.toString().toLowerCase(Locale.ROOT))) {
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

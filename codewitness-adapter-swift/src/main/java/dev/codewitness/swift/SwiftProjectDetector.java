package dev.codewitness.swift;

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
 * Detects Swift packages and describes their layout.
 *
 * <p>Only files are read here; nothing is executed and no toolchain is needed. Detection is deliberately
 * conservative, because the adapter drives Swift Package Manager and nothing else: a tree is claimed
 * when it has a {@code Package.swift}, which is the one file every SwiftPM package must have. A tree
 * with {@code .swift} files but no manifest is left to {@code SwiftLanguageDetector}, which reports it
 * as present and unverifiable - honest, and the same choice the C and C++ adapter makes for a tree built
 * by a hand-written Makefile.
 *
 * <p>{@code Package.swift} is Swift source, not a data format, so it is read as text with the few line
 * patterns below rather than evaluated. Evaluating it would run project code before the run has decided
 * to, and a manifest that constructs its target list programmatically simply yields the conventional
 * layout facts instead of a guess: the detector falls back to the {@code Sources} and {@code Tests}
 * directories SwiftPM itself would use.
 */
public final class SwiftProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";
    public static final String ATTR_TOOLS_VERSION = "toolsVersion";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";

    /** Descriptor files, in the order a report should read them. */
    private static final List<String> DESCRIPTORS = List.of("Package.swift", "Package.resolved");

    /** File extensions, without the dot, this adapter treats as Swift source. */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of("swift");

    /** The same extensions as a single attribute value, in the order a report should read them. */
    private static final String SOURCE_EXTENSIONS_VALUE = "swift";

    /**
     * Directories never treated as source, relative to the walked root.
     *
     * <p>The check is relative to the root rather than absolute, exactly as the Rust and Ruby detectors'
     * is: a run's isolated workspace lives under the project's {@code .codewitness} directory, so an
     * absolute check would reject every file in the workspace and silently find no source at all.
     *
     * <p>{@code .build}, {@code .swiftpm} and {@code DerivedData} are where Swift tooling writes: a
     * checked-out dependency, a compiled product or an Xcode index is not project source, and analysing
     * one would attribute a tool's output or somebody else's code to the project.
     */
    private static final Set<String> EXCLUDED = Set.of(
            "node_modules", "build", "dist", ".git", ".codewitness", ".build", ".swiftpm", "DerivedData",
            "Pods", ".index-build");

    /** Package name from {@code Package(name: "Ledger", ...)}. */
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("(?m)Package\\s*\\(\\s*name\\s*:\\s*\"([^\"]+)\"");

    /** A declared target of either kind: {@code .target(name: "Ledger", ...)}. */
    private static final Pattern TARGET_NAME = Pattern.compile(
            "\\.(?:target|executableTarget|testTarget|systemLibrary|binaryTarget)\\s*\\([^)]*?name\\s*:\\s*\"([^\"]+)\"");

    /** A test target specifically, whose name is the module XCTest reports cases under. */
    private static final Pattern TEST_TARGET_NAME =
            Pattern.compile("\\.testTarget\\s*\\([^)]*?name\\s*:\\s*\"([^\"]+)\"");

    /**
     * A non-test target specifically, whose name is the module a generated test must import.
     *
     * <p>It is matched separately from the general target pattern because a test target's name must never
     * be mistaken for the library a generated class imports: a class written into the test target that
     * imported that same target would not compile.
     */
    private static final Pattern MAIN_TARGET_NAME = Pattern.compile(
            "\\.(?:target|executableTarget|systemLibrary)\\s*\\([^)]*?name\\s*:\\s*\"([^\"]+)\"");

    /** The manifest's tools version: {@code // swift-tools-version:5.9}. */
    private static final Pattern TOOLS_VERSION =
            Pattern.compile("(?m)^\\s*//\\s*swift-tools-version\\s*:\\s*([0-9][0-9.]*)");

    /** Source roots SwiftPM uses by convention, in the order a report should read them. */
    private static final List<String> SOURCE_ROOTS = List.of("Sources", "Source", "src");

    /** Test roots SwiftPM uses by convention. */
    private static final List<String> TEST_ROOTS = List.of("Tests", "Test", "test");

    private SwiftProjectDetector() {
    }

    /** @return {@code true} when the directory is a Swift package this adapter can drive. */
    public static boolean looksLikeSwiftProject(Path root) {
        return root != null && Files.isRegularFile(root.resolve("Package.swift"));
    }

    /** Inspects the package and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.SWIFT)
                .buildSystem(BuildSystemId.SWIFTPM)
                .sourceExtensions(SOURCE_EXTENSIONS)
                .attribute(ATTR_SOURCE_EXTENSIONS, SOURCE_EXTENSIONS_VALUE)
                .attribute(ATTR_PACKAGE_MANAGER, "swiftpm");

        List<Path> descriptors = descriptorFiles(root);
        for (Path descriptor : descriptors) {
            builder.addDetectedDescriptor(descriptor);
        }
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none")
                    .attribute(ATTR_DETECTION_WARNING,
                            "no Package.swift found; the tree can be analysed but SwiftPM cannot build it");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors.stream()
                    .map(descriptor -> descriptor.getFileName().toString())
                    .toList()));
        }

        String manifest = readText(root.resolve("Package.swift"));
        String toolsVersion = firstGroup(TOOLS_VERSION, manifest);
        if (toolsVersion != null) {
            builder.attribute(ATTR_TOOLS_VERSION, toolsVersion);
        }

        List<Path> sourceDirs = detectRoots(root, SOURCE_ROOTS);
        List<Path> testDirs = detectRoots(root, TEST_ROOTS);
        String testModule = testModule(root, manifest, testDirs);
        if (testModule != null) {
            builder.attribute(SwiftToolchain.ATTR_TEST_MODULE, testModule);
        }
        String hostModule = hostModule(manifest, sourceDirs);
        if (hostModule != null) {
            builder.attribute(SwiftToolchain.ATTR_HOST_MODULE, hostModule);
        }
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testDirs.isEmpty()));

        String packageName = firstGroup(PACKAGE_NAME, manifest);
        String name = packageName == null || packageName.isBlank() ? project.name() : packageName.strip();
        if (sourceDirs.isEmpty() && testDirs.isEmpty()) {
            // A package whose targets are declared with explicit `path:` arguments has neither
            // conventional root. The package directory itself is the source root it compiles from, and
            // saying so is better than reporting no sources at all.
            sourceDirs = List.of(root);
        }
        builder.addModule(new ModuleInfo(name, root, "swiftpm", sourceDirs, testDirs, null));

        String framework = testDirs.isEmpty() && !mentions(manifest, "xctest") ? "none" : "xctest";
        if (!"none".equals(framework)) {
            builder.addTestFramework(framework);
        }
        builder.attribute(ATTR_TEST_FRAMEWORK, framework);
        return builder.build();
    }

    /**
     * @return the module XCTest reports a test class under: the first declared test target's name, or
     *     the first conventional test directory's name when the manifest does not name one.
     */
    private static String testModule(Path root, String manifest, List<Path> testDirs) {
        String declared = firstGroup(TEST_TARGET_NAME, manifest);
        if (declared != null && !declared.isBlank()) {
            return declared.strip();
        }
        for (Path directory : testDirs) {
            if (directory.getFileName() != null) {
                return directory.getFileName().toString();
            }
        }
        return null;
    }

    /**
     * @return the module a generated test must {@code @testable import}: the first declared non-test
     *     target's name, or the first conventional source directory's name when the manifest does not
     *     name one, or {@code null} when neither is known.
     *
     * <p>This is deliberately a different fact from {@link #testModule}: XCTest reports a case under the
     * test target's module, while the generated class's own source imports the library target it
     * exercises. A class that imported the test target it was written into would not compile, so the two
     * names are read separately and neither is used for the other's purpose.
     */
    private static String hostModule(String manifest, List<Path> sourceDirs) {
        String declared = firstGroup(MAIN_TARGET_NAME, manifest);
        if (declared != null && !declared.isBlank()) {
            return declared.strip();
        }
        for (Path directory : sourceDirs) {
            if (directory.getFileName() != null) {
                return directory.getFileName().toString();
            }
        }
        return null;
    }

    /**
     * @param root the package root
     * @param candidates the conventional parent directories to look under
     * @return the Swift source directories below the first candidate that holds any, or an empty list
     */
    private static List<Path> detectRoots(Path root, List<String> candidates) {
        for (String candidate : candidates) {
            Path parent = root.resolve(candidate);
            if (!Files.isDirectory(parent)) {
                continue;
            }
            List<Path> directories = new ArrayList<>();
            try (Stream<Path> list = Files.list(parent)) {
                list.filter(Files::isDirectory)
                        .filter(directory -> containsSwiftSource(directory, 6))
                        .sorted()
                        .forEach(directories::add);
            } catch (IOException _) {
                // an unreadable tree yields no roots, which the caller reports rather than guesses about
            }
            if (!directories.isEmpty()) {
                return List.copyOf(directories);
            }
        }
        return List.of();
    }

    /** @return {@code true} when a directory holds a Swift file no deeper than {@code depth}. */
    private static boolean containsSwiftSource(Path directory, int depth) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(directory, depth)) {
            return walk.filter(Files::isRegularFile).anyMatch(SwiftProjectDetector::isSwiftSourceName);
        } catch (IOException _) {
            return false;
        }
    }

    /** @return the descriptor files of the package that exist, in preference order. */
    static List<Path> descriptorFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null) {
            return files;
        }
        for (String name : DESCRIPTORS) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        return files;
    }

    /** @return {@code true} when the file name ends in {@code .swift}. */
    public static boolean isSwiftSourceName(Path path) {
        return path != null && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".swift");
    }

    /**
     * @return every Swift file below a root, sorted, skipping build output, dependency and VCS
     *     directories.
     *
     * <p>Dependency code is somebody else's source: analysing it would attribute another project's
     * decisions to this one, and mutating it would measure somebody else's tests.
     */
    public static List<Path> swiftFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(SwiftProjectDetector::isSwiftSourceName)
                    // Package.swift is Swift source but it is the build descriptor, not the package's
                    // code: mutating it or analysing it would report on the manifest rather than on the
                    // program it describes.
                    .filter(path -> !path.getFileName().toString().equals("Package.swift"))
                    .filter(path -> !isExcludedSource(root, path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /** @return {@code true} when an element below {@code root} is build output, a dependency or VCS data. */
    public static boolean isExcludedSource(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        for (Path element : relative) {
            if (EXCLUDED.contains(element.toString())) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when the text mentions a token; an unreadable manifest says nothing. */
    private static boolean mentions(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String readText(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return null;
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}

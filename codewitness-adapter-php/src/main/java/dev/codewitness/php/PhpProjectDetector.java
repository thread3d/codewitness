package dev.codewitness.php;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Detects PHP projects and describes their layout.
 *
 * <p>Only files are read here; nothing is executed. Detection is deliberately conservative: a
 * {@code composer.json} that cannot be parsed is reported through
 * {@link ProjectInfo#attributes()} rather than replaced by an invented module name or php version, and
 * a tree without a descriptor is still claimed - it can be syntax-checked, tested and analysed, it
 * simply has nothing to install.
 *
 * <p>The one thing this detector cannot know is whether the project's tests can run: PHPUnit is a
 * Composer dependency that exists only after the build has created {@code vendor/}. The test framework
 * recorded here is what the project <em>declares</em>, and the test phase resolves the runner itself.
 */
public final class PhpProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";
    public static final String ATTR_PHP_VERSION = "phpVersion";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";

    /**
     * Descriptor files that identify a PHP project, in the order they are preferred.
     *
     * <p>{@code composer.json} is first because it is the only one that also names the package manager,
     * the module and the required interpreter; the others merely say which test framework the project
     * configures.
     */
    private static final List<String> DESCRIPTORS =
            List.of("composer.json", "phpunit.xml", "phpunit.xml.dist", "Pest.php", "artisan");

    /** File extensions, without the dot, this adapter treats as PHP source. */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of("php", "phtml");

    /**
     * Directories never treated as source, relative to the walked root.
     *
     * <p>The check is relative to the root rather than absolute, exactly as the Python detector's is: a
     * run's isolated workspace lives under the project's {@code .codewitness} directory, so an absolute
     * check would reject every file in the workspace and silently find no source at all.
     */
    private static final Set<String> EXCLUDED = Set.of(
            "vendor", "node_modules", "build", "dist", ".git", ".codewitness",
            ".phpunit.cache", ".phpunit.result.cache", "cache", ".cache");

    /** Candidates for the directory the project's own sources live in, in preference order. */
    private static final List<String> SOURCE_ROOTS = List.of("src", "app", "lib");

    /** Candidates for a test directory, in preference order. */
    private static final List<String> TEST_ROOTS = List.of("tests", "test", "spec");

    private static final Pattern VERSION_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PhpProjectDetector() {
    }

    /** @return {@code true} when the directory looks like a PHP project this adapter can handle. */
    public static boolean looksLikePhpProject(Path root) {
        for (String descriptor : DESCRIPTORS) {
            if (Files.isRegularFile(root.resolve(descriptor))) {
                return true;
            }
        }
        return containsPhpSource(root, 3);
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.PHP)
                .sourceExtensions(SOURCE_EXTENSIONS)
                .attribute(ATTR_SOURCE_EXTENSIONS, "php,phtml");

        List<String> descriptors = new ArrayList<>();
        for (String descriptor : DESCRIPTORS) {
            Path file = root.resolve(descriptor);
            if (Files.isRegularFile(file)) {
                descriptors.add(descriptor);
                builder.addDetectedDescriptor(file);
            }
        }
        boolean hasComposer = Files.isRegularFile(root.resolve("composer.json"));
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none")
                    .attribute(ATTR_DETECTION_WARNING,
                            "no PHP build descriptor (composer.json, phpunit.xml, Pest.php, artisan) found; "
                                    + "the project can be syntax-checked, tested and analysed but its "
                                    + "dependencies cannot be installed");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors));
        }
        builder.buildSystem(hasComposer ? BuildSystemId.COMPOSER : BuildSystemId.NONE);
        // Composer is the only package manager PHP has in practice, so a tree without composer.json has
        // no package manager at all rather than a default one invented for it.
        builder.attribute(ATTR_PACKAGE_MANAGER, hasComposer ? "composer" : "unknown");

        ComposerManifest manifest = readComposerManifest(root.resolve("composer.json"));
        if (manifest.warning() != null) {
            builder.attribute(ATTR_DETECTION_WARNING, manifest.warning());
        }
        if (manifest.phpVersion() != null) {
            builder.attribute(ATTR_PHP_VERSION, manifest.phpVersion());
        }

        List<Path> sourceDirs = detectSourceDirs(root);
        List<Path> testDirs = detectTestDirs(root);
        String name = manifest.name() == null ? project.name() : moduleName(manifest.name());
        builder.addModule(new ModuleInfo(name, root, "php", sourceDirs, testDirs, null));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testDirs.isEmpty()));

        String framework = detectTestFramework(root);
        if (!"none".equals(framework)) {
            builder.addTestFramework(framework);
        }
        builder.attribute(ATTR_TEST_FRAMEWORK, framework);
        return builder.build();
    }

    /**
     * @return the Composer package name without its vendor prefix, because that is what a human and a
     *     report mean by "the project"; a name that carries no vendor prefix is used as it is.
     */
    static String moduleName(String composerName) {
        int slash = composerName.indexOf('/');
        return slash < 0 ? composerName : composerName.substring(slash + 1);
    }

    /** @return {@code src}, {@code app}, {@code lib} when it holds PHP sources, otherwise the root. */
    private static List<Path> detectSourceDirs(Path root) {
        for (String candidate : SOURCE_ROOTS) {
            Path directory = root.resolve(candidate);
            if (Files.isDirectory(directory) && containsPhpSource(directory, 8)) {
                return List.of(directory);
            }
        }
        // A flat layout keeps its classes next to composer.json; using the root keeps includes working
        // exactly as they do when a developer runs the code from the project directory.
        return List.of(root);
    }

    /**
     * @return the test roots: {@code tests}, {@code test} or {@code spec} when they hold PHP, or the
     *     project root when it is the only place holding a {@code *Test.php} or {@code Spec.php} file.
     */
    private static List<Path> detectTestDirs(Path root) {
        List<Path> result = new ArrayList<>();
        for (String candidate : TEST_ROOTS) {
            Path directory = root.resolve(candidate);
            if (Files.isDirectory(directory) && containsPhpSource(directory, 8)) {
                result.add(directory);
            }
        }
        if (result.isEmpty() && holdsTestClassAtRoot(root)) {
            result.add(root);
        }
        return List.copyOf(result);
    }

    /** @return {@code true} when the root itself holds a file PHPUnit or Pest would run by convention. */
    private static boolean holdsTestClassAtRoot(Path root) {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.equals("Spec.php") || name.endsWith("Test.php"));
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * @return {@code pest} or {@code phpunit} when the project configures one, otherwise {@code none}.
     *
     * <p>There is no fallback framework: PHP ships no test runner in its standard library, so a project
     * with neither Pest nor PHPUnit configuration simply has no tests this adapter can honestly claim
     * to be able to run.
     */
    private static String detectTestFramework(Path root) {
        if (Files.isRegularFile(root.resolve("Pest.php")) || composerMentions(root, "pest")) {
            return "pest";
        }
        if (Files.isRegularFile(root.resolve("phpunit.xml"))
                || Files.isRegularFile(root.resolve("phpunit.xml.dist"))
                || composerMentions(root, "phpunit/phpunit")) {
            return "phpunit";
        }
        return "none";
    }

    private static boolean composerMentions(Path root, String needle) {
        Path manifest = root.resolve("composer.json");
        return Files.isRegularFile(manifest)
                && readString(manifest).toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * @return the PHP files below a root, sorted, skipping the vendor tree and the caches.
     *
     * <p>Dependency code is somebody else's source: analysing it would attribute another project's
     * decisions to this one, and checking its syntax would report a vendor's incompatibility as this
     * project's build failure.
     */
    public static List<Path> phpFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(PhpProjectDetector::isPhpSourceName)
                    .filter(path -> !isExcludedSource(root, path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /** @return {@code true} when an element below {@code root} is the vendor tree, a cache or VCS data. */
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

    /** @return {@code true} when a shallow walk finds a PHP source file below {@code root}. */
    private static boolean containsPhpSource(Path root, int maxDepth) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            return walk.anyMatch(path -> Files.isRegularFile(path)
                    && isPhpSourceName(path)
                    && !isExcludedSource(root, path));
        } catch (IOException _) {
            return false;
        }
    }

    private static boolean isPhpSourceName(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".php") || name.endsWith(".phtml");
    }

    /**
     * Reads the two facts this class needs out of {@code composer.json}: the package name and the
     * interpreted version the {@code php} requirement asks for.
     *
     * <p>A parse failure is reported as a warning and nothing else: the detector then describes the
     * project from its tree, which is a weaker but true statement, instead of failing the run or
     * inventing a name.
     */
    private static ComposerManifest readComposerManifest(Path file) {
        if (!Files.isRegularFile(file)) {
            return new ComposerManifest(null, null, null);
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String name = text(root.path("name"));
            String requirement = text(root.path("require").path("php"));
            String version = requirement == null ? null : firstVersionNumber(requirement);
            return new ComposerManifest(name, version, null);
        } catch (Exception e) {
            return new ComposerManifest(null, null, "composer.json could not be parsed ("
                    + e.getClass().getSimpleName() + "); the project was described from its file tree instead");
        }
    }

    private static String firstVersionNumber(String requirement) {
        Matcher matcher = VERSION_NUMBER.matcher(requirement);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    static String readString(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }

    /**
     * What {@code composer.json} said, or nothing when it said nothing usable.
     *
     * @param name       the {@code vendor/package} name, or {@code null}
     * @param phpVersion the version the {@code php} requirement names, or {@code null}
     * @param warning    why the manifest could not be read, or {@code null}
     */
    private record ComposerManifest(String name, String phpVersion, String warning) {
    }
}

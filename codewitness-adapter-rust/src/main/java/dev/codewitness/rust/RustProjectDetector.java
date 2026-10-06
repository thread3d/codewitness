package dev.codewitness.rust;

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
 * Detects Rust projects and describes their Cargo layout.
 *
 * <p>Only files are read here; nothing is executed. Cargo is a single, strongly conventional build
 * system, so detection is mostly a matter of reading {@code Cargo.toml}: the crate name, the edition
 * and the conventional {@code src/} and {@code tests/} directories. A directory with Rust sources
 * but no manifest is still detected, because it can be syntax-checked and analysed even though it
 * cannot be built or tested.
 *
 * <p>The crate name matters beyond reporting: generated integration tests call the crate by name, so
 * a hyphenated package name is normalised to the underscored identifier Cargo exposes to Rust code.
 */
public final class RustProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_CRATE_NAME = "crateName";
    public static final String ATTR_EDITION = "edition";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";
    public static final String ATTR_RUST_VERSION = "rustVersion";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";

    /** Descriptor files that identify a Rust project, in the order they are preferred. */
    private static final List<String> DESCRIPTORS = List.of(
            "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "rust-toolchain");

    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "(?ms)^\\s*\\[package\\][^\\[]*?^\\s*name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern EDITION = Pattern.compile(
            "(?m)^\\s*edition\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern RUST_CHANNEL = Pattern.compile(
            "(?m)^\\s*channel\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern VERSION_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+){1,2})");

    private RustProjectDetector() {
    }

    /** @return {@code true} when the directory looks like a Rust project this adapter can handle. */
    public static boolean looksLikeRustProject(Path root) {
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            return true;
        }
        return containsRustSource(root, 3);
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.RUST)
                .sourceExtensions(Set.of("rs"))
                .attribute(ATTR_SOURCE_EXTENSIONS, "rs");

        List<String> descriptors = new ArrayList<>();
        for (String descriptor : DESCRIPTORS) {
            Path file = root.resolve(descriptor);
            if (Files.isRegularFile(file)) {
                descriptors.add(descriptor);
                builder.addDetectedDescriptor(file);
            }
        }
        boolean manifest = Files.isRegularFile(root.resolve("Cargo.toml"));
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none")
                    .attribute(ATTR_DETECTION_WARNING,
                            "no Cargo.toml was found; the Rust sources can be analysed but the project "
                                    + "cannot be built, tested or mutated");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors));
        }
        builder.buildSystem(manifest ? BuildSystemId.CARGO : BuildSystemId.NONE);
        builder.attribute(ATTR_PACKAGE_MANAGER, manifest ? "cargo" : "unknown");

        String crateName = detectCrateName(project, root);
        builder.attribute(ATTR_CRATE_NAME, crateName);
        String edition = detectEdition(root);
        if (edition != null) {
            builder.attribute(ATTR_EDITION, edition);
        }
        String rustVersion = detectRustVersion(root);
        if (rustVersion != null) {
            // The runtime version is recorded so a report can be reproduced against the same toolchain.
            builder.attribute(ATTR_RUST_VERSION, rustVersion).attribute("runtimeVersion", "Rust " + rustVersion);
        }

        List<Path> sourceDirs = detectSourceDirs(root);
        List<Path> testDirs = detectTestDirs(root);
        builder.addModule(new ModuleInfo(crateName, root, "crate", sourceDirs, testDirs, null));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testDirs.isEmpty()));
        builder.addTestFramework("libtest");
        builder.attribute(ATTR_TEST_FRAMEWORK, "libtest");
        return builder.build();
    }

    /** @return main source roots: {@code src} when it exists, otherwise the project root. */
    static List<Path> detectSourceDirs(Path root) {
        Path src = root.resolve("src");
        if (Files.isDirectory(src) && containsRustSource(src, 6)) {
            return List.of(src);
        }
        return List.of(root);
    }

    /** @return the {@code tests} directory when it contains Rust integration tests. */
    static List<Path> detectTestDirs(Path root) {
        List<Path> result = new ArrayList<>();
        Path tests = root.resolve("tests");
        if (Files.isDirectory(tests) && containsRustSource(tests, 8)) {
            result.add(tests);
        }
        return List.copyOf(result);
    }

    /** @return the crate identifier used from Rust code, with hyphens replaced by underscores. */
    static String detectCrateName(ProjectRef project, Path root) {
        Path manifest = root.resolve("Cargo.toml");
        if (Files.isRegularFile(manifest)) {
            Matcher matcher = PACKAGE_NAME.matcher(readString(manifest));
            if (matcher.find()) {
                return matcher.group(1).replace('-', '_');
            }
        }
        return project.name().replace('-', '_');
    }

    /** @return the declared Rust edition, for example {@code 2021}, or {@code null}. */
    static String detectEdition(Path root) {
        Path manifest = root.resolve("Cargo.toml");
        if (Files.isRegularFile(manifest)) {
            Matcher matcher = EDITION.matcher(readString(manifest));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** @return the toolchain channel from {@code rust-toolchain.toml}, or {@code null}. */
    static String detectRustVersion(Path root) {
        for (String file : List.of("rust-toolchain.toml", "rust-toolchain")) {
            Path path = root.resolve(file);
            if (Files.isRegularFile(path)) {
                Matcher channel = RUST_CHANNEL.matcher(readString(path));
                String value = channel.find() ? channel.group(1) : readString(path).trim();
                Matcher version = VERSION_NUMBER.matcher(value);
                if (version.find()) {
                    return version.group(1);
                }
            }
        }
        return null;
    }

    /** @return {@code true} when a shallow walk finds a Rust source file below {@code root}. */
    static boolean containsRustSource(Path root, int maxDepth) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            return walk.anyMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".rs"));
        } catch (IOException _) {
            return false;
        }
    }

    /** @return the Rust files below a root, sorted, skipping build output and VCS metadata. */
    public static List<Path> rustFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".rs"))
                    .filter(path -> !isExcluded(path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /** @return {@code true} when any path element is build output, a cache or a VCS directory. */
    static boolean isExcluded(Path path) {
        Set<String> excluded = Set.of("target", ".git", ".codewitness", "node_modules", "vendor", ".cargo");
        for (Path element : path) {
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

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
import java.util.Comparator;
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

    /**
     * The manifest the crate is built from, relative to the project root with forward slashes.
     *
     * <p>Every later phase resolves this against the run's own project root, so the path stays valid
     * whether the run works on the user's tree or on its isolated copy of it.
     */
    public static final String ATTR_MANIFEST = "manifestPath";

    /** Every manifest found at or below the project root, relative and comma separated, shallowest first. */
    public static final String ATTR_MANIFESTS = "manifests";

    /** Descriptor files that identify a Rust project, in the order they are preferred. */
    private static final List<String> DESCRIPTORS = List.of(
            "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "rust-toolchain");

    private static final String MANIFEST = "Cargo.toml";

    /**
     * How far below the project root a crate manifest is looked for.
     *
     * <p>Cargo resolves no manifest above its own, so a checkout whose crate sits in a subdirectory is
     * invisible to every Cargo command until one is named explicitly. The bound keeps a manifest inside a
     * generated or vendored tree from being mistaken for the project's own.
     */
    static final int MAX_MANIFEST_DEPTH = 6;

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
        if (manifest(root) != null) {
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

        // The crate's manifest need not sit at the project root. Pointing the tool at a checkout whose
        // crate lives in a subdirectory is ordinary, and Cargo looks for a manifest in its working
        // directory only, so the manifest is found once here and named to every later phase.
        List<Path> manifests = manifestFiles(root);
        Path manifest = manifests.isEmpty() ? null : manifests.getFirst();
        Path crateRoot = manifest == null ? root : manifest.getParent();
        if (manifest != null) {
            builder.buildRoot(crateRoot).attribute(ATTR_MANIFEST, relativePath(root, manifest));
            if (manifests.size() > 1) {
                List<String> paths = new ArrayList<>();
                for (Path found : manifests) {
                    paths.add(relativePath(root, found));
                }
                builder.attribute(ATTR_MANIFESTS, String.join(",", paths))
                        .attribute(ATTR_DETECTION_WARNING, "the tree holds " + manifests.size()
                                + " Cargo manifests and none is at the project root; the shallowest, "
                                + relativePath(root, manifest) + ", is the crate that is built, tested and "
                                + "mutated, and the other crates are not part of this run");
            }
        }

        List<String> descriptors = new ArrayList<>();
        for (String descriptor : DESCRIPTORS) {
            Path file = descriptorFile(descriptor, crateRoot, root);
            if (file != null) {
                descriptors.add(relativePath(root, file));
                builder.addDetectedDescriptor(file);
            }
        }
        boolean manifestFound = manifest != null;
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors));
        }
        if (!manifestFound) {
            builder.attribute(ATTR_DETECTION_WARNING,
                    "no Cargo.toml was found in the project directory or in the " + MAX_MANIFEST_DEPTH
                            + " directory levels below it; the Rust sources can be analysed but the project "
                            + "cannot be built, tested or mutated");
        }
        builder.buildSystem(manifestFound ? BuildSystemId.CARGO : BuildSystemId.NONE);
        builder.attribute(ATTR_PACKAGE_MANAGER, manifestFound ? "cargo" : "unknown");

        String crateName = detectCrateName(project, crateRoot);
        builder.attribute(ATTR_CRATE_NAME, crateName);
        String edition = detectEdition(crateRoot);
        if (edition != null) {
            builder.attribute(ATTR_EDITION, edition);
        }
        String rustVersion = detectRustVersion(crateRoot);
        if (rustVersion == null && !crateRoot.equals(root)) {
            // A toolchain file at the project root applies to a crate below it just as well.
            rustVersion = detectRustVersion(root);
        }
        if (rustVersion != null) {
            // The runtime version is recorded so a report can be reproduced against the same toolchain.
            builder.attribute(ATTR_RUST_VERSION, rustVersion).attribute("runtimeVersion", "Rust " + rustVersion);
        }

        List<Path> sourceDirs = detectSourceDirs(crateRoot);
        List<Path> testDirs = detectTestDirs(crateRoot);
        builder.addModule(new ModuleInfo(crateName, crateRoot, "crate", sourceDirs, testDirs, null));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testDirs.isEmpty()));
        builder.addTestFramework("libtest");
        builder.attribute(ATTR_TEST_FRAMEWORK, "libtest");
        return builder.build();
    }

    /**
     * @return the manifest the crate is built from — the project's own when it has one, otherwise the
     *     shallowest below it — or {@code null} when the tree holds no Cargo manifest at all
     */
    static Path manifest(Path root) {
        Path atRoot = root.resolve(MANIFEST);
        if (Files.isRegularFile(atRoot)) {
            return atRoot;
        }
        List<Path> nested = manifestFiles(root);
        return nested.isEmpty() ? null : nested.getFirst();
    }

    /**
     * @return every {@code Cargo.toml} at or below {@code root}, shallowest first and then by path, so
     *     that a crate is found before its own subdirectories and the choice between siblings is
     *     deterministic rather than dependent on the file system's iteration order
     */
    public static List<Path> manifestFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root, MAX_MANIFEST_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(MANIFEST))
                    .filter(path -> !isExcluded(root, path) && !isSampleTree(root, path))
                    .sorted(Comparator
                            .comparingInt((Path path) -> path.getNameCount())
                            .thenComparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no manifest, which the caller reports rather than guesses about
        }
        return files;
    }

    /**
     * @return {@code true} when the path lies in a sample-project directory
     *
     * <p>A repository's fixtures are inputs to its tests, not code the run set out to verify — the same
     * reason the language detectors skip these directories. Claiming one would make an unrelated
     * repository report a Rust project and build a sample crate while verifying itself.
     */
    private static boolean isSampleTree(Path root, Path path) {
        Set<String> sample = Set.of("fixtures", "testdata");
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            return false;
        }
        for (Path element : relative) {
            if (sample.contains(element.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** @return the project-root-relative form of {@code path}, with forward slashes. */
    public static String relativePath(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            return path.toString().replace('\\', '/');
        }
        return relative.toString().replace('\\', '/');
    }

    /** @return the descriptor beside the crate's manifest, else at the project root, else {@code null}. */
    private static Path descriptorFile(String name, Path crateRoot, Path root) {
        Path besideCrate = crateRoot.resolve(name);
        if (Files.isRegularFile(besideCrate)) {
            return besideCrate;
        }
        Path atRoot = root.resolve(name);
        return Files.isRegularFile(atRoot) ? atRoot : null;
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
    static String detectCrateName(ProjectRef project, Path crateRoot) {
        Path manifest = manifest(crateRoot);
        if (manifest != null) {
            Matcher matcher = PACKAGE_NAME.matcher(readString(manifest));
            if (matcher.find()) {
                return matcher.group(1).replace('-', '_');
            }
            // Cargo names a package whose manifest declares no name after the manifest's own directory.
            Path directory = crateRoot.toAbsolutePath().normalize().getFileName();
            if (directory != null) {
                return directory.toString().replace('-', '_');
            }
        }
        return project.name().replace('-', '_');
    }

    /** @return the declared Rust edition, for example {@code 2021}, or {@code null}. */
    static String detectEdition(Path crateRoot) {
        Path manifest = manifest(crateRoot);
        if (manifest != null) {
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
                    .filter(path -> !isExcluded(root, path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /**
     * @return {@code true} when a path element <em>below the walked root</em> is build output, a cache
     *     or a VCS directory.
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
        Set<String> excluded = Set.of("target", ".git", ".codewitness", "node_modules", "vendor", ".cargo");
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

package dev.codewitness.core.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores the artefacts a verification run produces: raw tool output, generated test sources,
 * mutation and coverage reports.
 *
 * <p>Artefacts live under {@code <runDirectory>/artifacts} and are referenced by evidence records.
 * Generated sources are kept separately from metadata so that metadata storage (SQLite) stays small
 * and the sources remain directly compilable.
 */
public final class ArtifactStore {

    private final Path runDirectory;

    public ArtifactStore(Path runDirectory) {
        this.runDirectory = runDirectory.toAbsolutePath().normalize();
        createDirectories(this.runDirectory);
    }

    public Path runDirectory() {
        return runDirectory;
    }

    public Path artifactsDirectory() {
        return ensure(runDirectory.resolve("artifacts"));
    }

    public Path logsDirectory() {
        return ensure(runDirectory.resolve("logs"));
    }

    public Path generatedTestsDirectory() {
        return ensure(runDirectory.resolve("generated-tests"));
    }

    /**
     * Resolves an artefact path, refusing anything that would escape the artefact directory.
     *
     * <p>The artefact store is written to on behalf of tools that process untrusted project input, so
     * every entry point enforces the same boundary rather than only the one that happens to be tested.
     *
     * @param relativePath path relative to the artefact directory
     * @return the resolved absolute path, guaranteed to be inside the artefact directory
     */
    private Path resolveInsideArtifacts(String relativePath) {
        Path root = artifactsDirectory();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("artifact path escapes the artifact directory: " + relativePath);
        }
        return target;
    }

    /** Writes a UTF-8 text artefact and returns its path. */
    public Path write(String relativePath, String content) {
        return writeBytes(relativePath, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a binary artefact and returns its path. */
    public Path writeBytes(String relativePath, byte[] content) {
        Path target = resolveInsideArtifacts(relativePath);
        createDirectories(target.getParent());
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write artifact " + target, e);
        }
        return target;
    }

    /** Copies an existing file (for example a raw surefire report) into the artefact store. */
    public Path copyIn(Path source, String relativePath) {
        Path target = resolveInsideArtifacts(relativePath);
        createDirectories(target.getParent());
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy " + source + " to " + target, e);
        }
        return target;
    }

    /** Copies an entire directory tree into the artefact store, skipping anything unreadable. */
    public Path copyTree(Path source, String relativePath) {
        Path targetRoot = resolveInsideArtifacts(relativePath);
        if (!Files.isDirectory(source)) {
            return targetRoot;
        }
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(path -> {
                Path target = targetRoot.resolve(source.relativize(path).toString());
                try {
                    if (Files.isDirectory(path)) {
                        createDirectories(target);
                    } else if (Files.isRegularFile(path)) {
                        createDirectories(target.getParent());
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ignored) {
                    // artifacts are best-effort: a missing raw report must not fail a verification run
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy tree " + source, e);
        }
        return targetRoot;
    }

    /** @return every artefact currently stored, sorted by path. */
    public List<Path> listArtifacts() {
        Path root = artifactsDirectory();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(files::add);
            files.sort(java.util.Comparator.comparing(Path::toString));
            return List.copyOf(files);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path ensure(Path directory) {
        createDirectories(directory);
        return directory;
    }

    private static void createDirectories(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create directory " + directory, e);
        }
    }
}

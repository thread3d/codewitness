package dev.codewitness.core.io;

import dev.codewitness.core.model.ProjectRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

/**
 * Creates the isolated workspace a run operates on.
 *
 * <p>By default the project is copied into the run directory before anything executes. Two things
 * follow from that: generated tests and build output never touch the user's working tree, and the
 * build can be repeated from a pristine copy. {@link Mode#IN_PLACE} is available for very large
 * projects and for reproduce-style runs; when it is used, the report says so.
 */
public final class WorkspaceManager {

    /** Directories that are never copied: build output, VCS metadata and previous runs. */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "target", "build", "out", ".git", ".gradle", ".idea", ".codewitness", "node_modules", ".mvn-cache",
            // The .NET SDK writes compiled assemblies to bin and every intermediate file - including
            // project.assets.json, whose package paths are absolute and belong to the machine that ran
            // the restore - to obj. Copying either would carry a stale restore into the isolated
            // workspace, so both are excluded and the build restores for itself.
            "bin", "obj",
            // Python build output, caches and virtual environments. A virtual environment is a copy of
            // an interpreter with absolute paths baked in: copying it into the workspace would both be
            // enormous and produce an interpreter that cannot find itself.
            "__pycache__", ".venv", "venv", ".tox", ".nox", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            ".hypothesis", ".eggs", "dist", ".pytype");

    private static boolean isExcludedDirectory(String name) {
        return EXCLUDED_DIRECTORIES.contains(name) || name.endsWith(".egg-info");
    }

    private WorkspaceManager() {
    }

    public enum Mode {
        /** Copy the project into the run workspace, excluding build output and VCS metadata. */
        COPY,
        /** Operate directly on the project directory. Faster, but writes generated tests into the project. */
        IN_PLACE
    }

    /**
     * Prepares a workspace.
     *
     * @param project project under verification
     * @param runDirectory directory that belongs to the run
     * @param mode copy or in-place
     * @return the prepared workspace
     */
    public static Workspace prepare(ProjectRef project, Path runDirectory, Mode mode) {
        if (mode == Mode.IN_PLACE) {
            return new Workspace(project.root(), Mode.IN_PLACE,
                    "operating in place on " + project.root() + "; generated tests are written into the project "
                            + "test sources and removed afterwards");
        }
        Path root = runDirectory.resolve("workspace").toAbsolutePath().normalize();
        copyTree(project.root(), root);
        return new Workspace(root, Mode.COPY,
                "isolated copy of " + project.root() + " at " + root
                        + " (build output, VCS metadata and previous run data excluded)");
    }

    /** Recursively copies {@code source} to {@code target}, skipping excluded directories. */
    public static void copyTree(Path source, Path target) {
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("not a directory: " + source);
        }
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(source) && !isSourceDirectory(source, dir)
                            && isExcludedDirectory(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path destination = target.resolve(source.relativize(file).toString());
                    Files.createDirectories(destination.getParent());
                    try {
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    } catch (IOException e) {
                        // Fall back to a plain copy: some filesystems reject attribute copying.
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    makeExecutableIfNeeded(destination);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create workspace copy at " + target, e);
        }
    }

    /**
     * @return {@code true} when {@code dir} sits under a {@code src} tree of the copied project, and
     *     is therefore source rather than build output
     *
     * <p>The exclusion list matches directory <em>names</em>, and legitimate source packages are
     * allowed to be called {@code build}, {@code out} or {@code target}. Excluding those would
     * silently delete real packages from the isolated copy - which is exactly what happened to
     * CodeWitness's own {@code dev.codewitness.java.build} package - and the copy would then fail to
     * compile. Build output never lives under {@code src}, so that prefix is what distinguishes the
     * two. The path is checked relative to the copied project, so a project that merely lives in a
     * directory called {@code src} is unaffected.
     */
    private static boolean isSourceDirectory(Path source, Path dir) {
        Path relative = source.relativize(dir);
        for (Path element : relative) {
            if (element.toString().equals("src")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Restores the executable bit on build wrappers. A copied {@code mvnw} that is not executable is
     * a very confusing failure, so it is handled explicitly rather than left to chance.
     */
    private static void makeExecutableIfNeeded(Path file) {
        String name = file.getFileName().toString();
        boolean wrapper = name.equals("mvnw") || name.equals("gradlew")
                || name.equals("mvnw.cmd") || name.endsWith(".sh");
        if (!wrapper) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException _) {
            file.toFile().setExecutable(true);
        }
    }

    /** @return the {@code src} directories that a copy would produce, used by tests. */
    public static List<String> excludedDirectories() {
        return List.copyOf(EXCLUDED_DIRECTORIES);
    }

    /**
     * A prepared workspace.
     *
     * @param root project root to run everything against
     * @param mode how the workspace was prepared
     * @param description human readable description included in reports
     */
    public record Workspace(Path root, Mode mode, String description) {

        public Workspace {
            root = root.toAbsolutePath().normalize();
        }

        public boolean isIsolated() {
            return mode == Mode.COPY;
        }
    }
}

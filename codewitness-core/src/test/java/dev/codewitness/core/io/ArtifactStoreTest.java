package dev.codewitness.core.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Artefacts are referenced by evidence records, so their paths must be predictable and a relative
 * path must never be able to escape the artifact directory.
 */
class ArtifactStoreTest {

    @Test
    @DisplayName("write creates the file and returns its path under artifacts/")
    void writeCreatesFile(@TempDir Path run) throws IOException {
        ArtifactStore store = new ArtifactStore(run);

        Path written = store.write("logs/build.log", "hello");

        assertEquals(run.resolve("artifacts/logs/build.log").toAbsolutePath().normalize(), written);
        assertEquals("hello", Files.readString(written));
        assertTrue(Files.isDirectory(store.artifactsDirectory()));
    }

    @Test
    @DisplayName("write creates intermediate directories")
    void writeCreatesDirectories(@TempDir Path run) throws IOException {
        ArtifactStore store = new ArtifactStore(run);

        Path written = store.write("a/b/c/report.txt", "deep");

        assertTrue(Files.exists(written));
        assertEquals("deep", Files.readString(written));
    }

    @Test
    @DisplayName("write(null) writes an empty file rather than failing")
    void writeNullContentWritesEmptyFile(@TempDir Path run) throws IOException {
        ArtifactStore store = new ArtifactStore(run);

        Path written = store.write("empty.txt", null);

        assertEquals("", Files.readString(written));
    }

    @Test
    @DisplayName("write rejects a relative path that escapes the artifact directory")
    void writeRejectsEscapingRelativePath(@TempDir Path run) {
        ArtifactStore store = new ArtifactStore(run);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.write("../escape.txt", "should not be written"));

        assertTrue(error.getMessage().contains("escapes the artifact directory"), error.getMessage());
        assertFalse(Files.exists(run.resolve("escape.txt")), "nothing must be written outside artifacts/");
    }

    @Test
    @DisplayName("write rejects an absolute path outside the artifact directory")
    void writeRejectsAbsolutePathOutside(@TempDir Path run) {
        ArtifactStore store = new ArtifactStore(run);
        Path outside = run.resolveSibling("outside-codewitness.txt");

        assertThrows(IllegalArgumentException.class, () -> store.write(outside.toString(), "nope"));
        assertFalse(Files.exists(outside));
    }

    @Test
    @DisplayName("writeBytes stores binary content unchanged")
    void writeBytesStoresBinary(@TempDir Path run) throws IOException {
        ArtifactStore store = new ArtifactStore(run);
        byte[] bytes = {0, 1, 2, (byte) 0xFF};

        Path written = store.writeBytes("binary/report.bin", bytes);

        assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(written)));
    }

    @Test
    @DisplayName("copyIn copies an existing file into the store")
    void copyInCopiesFile(@TempDir Path run) throws IOException {
        ArtifactStore store = new ArtifactStore(run);
        Path source = run.resolve("surefire-report.xml");
        Files.writeString(source, "<testsuite/>", StandardCharsets.UTF_8);

        Path copied = store.copyIn(source, "raw/surefire-report.xml");

        assertEquals("<testsuite/>", Files.readString(copied));
        assertTrue(store.listArtifacts().contains(copied));
    }

    @Test
    void copyInRejectsEscapingRelativePath(@TempDir Path run) throws IOException {
        ArtifactStore store = new ArtifactStore(run);
        Path source = run.resolve("source.txt");
        Files.writeString(source, "content");

        // Currently FAILS: no exception is thrown and the file lands in the run directory.
        assertThrows(IllegalArgumentException.class, () -> store.copyIn(source, "../escape.txt"));
        assertFalse(Files.exists(run.resolve("escape.txt")));
    }

    @Test
    @DisplayName("copyTree copies a directory recursively")
    void copyTreeCopiesRecursively(@TempDir Path run) throws IOException {
        ArtifactStore store = new ArtifactStore(run);
        Path source = run.resolve("reports");
        Files.createDirectories(source.resolve("nested"));
        Files.writeString(source.resolve("coverage.xml"), "coverage");
        Files.writeString(source.resolve("nested/mutation.xml"), "mutation");

        Path target = store.copyTree(source, "raw/reports");

        assertEquals("coverage", Files.readString(target.resolve("coverage.xml")));
        assertEquals("mutation", Files.readString(target.resolve("nested/mutation.xml")));
    }

    @Test
    @DisplayName("copyTree on a non-directory returns the intended target without failing")
    void copyTreeOnMissingSourceIsTolerant(@TempDir Path run) {
        ArtifactStore store = new ArtifactStore(run);

        Path target = store.copyTree(run.resolve("does-not-exist"), "raw/missing");

        assertEquals(run.resolve("artifacts/raw/missing").toAbsolutePath().normalize(), target);
        assertFalse(Files.exists(target));
    }

    @Test
    @DisplayName("listArtifacts returns every stored file sorted by path")
    void listArtifactsReturnsSortedFiles(@TempDir Path run) {
        ArtifactStore store = new ArtifactStore(run);
        store.write("b.txt", "b");
        store.write("a.txt", "a");
        store.write("nested/c.txt", "c");

        List<Path> artifacts = store.listArtifacts();

        assertEquals(3, artifacts.size());
        List<String> relative = artifacts.stream()
                .map(p -> store.artifactsDirectory().relativize(p).toString().replace('\\', '/'))
                .toList();
        assertEquals(List.of("a.txt", "b.txt", "nested/c.txt"), relative);
    }

    @Test
    @DisplayName("an empty store lists nothing")
    void emptyStoreListsNothing(@TempDir Path run) {
        ArtifactStore store = new ArtifactStore(run);

        assertEquals(List.of(), store.listArtifacts());
    }

    @Test
    @DisplayName("the standard directories are created under the run directory")
    void standardDirectoriesAreCreated(@TempDir Path run) {
        ArtifactStore store = new ArtifactStore(run);

        assertEquals(run.toAbsolutePath().normalize(), store.runDirectory());
        assertTrue(Files.isDirectory(store.artifactsDirectory()));
        assertTrue(Files.isDirectory(store.logsDirectory()));
        assertTrue(Files.isDirectory(store.generatedTestsDirectory()));
    }
}

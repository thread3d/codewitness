package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of a tree that is six languages at once.
 *
 * <p>Every other end-to-end test here proves something about one language. This one proves the thing that
 * only appears when they are together: that one run selects every adapter a tree needs, that each language
 * is built, tested and analysed in its own right, and that the report says which languages those were -
 * including the one it contains and cannot verify at all.
 *
 * <p>The fixture holds a Maven project, a Python package, a Cargo crate, a Go module, a TypeScript ledger
 * and a plain-JavaScript tool in one directory, plus a {@code Gemfile} that no adapter understands. That
 * last file is not decoration: it is the case running more adapters cannot fix, where the honest answer is
 * to name the language rather than to compute a verdict as though the code were not there.
 *
 * <p>All six toolchains are needed, so unlike the per-language tests this one does not skip itself when one
 * is missing - a skipped test here would look exactly like a polyglot project that works. The CI workflow
 * installs every one of them before running this suite.
 */
@Tag("e2e")
class PolyglotEndToEndVerificationTest extends EndToEndSupport {

    private static final List<String> LANGUAGES =
            List.of("java", "python", "rust", "go", "javascript", "typescript");

    @Test
    @DisplayName("one run verifies all six languages in a tree, and names the language nothing can verify")
    void verifiesEveryLanguageInOneRun(@TempDir Path output) throws IOException {
        assumeEveryToolchainAvailable();
        Path fixture = requireFixture("polyglot-service");

        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--config", fixture.resolve("codewitness.yaml").toString(),
                "--output", output.toString(),
                "--quiet");

        JsonNode report = readReport(output);
        JsonNode languages = report.path("project").path("languages");

        // Every adapter that claimed part of the tree ran, found its own sources, and built them. A tree
        // read as one language would produce one entry here, and a language whose toolchain was missing
        // would report buildSucceeded false rather than disappearing.
        List<String> verified = new ArrayList<>();
        for (JsonNode language : languages) {
            if (language.path("verified").asBoolean()) {
                verified.add(language.path("language").asText());
                assertTrue(language.path("buildSucceeded").asBoolean(),
                        language.path("language").asText() + " did not build: " + language);
                assertTrue(language.path("modules").asInt() > 0,
                        language.path("language").asText() + " found no modules: " + language);
            }
        }
        assertEquals(LANGUAGES, verified,
                "every language in the fixture must be verified in one run: " + languages);

        // The language nothing can verify is a first-class entry, not a footnote: a reader who sees the six
        // verified languages without seeing Ruby would read the report as covering the whole tree.
        JsonNode ruby = languageNamed(languages, "ruby");
        assertFalse(ruby.path("verified").asBoolean(), "nothing built, tested or analysed the Ruby");
        assertEquals("Gemfile", ruby.path("evidence").asText(),
                "the entry must name the file that gives the language away");
        assertTrue(report.path("limitations").toString().contains("Ruby"),
                "the limitation must name the language: " + report.path("limitations"));

        assertEquals("polyglot", report.path("project").path("language").asText(),
                "a tree verified in six languages must not be reported as one of them");

        // The payoff: a generated Python test was executed against the Python half while five other
        // languages read the same directory, and the verdict landed on the requirement it was written for.
        // Nothing else in the suite proves that a generated experiment reaches the right language when
        // several are present.
        JsonNode total = requirement(report, "R-002");
        assertEquals("VERIFIED", total.path("status").asText(),
                "the executed scenario must discharge R-002: " + total);
        assertEquals("UNVERIFIED", requirement(report, "R-001").path("status").asText(),
                "a requirement with no scenario stays unverified rather than borrowing the Python result");

        assertEquals(0, exitCode, "nothing failed, so the run exits zero");
    }

    @Test
    @DisplayName("tests from five languages run in one pass, and the sixth says why it has none")
    void runsEveryLanguageTestSuiteInOnePass(@TempDir Path output) throws IOException {
        assumeEveryToolchainAvailable();
        Path fixture = requireFixture("polyglot-service");

        Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--config", fixture.resolve("codewitness.yaml").toString(),
                "--output", output.toString(),
                "--quiet");

        JsonNode report = readReport(output);
        assertEquals(8, report.path("tests").path("total").asInt(),
                "the fixture holds 2 Python, 2 Rust, 2 Go, 1 JavaScript and 1 TypeScript test: "
                        + report.path("tests"));
        assertEquals(8, report.path("tests").path("passed").asInt(), report.path("tests").toString());
        assertEquals(0, report.path("tests").path("failed").asInt(), report.path("tests").toString());

        // Each language's suite is recorded under its own adapter, which is what makes the merged counter
        // above readable: eight passing tests across five toolchains is not a claim about any one of them.
        for (String adapter : List.of("python", "rust", "go", "javascript", "typescript")) {
            JsonNode evidence = testEvidenceFor(report, adapter);
            assertTrue(evidence.path("result").asText().contains("passed"),
                    adapter + "'s suite must be recorded in its own right: " + evidence);
        }
        JsonNode java = testEvidenceFor(report, "java");
        assertTrue(java.path("result").asText().contains("no test source directory"),
                "the Java half declares no tests, and the report must say that rather than imply a pass: "
                        + java);

        assertEquals(0, report.path("tests").path("failed").asInt());
    }

    @Test
    @DisplayName("dependencies from more than one ecosystem are inventoried in one run")
    void inventoriesEveryEcosystem(@TempDir Path output) throws IOException {
        assumeEveryToolchainAvailable();
        Path fixture = requireFixture("polyglot-service");

        Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--config", fixture.resolve("codewitness.yaml").toString(),
                "--output", output.toString(),
                "--quiet");

        JsonNode dependencies = readReport(output).path("dependencies");
        List<String> ecosystems = new ArrayList<>();
        for (JsonNode coordinate : dependencies.path("coordinates")) {
            ecosystems.add(coordinate.path("ecosystem").asText());
        }
        // poetry.lock and package-lock.json are read in the same pass, by the two languages that wrote
        // them. A tree read as one language would inventory at most one ecosystem.
        assertTrue(ecosystems.contains("PyPI"), "poetry.lock must be read: " + ecosystems);
        assertTrue(ecosystems.contains("npm"), "package-lock.json must be read: " + ecosystems);

        // Cargo writes this crate's lock file during the build, and it lists the crate being built
        // alongside its dependencies. The crate has no dependencies of its own, so the right answer is no
        // crates.io coordinates at all - and certainly not one for polyglot-ledger itself, which would be
        // a project reported as a dependency of itself.
        for (JsonNode coordinate : dependencies.path("coordinates")) {
            assertFalse(coordinate.path("ecosystem").asText().equals("crates.io"),
                    "this crate declares no dependency, so none may be invented: " + coordinate);
        }
        assertFalse(ecosystems.contains("Maven"),
                "the Maven half declares no dependency either: " + ecosystems);

        // The inventory's own note has to name the languages it came from rather than claim one source.
        assertTrue(dependencies.path("tool").asText().contains("poetry-lock"), dependencies.toString());
        assertTrue(dependencies.path("tool").asText().contains("package-lock"), dependencies.toString());
    }

    // ------------------------------------------------------------------ helpers

    /** @return the language entry with the given id, failing when the report has none. */
    private static JsonNode languageNamed(JsonNode languages, String id) {
        for (JsonNode language : languages) {
            if (language.path("language").asText().equals(id)) {
                return language;
            }
        }
        throw new AssertionError("the report has no entry for " + id + ": " + languages);
    }

    /** @return the test evidence one adapter recorded, failing when there is none. */
    private static JsonNode testEvidenceFor(JsonNode report, String adapter) {
        for (JsonNode evidence : report.path("evidence")) {
            if (evidence.path("type").asText().equals("TEST")
                    && adapter.equals(evidence.path("metadata").path("adapter").asText())) {
                return evidence;
            }
        }
        throw new AssertionError("no test evidence for " + adapter + " in " + report.path("evidence"));
    }

    /**
     * Fails rather than skips when a toolchain is missing.
     *
     * <p>Everywhere else in this suite a missing toolchain skips the test, because that test is about a
     * language the machine may simply not have. Here it would be a hole in the point of the test: this one
     * exists to show that all six languages are verified together, and a run with one toolchain absent would
     * report that language as unbuilt while still passing.
     */
    private static void assumeEveryToolchainAvailable() {
        List<String> missing = new ArrayList<>();
        if (findMaven() == null) {
            missing.add("Maven");
        }
        if (findPython() == null) {
            missing.add("Python");
        }
        if (findCargo() == null) {
            missing.add("Cargo");
        }
        if (findGo() == null) {
            missing.add("Go");
        }
        if (findNode() == null) {
            missing.add("Node");
        }
        assertTrue(missing.isEmpty(),
                "this test needs every language toolchain, and these are missing: " + missing
                        + ". A missing toolchain here would look exactly like a polyglot project that works.");
    }

    /** Reads the JSON report written by the most recent run. */
    private JsonNode readReport(Path output) throws IOException {
        List<Path> runs = runDirectories(output);
        assertFalse(runs.isEmpty(), "a run directory must have been created");
        Path report = runs.get(0).resolve("report.json");
        assertTrue(Files.isRegularFile(report), "the canonical JSON report must exist at " + report);
        return new ObjectMapper().readTree(Files.readString(report, StandardCharsets.UTF_8));
    }
}

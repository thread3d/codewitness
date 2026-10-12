package dev.codewitness.swift.coverage;

import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.swift.SwiftTestContexts;
import dev.codewitness.swift.SwiftToolchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage measurement for Swift, read from the llvm-cov export SwiftPM writes.
 *
 * <p>Nothing here needs the Swift toolchain: the export is parsed from a synthetic file, and the run path
 * that would spawn {@code swift} is asserted only through the reasons it declines for. What the tests pin
 * is the two ways a coverage number can lie: a file that is somebody else's code, and a run that never
 * produced a report, both of which must end in "not measured" rather than in a percentage.
 */
class SwiftCoverageProviderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the provider names itself and claims Swift and nothing else")
    void supportsOnlySwift() {
        SwiftCoverageProvider provider = new SwiftCoverageProvider();

        assertEquals("swift-coverage", provider.id());
        assertTrue(provider.supports(SwiftTestContexts.projectInfo(tmp)));

        ProjectInfo javaProject = ProjectInfo.builder(ProjectRef.of(tmp))
                .language(LanguageId.JAVA)
                .build();
        assertFalse(provider.supports(javaProject),
                "the Java adapter has its own coverage provider; two would both run");
        assertFalse(provider.supports(null));
    }

    @Test
    @DisplayName("a file is attributed only when it is one of the project's own Swift sources")
    void attributesOnlyTheProjectsOwnFiles() throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path ledger = SwiftTestContexts.write(project, "Sources/Ledger/Ledger.swift", "public struct Ledger {}\n");

        assertEquals("Sources/Ledger/Ledger.swift",
                SwiftCoverageProvider.projectSourceName(project, ledger.toString()));
        assertEquals("Sources/Ledger/Ledger.swift",
                SwiftCoverageProvider.projectSourceName(project, "Sources/Ledger/Ledger.swift"),
                "a relative name is resolved against the project root");

        assertNull(SwiftCoverageProvider.projectSourceName(project,
                        project.resolve(".build/checkouts/dep/Sources/Dep/Dep.swift").toString()),
                "a checked-out dependency is somebody else's code");
        assertNull(SwiftCoverageProvider.projectSourceName(project,
                        project.resolve("Sources/Ledger/notes.txt").toString()),
                "a non-Swift file is not project source");
        assertNull(SwiftCoverageProvider.projectSourceName(project,
                        project.resolve("Sources/Ledger/runner.swift").toString()),
                "the test runner SwiftPM derives is a scaffold, not the project's code");
        assertNull(SwiftCoverageProvider.projectSourceName(project,
                        project.resolve("DerivedSources/Generated.swift").toString()));
        assertNull(SwiftCoverageProvider.projectSourceName(project, null));
        assertNull(SwiftCoverageProvider.projectSourceName(project, "   "));
        assertNull(SwiftCoverageProvider.projectSourceName(null, ledger.toString()));

        Path outside = tmp.resolve("elsewhere/Thing.swift");
        assertEquals(outside.toString(), SwiftCoverageProvider.projectSourceName(project, outside.toString()),
                "a file outside the project is still displayed, just not made relative");
    }

    @Test
    @DisplayName("the export is summed over the project's own files only, and the per-file map is restricted")
    void parsesTheExport() throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path ledger = SwiftTestContexts.write(project, "Sources/Ledger/Ledger.swift", "public struct Ledger {}\n");
        Path dependency = SwiftTestContexts.write(project,
                ".build/checkouts/dep/Sources/Dep/Dep.swift", "public struct Dep {}\n");
        Path report = SwiftTestContexts.write(tmp, "codecov.json",
                export(entry(ledger, 4, 3, 2, 1), entry(dependency, 100, 100, 5, 5)));

        CoverageReport coverage = SwiftCoverageProvider.parse(report, project, "test");

        assertTrue(coverage.measured(), coverage.note());
        assertEquals("llvm-cov", coverage.tool());
        assertEquals(3, coverage.lineCovered(), coverage.describe());
        assertEquals(4, coverage.lineTotal(),
                "a dependency's lines say nothing about the code under verification");
        assertEquals(1, coverage.branchCovered());
        assertEquals(2, coverage.branchTotal());
        assertEquals(1, coverage.perClassLineCoverage().size(), coverage.perClassLineCoverage().toString());
        assertEquals(75.0, coverage.perClassLineCoverage().get("Sources/Ledger/Ledger.swift"), 0.001);
        assertEquals(List.of(report), coverage.reports());
        assertTrue(coverage.note().contains("1 file(s)"), coverage.note());
    }

    @Test
    @DisplayName("an export that names no file of this project is not measured, never zero")
    void anExportWithoutAProjectFileIsNotMeasured() throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path dependency = SwiftTestContexts.write(project,
                ".build/checkouts/dep/Sources/Dep/Dep.swift", "public struct Dep {}\n");
        Path report = SwiftTestContexts.write(tmp, "codecov.json", export(entry(dependency, 10, 10, 0, 0)));

        CoverageReport coverage = SwiftCoverageProvider.parse(report, project, "test");

        assertFalse(coverage.measured(), "a percentage over a dependency is not a measurement of this project");
        assertTrue(coverage.note().contains("names no file of this project's own Swift sources"),
                coverage.note());
        assertEquals(0, coverage.lineCovered(), "not measured carries no counts at all");
        assertEquals(0, coverage.lineTotal(), "which is not the same thing as a measured zero");
    }

    @Test
    @DisplayName("a report that cannot be read, or carries no data, is not measured")
    void unreadableExportsAreNotMeasured() throws IOException {
        CoverageReport missing = SwiftCoverageProvider.parse(
                Path.of("/does/not/exist/codecov.json"), tmp, "test");
        CoverageReport malformed = SwiftCoverageProvider.parse(
                SwiftTestContexts.write(tmp, "broken.json", "{\"data\":["), tmp, "test");
        CoverageReport noData = SwiftCoverageProvider.parse(
                SwiftTestContexts.write(tmp, "nodata.json", "{\"type\":\"llvm.coverage.json.export\"}"),
                tmp, "test");

        assertFalse(missing.measured());
        assertTrue(missing.note().contains("could not be read"), missing.note());
        assertFalse(malformed.measured());
        assertTrue(malformed.note().contains("could not be read"), malformed.note());
        assertFalse(noData.measured());
        assertTrue(noData.note().contains("carries no data section"), noData.note());
    }

    @Test
    @DisplayName("a toolchain that instrumented no branches says so rather than reporting zero")
    void noBranchRegionsIsStatedInTheNote() throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path ledger = SwiftTestContexts.write(project, "Sources/Ledger/Ledger.swift", "public struct Ledger {}\n");
        Path report = SwiftTestContexts.write(tmp, "codecov.json", export(entry(ledger, 2, 2, 0, 0)));

        CoverageReport coverage = SwiftCoverageProvider.parse(report, project, "test");

        assertTrue(coverage.measured(), coverage.note());
        assertEquals(0, coverage.branchTotal());
        assertTrue(coverage.note().contains("instrumented no branch regions"), coverage.note());
    }

    @Test
    @DisplayName("the fallback scan finds the report in either SwiftPM build layout")
    void findsTheReportUnderBuild() throws IOException {
        Path debug = tmp.resolve("debug-project");
        Path report = SwiftTestContexts.write(debug, ".build/debug/codecov/report.json", "{}");
        assertEquals(report, SwiftCoverageProvider.codecovJsonUnderBuild(debug));

        Path triple = tmp.resolve("triple-project");
        Path tripleReport = SwiftTestContexts.write(triple,
                ".build/arm64-apple-macosx/debug/codecov/out.json", "{}");
        assertEquals(tripleReport, SwiftCoverageProvider.codecovJsonUnderBuild(triple),
                "the .build/<triple>/<config>/codecov layout is covered too");

        assertNull(SwiftCoverageProvider.codecovJsonUnderBuild(tmp.resolve("no-build")));
    }

    @Test
    @DisplayName("the printed path is preferred when it names a JSON file or the codecov directory")
    void readsThePrintedPath() throws IOException {
        Path json = SwiftTestContexts.write(tmp, "printed/report.json", "{}");

        assertEquals(json, SwiftCoverageProvider.codecovJson(json.toString()));
        assertEquals(json, SwiftCoverageProvider.codecovJson(json.getParent().toString()),
                "some toolchains print the codecov directory rather than the file inside it");
        assertNull(SwiftCoverageProvider.codecovJson(null));
        assertNull(SwiftCoverageProvider.codecovJson("   "));
        assertNull(SwiftCoverageProvider.codecovJson(tmp.resolve("missing.json").toString()));
        assertNull(SwiftCoverageProvider.codecovJson(
                SwiftTestContexts.write(tmp, "printed/notes.txt", "x").toString()),
                "a printed path that is not JSON is not a report");
    }

    @Test
    @DisplayName("a stale codecov directory is cleared before a run, so an empty run is never read as these numbers")
    void clearsStaleCoverage() throws IOException {
        Path project = tmp.resolve("project");
        Path report = SwiftTestContexts.write(project, ".build/debug/codecov/report.json", "{}");
        Path nested = SwiftTestContexts.write(project, ".build/debug/codecov/nested/other.txt", "x");
        Path keep = SwiftTestContexts.write(project, ".build/debug/keep.txt", "keep");

        List<Path> directories = SwiftCoverageProvider.codecovDirectories(project.resolve(".build"));
        assertTrue(directories.contains(project.resolve(".build/debug/codecov")), directories.toString());

        SwiftCoverageProvider.clearStaleCoverage(project);

        assertFalse(Files.exists(report), "a report from an earlier run must not be read as this run's");
        assertFalse(Files.exists(nested));
        assertTrue(Files.exists(keep), "only codecov directories are cleared");
        assertTrue(Files.isDirectory(project.resolve(".build/debug/codecov")),
                "the directory itself is kept; only its contents are cleared");
    }

    @Test
    @DisplayName("helpers pick a stable file and the last non-blank line")
    void helperSelectionIsStable() throws IOException {
        Path directory = SwiftTestContexts.write(tmp, "candidates/b.json", "{}").getParent();
        Path first = SwiftTestContexts.write(tmp, "candidates/a.json", "{}");
        SwiftTestContexts.write(tmp, "candidates/note.txt", "x");

        assertEquals(first, SwiftCoverageProvider.firstJson(directory));
        assertNull(SwiftCoverageProvider.firstJson(tmp.resolve("no-such-directory")));
        assertNull(SwiftCoverageProvider.firstJson(
                SwiftTestContexts.write(tmp, "text-only/only.txt", "x").getParent()));

        assertEquals("two", SwiftCoverageProvider.lastNonBlankLine("one\n\n  two  \n"));
        assertEquals("one", SwiftCoverageProvider.lastNonBlankLine("one"));
        assertNull(SwiftCoverageProvider.lastNonBlankLine("   \n\n"));
        assertNull(SwiftCoverageProvider.lastNonBlankLine(null));
    }

    @Test
    @DisplayName("a probe failure, a missing executable and a package with no tests are reasons, not a zero")
    void measureDeclinesWithAReasonAndRunsNothing() throws IOException {
        SwiftCoverageProvider provider = new SwiftCoverageProvider();

        Path probed = tmp.resolve("probed");
        SwiftTestContexts.writePackageWithTests(probed);
        VerificationContext probeFailed = SwiftTestContexts.context(
                SwiftTestContexts.withAttributes(probed,
                        Map.of(SwiftToolchain.ATTR_PROBE_FAILURE, "swift: not found")),
                tmp.resolve("run-probed"));
        CoverageReport noToolchain = provider.measure(probeFailed);

        assertFalse(noToolchain.measured());
        assertTrue(noToolchain.note().contains("could not be probed"), noToolchain.note());

        Path detectedOnly = tmp.resolve("detected-only");
        SwiftTestContexts.writePackageWithTests(detectedOnly);
        CoverageReport noExecutable = provider.measure(
                SwiftTestContexts.context(detectedOnly, tmp.resolve("run-no-exe")));

        assertFalse(noExecutable.measured());
        assertTrue(noExecutable.note().contains("no swift executable was resolved"), noExecutable.note());

        Path noTests = tmp.resolve("no-tests");
        SwiftTestContexts.writePackageWithoutTests(noTests);
        VerificationContext withoutSuite = SwiftTestContexts.context(
                SwiftTestContexts.withAttributes(noTests,
                        Map.of(SwiftToolchain.ATTR_SWIFT, SwiftTestContexts.MISSING_SWIFT)),
                tmp.resolve("run-no-tests"));
        CoverageReport nothingToInstrument = provider.measure(withoutSuite);

        assertFalse(nothingToInstrument.measured());
        assertTrue(nothingToInstrument.note().contains("no test target"), nothingToInstrument.note());
        assertEquals(0, nothingToInstrument.lineTotal());
    }

    // ------------------------------------------------------------------ helpers

    private static String export(String... entries) {
        return "{\"data\":[{\"files\":[" + String.join(",", entries) + "]}]}";
    }

    private static String entry(Path file, int lines, int coveredLines, int branches, int coveredBranches) {
        return "{\"filename\":\"" + file.toString().replace("\\", "\\\\") + "\","
                + "\"summary\":{"
                + "\"lines\":{\"count\":" + lines + ",\"covered\":" + coveredLines + "},"
                + "\"branches\":{\"count\":" + branches + ",\"covered\":" + coveredBranches + "}}}";
    }
}

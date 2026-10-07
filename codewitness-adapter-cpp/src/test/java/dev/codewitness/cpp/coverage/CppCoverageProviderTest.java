package dev.codewitness.cpp.coverage;

import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.cpp.CppTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static dev.codewitness.cpp.CppTestContexts.LIBRARY_CMAKE;
import static dev.codewitness.cpp.CppTestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the gcov coverage provider.
 *
 * <p>The document format is asserted against real gcov output - {@code -:    0:Source:...} headers, a
 * {@code #####} count for a line that did not run, and {@code branch  <n> taken <count>} lines - because the
 * parser is the only thing standing between the compiler's instrumentation and the report's figures.
 */
class CppCoverageProviderTest {

    /** A document in the shape `gcov -b -c` writes. */
    private static final String GCOV = """
                    -:    0:Source:/work/src/ledger.cpp
                    -:    0:Graph:CMakeFiles/ledger.dir/src/ledger.cpp.gcno
                    -:    0:Data:CMakeFiles/ledger.dir/src/ledger.cpp.gcda
                    -:    0:Runs:1
            function _ZN6ledger5clampExx called 1 returned 100% blocks executed 80%
                    1:    1:namespace ledger {
                    1:    2:long long clamp(long long amount, long long limit) {
                    1:    3:    if (amount < 0) {
            branch  0 taken 1
            branch  1 taken 0
                #####:    4:        return 0;
                    -:    5:    }
                    4:    6:    for (long long i = 0; i < 3; ++i) {
            branch  0 taken 3
            branch  1 taken 1
                    3:    7:        amount += i;
                    3:    8:    }
                    1:    9:    if (amount > limit) {
            branch  0 taken 0
            branch  1 taken 1
                #####:   10:        return limit;
                    -:   11:    }
                    1:   12:    return amount;
                    1:   13:}
                    -:   14:}
            """;

    @Test
    @DisplayName("a gcov document yields its lines, its branches and the file it names")
    void parsesOneDocument() {
        CppCoverageProvider.FileCoverage coverage = CppCoverageProvider.parseFile(GCOV);

        assertEquals("/work/src/ledger.cpp", coverage.source());
        assertTrue(coverage.coverable());
        assertEquals(11, coverage.lineTotal(), "every line whose count is not - is instrumented");
        assertEquals(9, coverage.lineCovered(), "the two lines gcov marked ##### did not run");
        assertEquals(6, coverage.branchTotal());
        assertEquals(4, coverage.branchCovered(), "two of the six branches were taken 0 times");
        assertEquals(100.0 * 9 / 11, coverage.linePercent(), 0.001);
    }

    @Test
    @DisplayName("a document with no instrumented line is not a measurement of zero")
    void emptyDocumentIsNotMeasured() {
        CppCoverageProvider.FileCoverage coverage = CppCoverageProvider.parseFile("");

        assertFalse(coverage.coverable());
        CoverageReport report = CppCoverageProvider.parse("", "nothing to read");
        assertFalse(report.measured());
        assertTrue(report.note().contains("nothing to read"), report.note());
    }

    @Test
    @DisplayName("one document becomes a measured report for one file")
    void parsesToAReport() {
        CoverageReport report = CppCoverageProvider.parse(GCOV, "gcov over an instrumented build");

        assertTrue(report.measured());
        assertEquals("gcov-coverage", report.tool());
        assertEquals(9, report.lineCovered());
        assertEquals(11, report.lineTotal());
        assertEquals(100.0 * 9 / 11, report.lineCoveragePercent(), 0.001);
        assertEquals(4, report.branchCovered());
        assertEquals(6, report.branchTotal());
        assertEquals(100.0 * 4 / 6, report.branchCoveragePercent(), 0.001);
        assertEquals(100.0 * 9 / 11, report.perClassLineCoverage().get("/work/src/ledger.cpp"), 0.001);
    }

    @Test
    @DisplayName("a percentage branch line is read the same as a counted one")
    void parsesPercentageBranches() {
        String document = """
                        -:    0:Source:/work/src/a.cpp
                        1:    1:int main() { return 0; }
                branch  0 taken 66.67%
                branch  1 taken 0%
                branch  2 never executed
                """;

        CppCoverageProvider.FileCoverage coverage = CppCoverageProvider.parseFile(document);

        assertEquals(3, coverage.branchTotal());
        assertEquals(1, coverage.branchCovered(), "taken 66.67% is covered, taken 0% and never executed are not");
    }

    @Test
    @DisplayName("the same source compiled twice is one entry with both runs' counts")
    void combinesDocumentsForOneSource(@TempDir Path directory) throws IOException {
        Path first = write(directory, "a.gcov", """
                        -:    0:Source:/work/src/ledger.cpp
                        1:    1:int a() { return 0; }
                        -:    2:int b() { return 0; }
                """);
        Path second = write(directory, "b.gcov", """
                        -:    0:Source:/work/src/ledger.cpp
                        -:    1:int a() { return 0; }
                        1:    2:int b() { return 0; }
                """);

        CoverageReport report = CppCoverageProvider.combine(List.of(first, second), "two targets", Path.of("/work"));

        assertTrue(report.measured());
        assertEquals(2, report.lineTotal());
        assertEquals(2, report.lineCovered());
        assertEquals(1, report.perClassLineCoverage().size(), "one entry per source, not one per document");
        assertEquals(100.0, report.perClassLineCoverage().get("src/ledger.cpp"), 0.001);
    }

    @Test
    @DisplayName("an unreadable report is named in the note rather than silently dropped")
    void unreadableReportIsNamed(@TempDir Path directory) throws IOException {
        Path good = write(directory, "good.gcov", """
                        -:    0:Source:/work/src/a.cpp
                        1:    1:int main() { return 0; }
                """);

        CoverageReport report = CppCoverageProvider.combine(List.of(good, directory.resolve("absent.gcov")),
                "one report went missing", Path.of("/work"));

        assertTrue(report.measured());
        assertTrue(report.note().contains("absent.gcov"), report.note());
    }

    @Test
    @DisplayName("nothing measured is reported as nothing measured, never as zero coverage")
    void reportsWhyNothingWasMeasured(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "Makefile", "all:\n\tc++ -o ledger src/ledger.cpp\n");
        write(project, "src/ledger.cpp", "int main() { return 0; }\n");
        VerificationContext context = CppTestContexts.context(project, run);

        CoverageReport report = new CppCoverageProvider().measure(context);

        assertFalse(report.measured());
        assertTrue(report.note().contains("no CMakeLists.txt"), report.note());
        assertEquals(0, report.lineTotal(), "an unmeasured report carries no figure at all");
    }

    @Test
    @DisplayName("a project with no registered test has nothing to measure coverage over")
    void noTestsMeansNoCoverage(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        VerificationContext context = CppTestContexts.context(project, run);

        CoverageReport report = new CppCoverageProvider().measure(context);

        assertFalse(report.measured());
        assertTrue(report.note().contains("registers no test with add_test"), report.note());
    }

    @Test
    @DisplayName("the provider claims C and C++ projects and names itself")
    void identifiesItself(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);
        CppCoverageProvider provider = new CppCoverageProvider();

        assertEquals("gcov-coverage", provider.id());
        assertTrue(provider.supports(context.projectInfo()));
    }

    @Test
    @DisplayName("a header outside the project is excluded, because the project cannot cover it")
    void excludesCodeTheProjectDoesNotOwn(@TempDir Path directory) throws IOException {
        Path own = write(directory, "own.gcov", """
                        -:    0:Source:/work/src/ledger.cpp
                        1:    1:int ledger() { return 0; }
                """);
        Path header = write(directory, "header.gcov", """
                        -:    0:Source:/usr/include/c++/v1/vector
                        1:    1:namespace std {
                        0:    2:void push_back();
                """);

        CoverageReport report = CppCoverageProvider.combine(List.of(own, header), "instrumented build",
                Path.of("/work"));

        assertTrue(report.measured());
        assertEquals(1, report.lineTotal(),
                "a line in the standard library header is not a line this project can cover");
        assertEquals(1, report.lineCovered());
        assertTrue(report.note().contains("outside the project"), report.note());
        assertEquals(1, report.reports().size());
    }

    @Test
    @DisplayName("a report the project owns is told from one it does not")
    void tellsTheProjectsOwnCodeFromTheRest() {
        assertTrue(CppCoverageProvider.isTheProjectsOwn(Path.of("/work"), "/work/src/ledger.cpp"));
        assertTrue(CppCoverageProvider.isTheProjectsOwn(Path.of("/work"), "src/ledger.cpp"),
                "a relative path cannot be placed, so it is kept rather than dropped");
        assertFalse(CppCoverageProvider.isTheProjectsOwn(Path.of("/work"), "/usr/include/vector"));
        assertTrue(CppCoverageProvider.isTheProjectsOwn(null, "/usr/include/vector"));
    }

    @Test
    @DisplayName("a shortener keeps a path under the project relative and one outside it as it is")
    void shortensPaths(@TempDir Path project) {
        Path source = project.resolve("src/ledger.cpp").toAbsolutePath().normalize();

        assertEquals("src/ledger.cpp", CppCoverageProvider.shorten(project, source.toString()));
        assertEquals("/elsewhere/a.cpp", CppCoverageProvider.shorten(project, "/elsewhere/a.cpp"));
        assertEquals("", CppCoverageProvider.shorten(project, null));
    }
}

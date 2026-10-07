package dev.codewitness.php.coverage;

import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.php.PhpInterpreter;
import dev.codewitness.php.PhpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage measurement for PHP, read from PHPUnit's Clover XML.
 *
 * <p>Nothing here needs PHP, Composer or PHPUnit: the report is parsed from a synthetic file, and the run
 * path is driven through a substituted process runner. What the tests are really about is the two ways a
 * coverage number can lie - a report that names somebody else's file, and a run that never happened - and
 * both are asserted to end in "not measured" rather than in a percentage.
 */
class PhpCoverageProviderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("statement and branch lines are counted per file of the project")
    void parsesCloverReport() throws IOException {
        Path ledger = PhpTestContexts.write(tmp, "src/Ledger.php", "<?php\nclass Ledger {}\n");
        Path report = PhpTestContexts.write(tmp, "clover.xml", clover(List.of(
                file(ledger, """
                        <line num="9" type="method" count="1"/>
                        <line num="11" type="stmt" count="1"/>
                        <line num="12" type="stmt" count="0"/>
                        <line num="15" type="cond" count="1"/>
                        <line num="16" type="cond" count="0"/>
                        """))));

        CoverageReport coverage = PhpCoverageProvider.parse(report, tmp, "test");

        assertTrue(coverage.measured());
        assertEquals("clover", coverage.tool());
        assertEquals(1, coverage.lineCovered(), "one statement line is covered");
        assertEquals(2, coverage.lineTotal(), "the method line is not a statement");
        assertEquals(1, coverage.branchCovered());
        assertEquals(2, coverage.branchTotal());
        assertEquals(1, coverage.perClassLineCoverage().size(), coverage.perClassLineCoverage().toString());
        assertEquals(50.0, coverage.perClassLineCoverage().get("src/Ledger.php"), 0.001,
                "the key is the path relative to the project root, which is what a reader can open");
    }

    @Test
    @DisplayName("a file under vendor/, outside the project, or already deleted is not the project's code")
    void countsOnlyTheProjectsOwnFiles() throws IOException {
        Path ledger = PhpTestContexts.write(tmp, "src/Ledger.php", "<?php\nclass Ledger {}\n");
        Path dependency = PhpTestContexts.write(tmp, "vendor/acme/lib/src/Thing.php", "<?php\nclass Thing {}\n");
        Path deleted = tmp.getParent().resolve("cw-elsewhere").resolve("Elsewhere.php");
        Path report = PhpTestContexts.write(tmp, "clover.xml", clover(List.of(
                file(ledger, "<line num=\"3\" type=\"stmt\" count=\"1\"/>\n"),
                file(dependency, "<line num=\"3\" type=\"stmt\" count=\"0\"/>\n"),
                file(deleted, "<line num=\"3\" type=\"stmt\" count=\"1\"/>\n"))));

        CoverageReport coverage = PhpCoverageProvider.parse(report, tmp, "test");

        assertTrue(coverage.measured(), coverage.note());
        assertEquals(1, coverage.lineCovered(),
                "a dependency's covered lines say nothing about the code under verification");
        assertEquals(1, coverage.lineTotal());
        assertEquals(1, coverage.perClassLineCoverage().size(), coverage.perClassLineCoverage().toString());
        assertTrue(coverage.perClassLineCoverage().containsKey("src/Ledger.php"),
                coverage.perClassLineCoverage().toString());
        assertFalse(coverage.perClassLineCoverage().keySet().stream()
                        .anyMatch(name -> name.contains("vendor") || name.contains("Elsewhere")),
                "neither a vendor file nor a file that does not exist may appear: "
                        + coverage.perClassLineCoverage());
    }

    @Test
    @DisplayName("a report that names no file of this project is not measured, never zero")
    void aReportWithoutAProjectFileIsNotMeasured() throws IOException {
        Path dependency = PhpTestContexts.write(tmp, "vendor/acme/lib/src/Thing.php", "<?php\nclass Thing {}\n");
        Path report = PhpTestContexts.write(tmp, "clover.xml", clover(List.of(
                file(dependency, "<line num=\"3\" type=\"stmt\" count=\"1\"/>\n"))));

        CoverageReport coverage = PhpCoverageProvider.parse(report, tmp, "test");

        assertFalse(coverage.measured(),
                "a percentage over vendor code is not a measurement of this project");
        assertTrue(coverage.note().contains("no file of this project"), coverage.note());
    }

    @Test
    @DisplayName("a missing or malformed report is not measured, with the reason")
    void unreadableReportsAreNotMeasured() throws IOException {
        CoverageReport missing = PhpCoverageProvider.parse(
                Path.of("/does/not/exist/clover.xml"), tmp, "test");
        CoverageReport malformed = PhpCoverageProvider.parse(
                PhpTestContexts.write(tmp, "broken.xml", "<coverage><project>"), tmp, "test");

        assertFalse(missing.measured());
        assertTrue(missing.note().contains("could not be read"), missing.note());
        assertFalse(malformed.measured());
        assertTrue(malformed.note().contains("could not be read"), malformed.note());
    }

    @Test
    @DisplayName("without a coverage driver in the interpreter nothing is measured and nothing runs")
    void noCoverageDriverIsNotMeasured() throws IOException {
        VerificationContext context = context(Map.of(), refusingToRun());

        CoverageReport coverage = new PhpCoverageProvider().measure(context);

        assertFalse(coverage.measured(), "PHPUnit cannot measure coverage without a driver");
        assertTrue(coverage.note().contains("Xdebug") && coverage.note().contains("PCOV"),
                "the report must name the missing driver: " + coverage.note());
        assertTrue(coverage.note().contains("not measured rather than reported as zero"), coverage.note());
    }

    @Test
    @DisplayName("a driver value the probe did not report is read as no driver")
    void unknownCoverageDriverIsNotMeasured() throws IOException {
        VerificationContext context = context(
                Map.of(PhpInterpreter.ATTR_COVERAGE_DRIVER, "none"), refusingToRun());

        CoverageReport coverage = new PhpCoverageProvider().measure(context);

        assertFalse(coverage.measured(), "an unknown driver value is not evidence that coverage can be measured");
        assertTrue(coverage.note().contains("neither Xdebug nor PCOV"), coverage.note());
    }

    @Test
    @DisplayName("with a driver but no PHPUnit the report says so instead of inventing a runner")
    void aDriverWithoutPhpunitIsNotMeasured() throws IOException {
        VerificationContext context = context(
                Map.of(PhpInterpreter.ATTR_COVERAGE_DRIVER, "pcov"), refusingToRun());

        CoverageReport coverage = new PhpCoverageProvider().measure(context);

        assertFalse(coverage.measured());
        assertTrue(coverage.note().contains("PHPUnit was not found"), coverage.note());
    }

    @Test
    @DisplayName("with a driver and a PHPUnit the run asks for the Clover report it later parses")
    void runsPhpunitWithCloverWhenADriverWasObserved() throws IOException {
        Path ledger = PhpTestContexts.write(tmp, "src/Ledger.php", "<?php\nclass Ledger {}\n");
        PhpTestContexts.write(tmp, "vendor/bin/phpunit", "#!/usr/bin/env php\n");
        PhpTestContexts.write(tmp, "phpunit.xml", "<?xml version=\"1.0\"?>\n<phpunit/>\n");
        String clover = clover(List.of(file(ledger, "<line num=\"3\" type=\"stmt\" count=\"1\"/>\n")));
        List<String> command = new ArrayList<>();
        VerificationContext context = context(
                Map.of(PhpInterpreter.ATTR_COVERAGE_DRIVER, "xdebug"),
                sandboxWritingReport(clover, command));

        CoverageReport coverage = new PhpCoverageProvider().measure(context);

        assertTrue(coverage.measured(), coverage.note());
        assertEquals(1, coverage.lineCovered());
        assertEquals(1, coverage.lineTotal());
        assertTrue(command.contains("--coverage-clover"),
                "the run must ask for the report this provider parses: " + command);
        assertTrue(command.contains(PhpCoverageProvider.reportPath(context).toString()),
                "and write it where the provider looks: " + command);
        assertTrue(command.contains("--configuration"),
                "the project's own PHPUnit configuration is what makes its tests loadable: " + command);
        assertTrue(coverage.note().contains("xdebug"), coverage.note());
        assertEquals(1, command.stream().filter("--do-not-cache-result"::equals).count(),
                "a cached result would let a second run reuse the first one's outcome: " + command);
    }

    @Test
    @DisplayName("the provider claims PHP and nothing else")
    void supportsOnlyPhp() throws IOException {
        VerificationContext context = context(Map.of(), refusingToRun());
        PhpCoverageProvider provider = new PhpCoverageProvider();

        assertTrue(provider.supports(context.projectInfo()));
        assertEquals("php-coverage", provider.id());

        ProjectInfo javaProject = ProjectInfo.builder(context.projectInfo().project())
                .language(LanguageId.JAVA)
                .build();
        assertFalse(provider.supports(javaProject),
                "the Java adapter has its own coverage provider; two would both run");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @return a context whose project declares no interpreter, so that PHPUnit resolution cannot depend on
     *     what the machine running the tests happens to have installed
     */
    private VerificationContext context(Map<String, String> attributes, ExecutionSandbox sandbox)
            throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP);
        merged.putAll(attributes);
        return PhpTestContexts.context(
                PhpTestContexts.withAttributes(tmp, merged), tmp.resolve("run"), List.of(), sandbox);
    }

    /** Builds a Clover document around the given {@code <file>} elements. */
    private static String clover(List<String> files) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE coverage SYSTEM \"clover.dtd\">\n"
                + "<coverage generated=\"1700000000\">\n  <project timestamp=\"1700000000\">\n"
                + String.join("", files)
                + "  </project>\n</coverage>\n";
    }

    private static String file(Path path, String lines) {
        return "    <file name=\"" + path + "\">\n      " + lines + "    </file>\n";
    }

    /** A sandbox whose runner fails the test if anything is executed. */
    private static ExecutionSandbox refusingToRun() {
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "test-sandbox";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: no process may run on this path";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    throw new AssertionError("no process may run: " + spec.command());
                };
            }
        };
    }

    /**
     * A sandbox whose runner records its argv and writes the Clover report the provider expects.
     *
     * <p>The report is written where the provider looks for it - {@code <run>/artifacts/php/coverage} - so
     * that the test exercises the same path a real PHPUnit run would take rather than a shortcut.
     */
    private ExecutionSandbox sandboxWritingReport(String clover, List<String> command) {
        Path report = tmp.resolve("run").resolve("artifacts").resolve("php").resolve("coverage")
                .resolve("clover.xml");
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "test-sandbox";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: writes the report the provider expects without starting PHPUnit";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    command.addAll(spec.command());
                    try {
                        Files.createDirectories(report.getParent());
                        Files.writeString(report, clover, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return new ProcessResult(spec.command(), spec.workingDirectory(), true, 0, "", "",
                            Duration.ZERO, false, null);
                };
            }
        };
    }
}

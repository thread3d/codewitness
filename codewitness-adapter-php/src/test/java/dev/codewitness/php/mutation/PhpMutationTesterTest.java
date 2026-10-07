package dev.codewitness.php.mutation;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.php.PhpInterpreter;
import dev.codewitness.php.PhpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation tester's contract for the outcomes that need no interpreter.
 *
 * <p>Nothing here spawns a process: the parts that do - writing a mutant, running PHPUnit, checking the
 * mutant with the lint script - are covered by the lexer tests and by the end-to-end fixture, and a test
 * that needed PHP installed to assert "PHPUnit was not found" would be asserting the machine rather than
 * the adapter.
 */
class PhpMutationTesterTest {

    @Test
    @DisplayName("the tester names itself as the report tool the adapter documents")
    void namesItselfAndClaimsOnlyPhp(@TempDir Path root) {
        PhpMutationTester tester = new PhpMutationTester();

        assertEquals("php-text-mutation", tester.id());
        assertTrue(tester.supports(PhpTestContexts.projectInfo(root)));
        assertFalse(tester.supports(ProjectInfo.builder(ProjectRef.of(root))
                .language(dev.codewitness.core.model.LanguageId.JAVA).build()),
                "a Java project's mutation is the Java adapter's to measure");
    }

    @Test
    @DisplayName("a failed interpreter probe is not measured, never a score over an unparsed tree")
    void anUnavailableInterpreterIsNotMeasured(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "src/Ledger.php", "<?php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root, Map.of(
                        PhpInterpreter.ATTR_PROBE_FAILURE, "php: not found",
                        PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        MutationReport report = new PhpMutationTester().analyse(context);

        assertFalse(report.measured(), report.describe());
        assertTrue(report.note().contains("interpreter probe failed"), report.note());
    }

    @Test
    @DisplayName("without PHPUnit nothing can be judged, so the run is not measured")
    void withoutPhpunitTheRunIsNotMeasured(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "src/Ledger.php", "<?php\n$total = $a + $b;\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root,
                        Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        MutationReport report = new PhpMutationTester().analyse(context);

        assertFalse(report.measured(), report.describe());
        assertTrue(report.note().contains("PHPUnit was not found"),
                "a mutant whose tests cannot run says nothing about test strength: " + report.note());
        assertEquals(0, report.generated(), "no mutant may be counted when none could be judged");
    }

    @Test
    @DisplayName("the report the tester parses is the one the test command writes")
    void theReportIsReadFromTheTestCommand() {
        List<String> command = List.of("php", "vendor/bin/phpunit", "--log-junit",
                "/run/artifacts/php/tests/junit.xml", "--colors=never");

        assertEquals(Path.of("/run/artifacts/php/tests/junit.xml"), PhpMutationTester.junitReport(command),
                "rebuilding the path here would let it drift from the provider's own layout");
        assertNull(PhpMutationTester.junitReport(List.of("php", "vendor/bin/phpunit")),
                "a command with no report names no report");
    }

    @Test
    @DisplayName("the project's own sources are mutated and its tests are not")
    void testsAreNotMutated(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "Ledger.php", "<?php\nclass Ledger {}\n");
        PhpTestContexts.write(root, "OtherTest.php", "<?php\nclass OtherTest {}\n");
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\nclass LedgerTest {}\n");
        VerificationContext context = PhpTestContexts.context(root, run);

        List<Path> targets = PhpMutationTester.targetFiles(context);

        assertEquals(List.of(root.resolve("Ledger.php")), targets,
                "mutating the oracle would measure the test suite against itself: " + targets);
    }

    @Test
    @DisplayName("a run that configures targets mutates exactly those that exist")
    void configuredTargetsWin(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "src/Ledger.php", "<?php\nclass Ledger {}\n");
        PhpTestContexts.write(root, "src/Fee.php", "<?php\nclass Fee {}\n");
        VerificationContext context = contextWithTargets(root, run, List.of("src/Ledger.php", "src/Absent.php"));

        List<Path> targets = PhpMutationTester.targetFiles(context);

        assertEquals(List.of(root.resolve("src/Ledger.php")), targets,
                "a configured target that is not there cannot be mutated, and is not invented: " + targets);
    }

    @Test
    @DisplayName("no more than twenty files are ever mutated")
    void theFileBoundIsRespected(@TempDir Path root, @TempDir Path run) throws IOException {
        for (int i = 0; i < 25; i++) {
            PhpTestContexts.write(root, "src/File" + i + ".php", "<?php\n$total = $a + $b;\n");
        }
        VerificationContext context = PhpTestContexts.context(root, run);

        assertEquals(20, PhpMutationTester.targetFiles(context).size(),
                "a large project must not be able to dominate a run");
    }

    // ------------------------------------------------------------------ helpers

    /** @return a context whose mutation configuration names the given target files. */
    private static VerificationContext contextWithTargets(Path root, Path run, List<String> targets) {
        ProjectInfo info = PhpTestContexts.projectInfo(root);
        VerificationConfig defaults = VerificationConfig.defaults();
        VerificationConfig config = new VerificationConfig(
                defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                new VerificationConfig.Mutation(true, "php-text", defaults.mutation().timeout(), new ArrayList<>(targets)),
                defaults.propertyTesting(), defaults.staticAnalysis(), defaults.security(), defaults.llm(),
                defaults.report(), defaults.sandbox(), List.of());
        ProjectRef projectRef = info.project();
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(projectRef)
                .projectInfo(info)
                .config(config)
                .workspace(WorkspaceManager.prepare(projectRef, run, WorkspaceManager.Mode.IN_PLACE))
                .artifacts(new ArtifactStore(run))
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .sandbox(new LocalProcessSandbox(List.of(root, run)))
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }
}

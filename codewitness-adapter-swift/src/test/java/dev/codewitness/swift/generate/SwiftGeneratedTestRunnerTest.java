package dev.codewitness.swift.generate;

import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.swift.SwiftTestReportParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attribution of a generated-class result from XCTest's console.
 *
 * <p>The engine matches a parsed case to a generated test by exact suite name, and the two XCTest
 * dialects spell that name differently: Apple's XCTest prints {@code Module.Class}, while
 * swift-corelibs-xctest on Linux prints {@code Class} alone. These tests pin the normalisation that makes
 * the two agree, because reading only one spelling is what left every scenario {@code UNVERIFIED} on a
 * Linux runner while the same fixture passed on macOS.
 */
class SwiftGeneratedTestRunnerTest {

    /** The batch the generator records for SC-ROLLING under the fixture's test target. */
    private static GeneratedTest rolling() {
        return new GeneratedTest(
                "LedgerTests.CwScSC_ROLLINGTests",
                "// generated",
                "testCwSearch",
                List.of("R-001"),
                List.of("OB-001"),
                "Two withdrawals across midnight",
                42L,
                null,
                true);
    }

    @Test
    @DisplayName("a corelibs suite is rewritten to the module-qualified name the batch recorded")
    void normalisesCorelibsSuite() {
        TestResult parsed = SwiftTestReportParser.parse("""
                Test Case 'CwScSC_ROLLINGTests.testCwSearch' started at 2026-10-12 07:14:06.148
                /work/Tests/LedgerTests/Generated/CwScSC_ROLLINGTests.swift:44: error: CwScSC_ROLLINGTests.testCwSearch : failed - CODEWITNESS_COUNTEREXAMPLE|case=0|seed=42
                Test Case 'CwScSC_ROLLINGTests.testCwSearch' failed (0.010 seconds)
                """, null, "linux");

        TestResult normalized = SwiftGeneratedTestRunner.normalizeSuites(parsed, List.of(rolling()));

        assertEquals("LedgerTests.CwScSC_ROLLINGTests", normalized.cases().getFirst().suite(),
                "the engine looks a generated test up by the exact recorded name");
        assertEquals(1, normalized.failed());
        assertTrue(normalized.cases().getFirst().failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"));
    }

    @Test
    @DisplayName("a suite the batch never wrote is left exactly as the runner reported it")
    void leavesForeignSuiteAlone() {
        TestResult parsed = SwiftTestReportParser.parse("""
                Test Case 'WithdrawalLedgerTests.testFeeIsAddedToTheRecordedTotal' started at 2026-10-12 07:14:06.150
                Test Case 'WithdrawalLedgerTests.testFeeIsAddedToTheRecordedTotal' passed (0.001 seconds)
                """, null, "linux");

        TestResult normalized = SwiftGeneratedTestRunner.normalizeSuites(parsed, List.of(rolling()));

        assertEquals("WithdrawalLedgerTests", normalized.cases().getFirst().suite());
    }

    @Test
    @DisplayName("attribution accepts either spelling, and a batch nothing names is refused")
    void attributionAcceptsBothSpellings() {
        TestResult corelibs = SwiftTestReportParser.parse("""
                Test Case 'CwScSC_ROLLINGTests.testCwSearch' started at 2026-10-12 07:14:06.148
                Test Case 'CwScSC_ROLLINGTests.testCwSearch' passed (0.010 seconds)
                """, null, "linux");
        assertTrue(SwiftGeneratedTestRunner.attributed(corelibs, List.of(rolling())),
                "an unnormalised Linux report still names the generated class by its simple name");

        TestResult unrelated = SwiftTestReportParser.parse("""
                Test Case 'SomethingElse.testOther' started at 2026-10-12 07:14:06.148
                Test Case 'SomethingElse.testOther' passed (0.010 seconds)
                """, null, "linux");
        assertFalse(SwiftGeneratedTestRunner.attributed(unrelated, List.of(rolling())),
                "a report that names none of the batch's classes is not evidence about the batch");
    }

    @Test
    @DisplayName("a case's simple name is its class name without a module prefix")
    void readsSimpleNames() {
        assertEquals("CwScSC_ROLLINGTests",
                SwiftGeneratedTestRunner.simpleName("LedgerTests.CwScSC_ROLLINGTests"));
        assertEquals("CwScSC_ROLLINGTests",
                SwiftGeneratedTestRunner.simpleName("CwScSC_ROLLINGTests"));
        assertEquals("", SwiftGeneratedTestRunner.simpleName(null));
    }
}

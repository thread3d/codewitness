package dev.codewitness.pascal.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Pascal mutation profile tells the shared tester what is code, what is a test, and what did not build. */
class PascalMutationTesterTest {

    private final PascalMutationTester.PascalMutationProfile profile =
            new PascalMutationTester.PascalMutationProfile();

    @Test
    @DisplayName("a test directory and a test file name both mark a path as tests")
    void recognisesTestPaths() {
        assertTrue(profile.isTestPath("tests/ledger.pas"));
        assertTrue(profile.isTestPath("tests/nested/ledger.pas"));
        assertTrue(profile.isTestPath("src/ledger_tests.pas"));
        assertTrue(profile.isTestPath("spec/ledger.pas"));
        assertFalse(profile.isTestPath("src/ledger.pas"));
    }

    @Test
    @DisplayName("compiler output is non-viable and a test result is not")
    void distinguishesCompileFailures() {
        assertNotNull(profile.nonViableReason("ledger.pas(3,1) Error: Identifier not found \"X\""));
        assertNotNull(profile.nonViableReason("Fatal: Compilation aborted"));
        assertNotNull(profile.nonViableReason("fpc: not found"));
        assertNull(profile.nonViableReason("Number of run tests: 2"));
        assertNull(profile.nonViableReason("all tests passed"));
    }

    @Test
    @DisplayName("the profile names Pascal and describes the operator set in a sentence")
    void describesItself() {
        assertTrue(profile.languageName().contains("Pascal"));
        assertEquals("pascal", profile.languageId().id());
        assertNotNull(profile.lexis());
        assertTrue(profile.operatorSetDescription().endsWith("."));
        assertEquals("pascal-text-mutation", profile.mutationTesterId());
        assertEquals("pascal-text", profile.mutationReportToolId());
    }
}

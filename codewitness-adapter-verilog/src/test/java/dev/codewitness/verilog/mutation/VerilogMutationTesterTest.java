package dev.codewitness.verilog.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Verilog half of the shared mutation loop. */
class VerilogMutationTesterTest {

    private final VerilogMutationTester.VerilogMutationProfile profile =
            new VerilogMutationTester.VerilogMutationProfile();

    @Test
    @DisplayName("a testbench is a test path and a design file is not")
    void testPaths() {
        assertTrue(profile.isTestPath("tb/ledger_tb.v"));
        assertTrue(profile.isTestPath("rtl/ledger_tb.v"));
        assertTrue(profile.isTestPath("test/ledger.v"));
        assertFalse(profile.isTestPath("rtl/ledger.v"));
        assertFalse(profile.isTestPath("src/main.v"));
    }

    @Test
    @DisplayName("an Icarus compile error is non-viable; a passing or failing simulation is not")
    void nonViableReason() {
        assertNotNull(profile.nonViableReason("ledger.v:8: syntax error\n"));
        assertNotNull(profile.nonViableReason("ledger.v:3: error: Unable to bind wire `x'\n"));
        assertNotNull(profile.nonViableReason("Elaboration failed\n"));
        assertNotNull(profile.nonViableReason("sh: iverilog: command not found\n"));
        assertNull(profile.nonViableReason("PASS: the ledger is correct\n"));
        assertNull(profile.nonViableReason("FAIL: the ledger is wrong\n"));
        assertNull(profile.nonViableReason(""));
    }

    @Test
    @DisplayName("the profile names the language, the lexis and the operator set")
    void profileFacts() {
        assertTrue(profile.languageName().contains("Verilog"));
        assertNotNull(profile.lexis());
        assertTrue(profile.operatorSetDescription().endsWith("."));
    }
}

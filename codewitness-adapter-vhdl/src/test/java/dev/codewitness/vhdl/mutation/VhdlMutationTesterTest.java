package dev.codewitness.vhdl.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The VHDL half of the shared mutation loop. */
class VhdlMutationTesterTest {

    private final VhdlMutationTester.VhdlMutationProfile profile =
            new VhdlMutationTester.VhdlMutationProfile();

    @Test
    @DisplayName("a testbench is a test path and a design file is not")
    void testPaths() {
        assertTrue(profile.isTestPath("tb/ledger_tb.vhd"));
        assertTrue(profile.isTestPath("rtl/ledger_tb.vhd"));
        assertTrue(profile.isTestPath("test/ledger.vhd"));
        assertFalse(profile.isTestPath("rtl/ledger.vhd"));
        assertFalse(profile.isTestPath("src/main.vhd"));
    }

    @Test
    @DisplayName("a GHDL analysis or elaboration error is non-viable; a simulation failure is a kill")
    void nonViableReason() {
        assertNotNull(profile.nonViableReason("rtl/ledger.vhd:6:3:error: bad assignment\n"));
        assertNotNull(profile.nonViableReason("ghdl:error: elaboration of design unit failed\n"));
        assertNotNull(profile.nonViableReason("sh: ghdl: command not found\n"));
        // A mutant the tests detect is a kill, not a non-viable mutant: GHDL uses `ghdl:error:` for
        // both, so the pattern must not match a simulation failure.
        assertNull(profile.nonViableReason("ghdl:error: assertion failed\n"));
        assertNull(profile.nonViableReason("ghdl:error: report failed\n"));
        assertNull(profile.nonViableReason("ghdl:error: simulation failed\n"));
        assertNull(profile.nonViableReason("ledger_tb.vhd:31:7:@1ns:(report note): PASS: fine\n"));
        assertNull(profile.nonViableReason("ledger_tb.vhd:24:5:@1ns:(assertion error): FAIL: bad\n"));
        assertNull(profile.nonViableReason(""));
    }

    @Test
    @DisplayName("the profile names the language, the lexis and the operator set")
    void profileFacts() {
        assertTrue(profile.languageName().contains("VHDL"));
        assertNotNull(profile.lexis());
        assertTrue(profile.operatorSetDescription().endsWith("."));
    }
}

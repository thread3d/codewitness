package dev.codewitness.python.mutation;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.model.MutationReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the AST mutation script's line protocol. */
class PythonMutationTesterTest {

    private static ProcessResult result(String output) {
        return new ProcessResult(List.of("python", "cw_mutation.py"), Path.of("/project"), true, 0,
                output, "", Duration.ofMillis(50), false, null);
    }

    @Test
    @DisplayName("killed, survived and timed-out mutants are separated and survivors carry their location")
    void parsesMutants() {
        MutationReport report = PythonMutationTester.parse(result("""
                CWMUTANT|SURVIVED|ledger.py|12|boundary|Lt -> LtE
                CWMUTANT|KILLED|ledger.py|18|arithmetic|Add -> Sub
                CWMUTANT|TIMEOUT|ledger.py|22|logical|And -> Or
                CWMUTANT|SURVIVED|fee.py|5|boolean|True -> False
                CMSUMMARY|generated=4|killed=1|survived=2|timedOut=1
                """), 2, Path.of("/project"));

        assertTrue(report.measured());
        assertEquals(4, report.generated());
        assertEquals(1, report.killed());
        assertEquals(2, report.survived());
        assertEquals(1, report.timedOut());
        assertEquals(2, report.survivors().size());
        assertEquals("ledger.py", report.survivors().getFirst().className());
        assertEquals(12, report.survivors().getFirst().line());
        assertTrue(report.survivors().getFirst().description().contains("Lt -> LtE"));
    }

    @Test
    @DisplayName("a run that mutated nothing is not measured, never a perfect score")
    void emptyRunIsNotMeasured() {
        MutationReport report = PythonMutationTester.parse(result("CMSUMMARY|generated=0|killed=0|survived=0|timedOut=0\n"),
                1, Path.of("/project"));

        assertTrue(!report.measured());
        assertTrue(report.note().contains("no mutatable statement"));
    }
}

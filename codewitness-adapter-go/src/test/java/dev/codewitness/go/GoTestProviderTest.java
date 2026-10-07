package dev.codewitness.go;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The replay command must name the test it replays, as a regex that selects exactly that name. */
class GoTestProviderTest {

    @Test
    @DisplayName("a test name containing regex metacharacters is quoted so the replay selects it literally")
    void replayCommandQuotesMetacharacters(@TempDir Path root, @TempDir Path runDirectory) {
        VerificationContext context = GoTestContexts.context(root, runDirectory);
        TestCaseResult testCase = TestCaseResult.failed("example.com/sample/ledger",
                "TestLedger/subtest(a+b)[1]", Duration.ZERO, "t.Fatal", "boom", null);

        List<String> command = new GoTestProvider().replayCommand(context, testCase);

        int run = command.indexOf("-run");
        assertTrue(run >= 0, command.toString());
        assertEquals("^TestLedger/subtest\\(a\\+b\\)\\[1\\]$", command.get(run + 1));
    }

    @Test
    @DisplayName("quoting leaves an ordinary test name, and the suite argument, untouched")
    void replayCommandLeavesPlainNamesAlone(@TempDir Path root, @TempDir Path runDirectory) {
        VerificationContext context = GoTestContexts.context(root, runDirectory);
        TestCaseResult testCase = TestCaseResult.passed("example.com/sample/ledger",
                "TestLedgerRecordsFee", Duration.ofMillis(4));

        List<String> command = new GoTestProvider().replayCommand(context, testCase);

        int run = command.indexOf("-run");
        assertEquals("^TestLedgerRecordsFee$", command.get(run + 1));
        assertEquals("example.com/sample/ledger", command.get(command.size() - 1));
    }

    @Test
    @DisplayName("quoteMeta escapes Go's metacharacters and nothing else")
    void quoteMetaEscapesGoMetacharacters() {
        assertEquals("\\.\\+\\*\\(\\)\\|\\[\\]\\{\\}\\^\\$", GoTestProvider.quoteMeta(".+*()|[]{}^$"));
        assertEquals("TestX/case_1-2", GoTestProvider.quoteMeta("TestX/case_1-2"));
    }
}

package dev.codewitness.core.engine;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.Severity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A baseline decides what a change introduced. These tests pin that identity is the finding's fingerprint,
 * that a read failure is loud rather than silently meaning "no baseline", and that a file which is not a
 * CodeWitness report is rejected instead of being treated as empty.
 */
class FindingBaselineTest {

    @TempDir
    Path temp;

    private static final Finding FINDING = new Finding("python-heuristics", "CW-PY-SEC-008", Severity.HIGH,
            FindingCategory.SECURITY, "SQL built by concatenation", "src/db.py", 2, "app", Map.of());

    private Path writeReport(String body) throws IOException {
        Path file = temp.resolve("report.json");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aFindingFromTheBaselineIsRecognised() throws IOException {
        Path report = writeReport("""
                {"runId":"CW-1","findings":[
                  {"rule":"CW-PY-SEC-008","message":"SQL built by concatenation","fingerprint":"%s"}
                ]}
                """.formatted(escape(FINDING.fingerprint())));

        FindingBaseline baseline = FindingBaseline.load(report);

        assertEquals(1, baseline.size());
        assertTrue(baseline.contains(FINDING), "the same finding must be recognised");
        assertFalse(baseline.contains(new Finding("python-heuristics", "CW-PY-SEC-008", Severity.HIGH,
                        FindingCategory.SECURITY, "a different message", "src/db.py", 2, "app", Map.of())),
                "a different message is a different finding");
    }

    @Test
    void anEmptyBaselineTreatsEverythingAsNew() {
        FindingBaseline baseline = FindingBaseline.empty();

        assertEquals(0, baseline.size());
        assertFalse(baseline.contains(FINDING));
    }

    @Test
    void aFileThatIsNotAReportIsRejected() throws IOException {
        Path report = writeReport("{\"unrelated\":true}");

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> FindingBaseline.load(report));
        assertTrue(rejected.getMessage().contains("does not look like"), rejected.getMessage());
    }

    @Test
    void malformedJsonIsRejected() throws IOException {
        Path report = writeReport("{not json");

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> FindingBaseline.load(report));
        assertTrue(rejected.getMessage().contains("not valid JSON"), rejected.getMessage());
    }

    @Test
    void aMissingFileIsRejectedWithThePath() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> FindingBaseline.load(temp.resolve("absent.json")));

        assertTrue(rejected.getMessage().contains("absent.json"), rejected.getMessage());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

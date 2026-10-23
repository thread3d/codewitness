package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codewitness.core.diagnostics.SelfIssueCategory;
import dev.codewitness.core.diagnostics.SelfIssueJournal;
import dev.codewitness.core.diagnostics.SelfIssueRecord;
import dev.codewitness.core.diagnostics.SelfIssueSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code codewitness issues}: the entry point to the journal the tool writes about itself. */
class IssuesCommandTest {

    @TempDir
    Path project;

    @Test
    @DisplayName("an empty journal is reported as empty rather than as a failure")
    void emptyJournal() {
        CliTestSupport.Result result = CliTestSupport.run("issues", "--project", project.toString());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("No open self-issues"), result.stdout());
    }

    @Test
    @DisplayName("a recorded issue is listed with its id, context and fix command")
    void listsRecordedIssues() {
        SelfIssueRecord issue = recordIssue();

        CliTestSupport.Result result = CliTestSupport.run("issues", "--project", project.toString());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains(issue.id()), result.stdout());
        assertTrue(result.stdout().contains("phase broke"), result.stdout());
        assertTrue(result.stdout().contains("--resolve " + issue.id()), result.stdout());
        assertTrue(result.stdout().contains("run=CW-2026-01-01-TEST"), result.stdout());
    }

    @Test
    @DisplayName("--json is a machine-readable view of the same journal")
    void jsonView() throws IOException {
        SelfIssueRecord issue = recordIssue();

        CliTestSupport.Result result = CliTestSupport.run("issues", "--project", project.toString(), "--json");

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        JsonNode document = new ObjectMapper().readTree(result.stdout());
        assertEquals(1, document.path("open").asInt(), result.stdout());
        assertEquals(1, document.path("issues").size(), result.stdout());
        assertEquals(issue.id(), document.path("issues").get(0).path("id").asText());
        assertEquals("PHASE_FAILURE", document.path("issues").get(0).path("category").asText());
    }

    @Test
    @DisplayName("--resolve closes an issue and --all keeps it visible")
    void resolveAndIncludeResolved() {
        SelfIssueRecord issue = recordIssue();

        CliTestSupport.Result resolved = CliTestSupport.run("issues", "--project", project.toString(),
                "--resolve", issue.id(), "--note", "fixed");

        assertEquals(0, resolved.exitCode(), resolved.stdout() + resolved.stderr());
        assertTrue(resolved.stdout().contains("Resolved " + issue.id()), resolved.stdout());
        assertTrue(resolved.stdout().contains("No open self-issues"), resolved.stdout());

        CliTestSupport.Result all = CliTestSupport.run("issues", "--project", project.toString(), "--all");
        assertTrue(all.stdout().contains(issue.id()), all.stdout());
        assertTrue(all.stdout().contains("[resolved]"), all.stdout());
    }

    @Test
    @DisplayName("an unknown id to resolve is a not-found error, not a silent success")
    void resolvingAnUnknownIssueFails() {
        CliTestSupport.Result result = CliTestSupport.run("issues", "--project", project.toString(),
                "--resolve", "CWI-00000000");

        assertEquals(2, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stderr().contains("no recorded self-issue"), result.stderr());
    }

    private SelfIssueRecord recordIssue() {
        SelfIssueJournal journal = SelfIssueJournal.open(
                SelfIssueJournal.defaultDirectory(project));
        RuntimeException error = new IllegalStateException("phase broke");
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("dev.codewitness.core.engine.VerificationEngine", "runPhase",
                        "VerificationEngine.java", 1268)
        });
        SelfIssueRecord issue = SelfIssueRecord.create(SelfIssueCategory.PHASE_FAILURE,
                SelfIssueSeverity.HIGH, "phase broke", error,
                Map.of(SelfIssueRecord.PHASE, "build",
                        SelfIssueRecord.RUN_ID, "CW-2026-01-01-TEST",
                        SelfIssueRecord.PROJECT, project.toString()));
        journal.record(issue);
        return issue;
    }
}

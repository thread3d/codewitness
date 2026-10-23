package dev.codewitness.core.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The self-issue journal: stable ids, aggregation, the digest, and resolutions that can regress. */
class SelfIssueJournalTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("an occurrence is written to both the machine log and the human digest")
    void recordsBothFiles() throws IOException {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));

        journal.record(issue("phase broke", SelfIssueCategory.PHASE_FAILURE, Map.of(
                SelfIssueRecord.PHASE, "build",
                SelfIssueRecord.RUN_ID, "CW-2026-01-01-TEST",
                SelfIssueRecord.COMMAND, "codewitness verify .",
                SelfIssueRecord.PROJECT, "/tmp/project")));

        assertTrue(Files.isRegularFile(journal.jsonlPath()));
        assertTrue(Files.isRegularFile(journal.markdownPath()));
        String markdown = Files.readString(journal.markdownPath(), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("# CodeWitness self-issue log"), markdown);
        assertTrue(markdown.contains("phase broke"), markdown);
        assertTrue(markdown.contains("CWI-"), markdown);
        assertTrue(markdown.contains("CW-2026-01-01-TEST"), markdown);
        assertTrue(markdown.contains("fix CWI-"), "the digest must explain how to ask for a fix");
    }

    @Test
    @DisplayName("the same fault recorded twice is one issue with two occurrences")
    void identicalFaultsAggregate() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));

        journal.record(issue("phase broke", SelfIssueCategory.PHASE_FAILURE, Map.of()));
        journal.record(issue("phase broke", SelfIssueCategory.PHASE_FAILURE, Map.of()));

        assertEquals(2, journal.records().size(), "every observation is kept");
        List<SelfIssueSummary> summaries = journal.summaries(true);
        assertEquals(1, summaries.size());
        assertEquals(2, summaries.getFirst().occurrences());
        assertEquals(1, journal.openIssueCount());
    }

    @Test
    @DisplayName("different messages at the same source frame are one issue, because the frame is the identity")
    void messagesAtTheSameSourceFrameAggregate() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));

        journal.record(issue("first failure", SelfIssueCategory.PHASE_FAILURE, Map.of()));
        journal.record(issue("second failure", SelfIssueCategory.PHASE_FAILURE, Map.of()));

        // Messages often carry a run directory or a temp path. Hashing them would create a fresh entry on
        // every run for the same defect; the source frame plus the exception type is the durable identity.
        assertEquals(1, journal.openIssueCount());
        assertEquals("second failure", journal.summaries(true).getFirst().message());
    }

    @Test
    @DisplayName("different source frames are different issues even with the same message")
    void differentSourceFramesAreDifferentIssues() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));

        journal.record(issueAt("VerificationEngine", "verify", "failure", Map.of()));
        journal.record(issueAt("WorkspaceManager", "prepare", "failure", Map.of()));

        assertEquals(2, journal.openIssueCount());
    }

    @Test
    @DisplayName("a resolution hides an issue without rewriting its history")
    void resolutionMarksTheIssueResolved() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));
        SelfIssueRecord issue = issue("phase broke", SelfIssueCategory.PHASE_FAILURE, Map.of());
        journal.record(issue);

        assertTrue(journal.resolve(issue.id(), "fixed in a later commit"));
        assertFalse(journal.resolve("CWI-00000000", "nothing"));
        assertTrue(journal.summaries(false).isEmpty());
        List<SelfIssueSummary> all = journal.summaries(true);
        assertEquals(1, all.size());
        assertTrue(all.getFirst().resolved());
        assertEquals("fixed in a later commit", all.getFirst().resolutionNote());
        assertTrue(journal.renderMarkdown().contains("## Resolved issues"));
    }

    @Test
    @DisplayName("a fault that recurs after its resolution is open again")
    void recurrenceReopensAResolvedIssue() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));
        SelfIssueRecord issue = issue("phase broke", SelfIssueCategory.PHASE_FAILURE, Map.of());
        journal.record(issue);
        assertTrue(journal.resolve(issue.id(), "fixed"));

        // A later occurrence, with a timestamp after the resolution, is what a regression looks like.
        journal.record(new SelfIssueRecord(
                issue.schemaVersion(), issue.id(), Instant.now().plusSeconds(5).toString(),
                issue.severity(), issue.category(), issue.message(), issue.errorType(), issue.detail(),
                issue.phase(), issue.strategy(), issue.adapter(), issue.requirement(), issue.runId(),
                issue.command(), issue.project(), issue.codewitnessVersion(), issue.javaVersion(),
                issue.thread(), issue.source()));

        assertEquals(1, journal.openIssueCount(), "a resolved fault that happens again is not fixed");
        assertTrue(journal.summaries(true).getFirst().occurrences() == 2);
    }

    @Test
    @DisplayName("a corrupt line does not make the rest of the journal unreadable")
    void corruptLineIsSkipped() throws IOException {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));
        journal.record(issue("phase broke", SelfIssueCategory.PHASE_FAILURE, Map.of()));
        Files.writeString(journal.jsonlPath(), "{not json}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertEquals(1, journal.records().size());
        assertEquals(1, journal.openIssueCount());
    }

    @Test
    @DisplayName("a disabled journal writes nothing and pretends nothing")
    void disabledJournalIsInert() {
        SelfIssueJournal journal = SelfIssueJournal.disabled();
        journal.record(issue("phase broke", SelfIssueCategory.PHASE_FAILURE, Map.of()));

        assertFalse(journal.isEnabled());
        assertNull(journal.markdownPath());
        assertTrue(journal.records().isEmpty());
        assertTrue(journal.summaries(true).isEmpty());
    }

    @Test
    @DisplayName("the default location is below the project's codewitness directory")
    void defaultLocationIsProjectScoped() {
        Path project = tmp.resolve("project");
        assertEquals(project.toAbsolutePath().normalize()
                        .resolve(".codewitness").resolve("self-issues"),
                SelfIssueJournal.defaultDirectory(project));
    }

    /**
     * A record with a deterministic stack frame.
     *
     * <p>The real call sites throw from adapter or engine code; a synthetic frame makes the identifier
     * and source assertions independent of where this test happens to run.
     */
    private static SelfIssueRecord issue(
            String message, SelfIssueCategory category, Map<String, String> context) {
        return issueAt("VerificationEngine", "verify", message, category, context);
    }

    private static SelfIssueRecord issueAt(
            String className, String method, String message, Map<String, String> context) {
        return issueAt(className, method, message, SelfIssueCategory.PHASE_FAILURE, context);
    }

    private static SelfIssueRecord issueAt(
            String className, String method, String message, SelfIssueCategory category,
            Map<String, String> context) {
        RuntimeException error = new RuntimeException(message);
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("dev.codewitness.core.engine." + className, method,
                        className + ".java", 218)
        });
        return SelfIssueRecord.create(category, SelfIssueSeverity.HIGH, message, error, context);
    }
}

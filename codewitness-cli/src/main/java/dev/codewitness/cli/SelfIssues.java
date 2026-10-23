package dev.codewitness.cli;

import dev.codewitness.core.diagnostics.SelfIssueCategory;
import dev.codewitness.core.diagnostics.SelfIssueJournal;
import dev.codewitness.core.diagnostics.SelfIssueRecord;
import dev.codewitness.core.diagnostics.SelfIssueSeverity;

import java.nio.file.Path;
import java.util.Map;

/**
 * The process-wide self-issue journal the command line writes to.
 *
 * <p>The engine receives its recorder explicitly, which keeps it testable. Two places cannot do that:
 * picocli's execution-exception handler and the command wrappers, which only have the process around
 * them. A small holder is enough for both; it is installed before a command starts touching a project
 * and replaced as soon as the project directory is known, so an issue raised by {@code verify
 * /some/other/project} lands in that project's journal rather than in whatever directory happened to
 * be the working directory.
 */
final class SelfIssues {

    private static volatile SelfIssueJournal journal = SelfIssueJournal.forProject(Path.of(""));
    private static volatile String command = "codewitness";

    private SelfIssues() {
    }

    /** Remembers the command line and points the journal at the working directory until a project is known. */
    static void begin(String[] args) {
        command = args == null || args.length == 0
                ? "codewitness"
                : "codewitness " + String.join(" ", args);
        journal = SelfIssueJournal.forProject(Path.of(""));
    }

    /** Points subsequent records at the journal for a project. */
    static void install(Path projectRoot) {
        journal = SelfIssueJournal.forProject(projectRoot);
    }

    /** @return the journal currently receiving records. */
    static SelfIssueJournal journal() {
        return journal;
    }

    /** @return the command line currently running, for the recorded context. */
    static String command() {
        return command;
    }

    /**
     * Records an exception that escaped a command.
     *
     * @param context short description of what was running
     * @param error   exception that escaped
     */
    static void recordInternalFailure(String context, Throwable error) {
        journal.record(SelfIssueCategory.INTERNAL_ERROR, SelfIssueSeverity.CRITICAL,
                "Unhandled internal failure during " + context + ": " + error,
                error,
                Map.of(SelfIssueRecord.PHASE, "cli",
                        SelfIssueRecord.COMMAND, command));
    }

    /** @return a one-line pointer to the journal, or {@code null} when it is disabled. */
    static String pointer() {
        SelfIssueJournal current = journal;
        return current.isEnabled()
                ? "codewitness: self-issue log: " + current.markdownPath()
                : null;
    }
}

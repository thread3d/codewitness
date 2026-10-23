package dev.codewitness.cli;

import dev.codewitness.core.diagnostics.SelfIssueJournal;
import dev.codewitness.core.diagnostics.SelfIssueSummary;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code codewitness issues} - reads the journal of faults CodeWitness found in itself.
 *
 * <p>This is the human and agent entry point to the self-issue log. It reads the journal rather than
 * running anything, so it works after the verified project has been cleaned and after the original
 * terminal is gone. The digest is a Markdown file; this command is the quick list, the JSON form for
 * scripts, and the place to record that an issue has been fixed.
 */
@Command(name = "issues",
        description = "List, or mark resolved, faults CodeWitness recorded in itself.")
public final class IssuesCommand implements Callable<Integer> {

    @Option(names = "--project", paramLabel = "<dir>",
            description = "Project whose self-issue journal to read (default '.').")
    private String project = ".";

    @Option(names = "--dir", paramLabel = "<dir>", description = "Journal directory, if not the default.")
    private Path directory;

    @Option(names = "--json", description = "Print the journal as JSON.")
    private boolean printJson;

    @Option(names = "--all", description = "Include resolved issues.")
    private boolean includeResolved;

    @Option(names = "--resolve", paramLabel = "<id>", split = ",",
            description = "Mark an issue id resolved, for example CWI-1A2B3C4D. Repeatable.")
    private List<String> resolveIds = List.of();

    @Option(names = "--note", paramLabel = "<text>", description = "Note to store with --resolve.")
    private String note;

    @Override
    public Integer call() {
        Path root = Path.of(project).toAbsolutePath().normalize();
        SelfIssues.install(root);
        Path journalDirectory = directory != null
                ? directory.toAbsolutePath().normalize()
                : SelfIssueJournal.defaultDirectory(root);
        SelfIssueJournal journal = SelfIssueJournal.open(journalDirectory);

        int unresolved = 0;
        for (String id : resolveIds == null ? List.<String>of() : resolveIds) {
            if (journal.resolve(id, note)) {
                System.out.println("Resolved " + id.trim().toUpperCase(java.util.Locale.ROOT));
            } else {
                System.err.println("codewitness: no recorded self-issue with id " + id
                        + " (looked in " + journal.jsonlPath() + ")");
                unresolved++;
            }
        }

        List<SelfIssueSummary> issues = journal.summaries(includeResolved);
        if (printJson) {
            System.out.println(journal.renderJson(includeResolved));
            return unresolved == 0 ? 0 : 2;
        }

        System.out.println("CodeWitness self-issue log");
        System.out.println("  journal    " + journal.markdownPath());
        System.out.println("  records    " + journal.jsonlPath());
        System.out.println("  open       " + journal.openIssueCount());
        System.out.println("  resolved   " + journal.resolvedIssueCount());
        System.out.println();
        if (issues.isEmpty()) {
            System.out.println(includeResolved
                    ? "No self-issues have been recorded here."
                    : "No open self-issues. Use --all to include resolved ones.");
            return unresolved == 0 ? 0 : 2;
        }

        for (SelfIssueSummary issue : issues) {
            System.out.printf("%-14s %-8s %-17s %2dx  last %s%s%n",
                    issue.id(), issue.severity(), issue.category(), issue.occurrences(),
                    issue.lastSeen(), issue.resolved() ? "  [resolved]" : "");
            System.out.println("  " + oneLine(issue.message()));
            if (issue.source() != null) {
                System.out.println("  where   " + issue.source());
            }
            String context = context(issue);
            if (!context.isEmpty()) {
                System.out.println("  context " + context);
            }
            if (issue.command() != null) {
                System.out.println("  command " + issue.command());
            }
            if (!issue.resolved()) {
                System.out.println("  fix     codewitness issues --project " + project
                        + " --resolve " + issue.id() + " --note \"...\"");
            }
            System.out.println();
        }
        System.out.println("Full digest: " + journal.markdownPath());
        System.out.println("Machine form: codewitness issues --json"
                + (includeResolved ? " --all" : "") + "  (--all includes resolved issues)");
        return unresolved == 0 ? 0 : 2;
    }

    private static String context(SelfIssueSummary issue) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        add(parts, "phase", issue.phase());
        add(parts, "strategy", issue.strategy());
        add(parts, "adapter", issue.adapter());
        add(parts, "requirement", issue.requirement());
        add(parts, "run", issue.runId());
        add(parts, "project", issue.project());
        return String.join(" · ", parts);
    }

    private static void add(java.util.List<String> parts, String key, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(key + "=" + value);
        }
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}

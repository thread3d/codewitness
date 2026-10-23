package dev.codewitness.core.diagnostics;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A durable journal of faults CodeWitness found in itself.
 *
 * <p>Two files are kept under one directory:
 *
 * <ul>
 *   <li>{@code issues.jsonl} - one row per occurrence. Append-only, machine-readable, and the source
 *       of truth;</li>
 *   <li>{@code SELF-ISSUES.md} - a regenerated human digest, grouped by issue id, with the command,
 *       run, project and stack trace needed to reproduce it.</li>
 * </ul>
 *
 * <p>The journal is deliberately outside the run directory. A tool defect is a fact about
 * CodeWitness that outlives the run that exposed it, and the user's stated workflow is to come back
 * later and ask for these to be fixed. Keeping the journal with the project (under
 * {@code .codewitness/self-issues}) means it is still there when that run's artefacts have been
 * cleaned.
 *
 * <p>Every write is best effort. A read-only or missing directory must not turn a verification
 * finding into a crash, so {@link #record(SelfIssueRecord)} never throws; it records the I/O failure
 * in {@link #lastFailure()} for callers that want to print it.
 */
public final class SelfIssueJournal implements SelfIssueRecorder {

    /** Machine-readable, append-only occurrence log. */
    public static final String ISSUES_FILE = "issues.jsonl";
    /** Human-readable digest regenerated after every occurrence. */
    public static final String MARKDOWN_FILE = "SELF-ISSUES.md";
    /** Append-only resolution log. */
    public static final String RESOLUTIONS_FILE = "resolved.jsonl";
    /** Directory, below the project's output root, that holds the journal. */
    public static final String DIRECTORY_NAME = "self-issues";

    /** Environment variable that relocates the journal. */
    public static final String DIRECTORY_ENVIRONMENT_VARIABLE = "CODEWITNESS_SELF_ISSUES_DIR";
    /** Environment variable that disables (or re-enables) the journal. */
    public static final String ENABLED_ENVIRONMENT_VARIABLE = "CODEWITNESS_SELF_ISSUES";
    /** System property equivalent of {@link #DIRECTORY_ENVIRONMENT_VARIABLE}. */
    public static final String DIRECTORY_PROPERTY = "codewitness.selfIssues.dir";
    /** System property equivalent of {@link #ENABLED_ENVIRONMENT_VARIABLE}. */
    public static final String ENABLED_PROPERTY = "codewitness.selfIssues.enabled";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, false);

    private final Path directory;
    private final Object lock = new Object();
    private volatile String lastFailure;

    private SelfIssueJournal(Path directory) {
        this.directory = directory;
    }

    /**
     * Opens a journal in a directory, creating it lazily when the first issue is recorded.
     *
     * @param directory journal directory; {@code null} is treated as disabled
     * @return an enabled journal unless {@code directory} is {@code null}
     */
    public static SelfIssueJournal open(Path directory) {
        return new SelfIssueJournal(directory == null ? null : directory.toAbsolutePath().normalize());
    }

    /** @return a journal that discards every record, for embedders and tests. */
    public static SelfIssueJournal disabled() {
        return new SelfIssueJournal(null);
    }

    /**
     * Resolves the journal for a project, honouring the environment and system-property overrides.
     *
     * @param projectRoot project being verified, used as the default location
     * @return the journal for this project, disabled only when explicitly turned off
     */
    public static SelfIssueJournal forProject(Path projectRoot) {
        if (!enabledByDefault()) {
            return disabled();
        }
        return open(defaultDirectory(projectRoot));
    }

    /**
     * @param projectRoot project being verified
     * @return where the journal lives unless {@value #DIRECTORY_ENVIRONMENT_VARIABLE} or
     *     {@value #DIRECTORY_PROPERTY} says otherwise
     */
    public static Path defaultDirectory(Path projectRoot) {
        String property = System.getProperty(DIRECTORY_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property.trim()).toAbsolutePath().normalize();
        }
        String environment = System.getenv(DIRECTORY_ENVIRONMENT_VARIABLE);
        if (environment != null && !environment.isBlank()) {
            return Path.of(environment.trim()).toAbsolutePath().normalize();
        }
        return projectRoot.toAbsolutePath().normalize().resolve(".codewitness").resolve(DIRECTORY_NAME);
    }

    /** @return whether the journal is enabled unless explicitly turned off. */
    public static boolean enabledByDefault() {
        String property = System.getProperty(ENABLED_PROPERTY);
        if (property != null && !property.isBlank()) {
            return !isOff(property);
        }
        String environment = System.getenv(ENABLED_ENVIRONMENT_VARIABLE);
        if (environment != null && !environment.isBlank()) {
            return !isOff(environment);
        }
        return true;
    }

    private static boolean isOff(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("false") || normalized.equals("off") || normalized.equals("no")
                || normalized.equals("0");
    }

    /** @return whether this journal writes anything. */
    public boolean isEnabled() {
        return directory != null;
    }

    /** @return the journal directory, or {@code null} when disabled. */
    public Path directory() {
        return directory;
    }

    /** @return the line-oriented occurrence log, or {@code null} when disabled. */
    public Path jsonlPath() {
        return directory == null ? null : directory.resolve(ISSUES_FILE);
    }

    /** @return the human-readable digest, or {@code null} when disabled. */
    public Path markdownPath() {
        return directory == null ? null : directory.resolve(MARKDOWN_FILE);
    }

    /** @return the resolution log, or {@code null} when disabled. */
    public Path resolutionsPath() {
        return directory == null ? null : directory.resolve(RESOLUTIONS_FILE);
    }

    /** @return the last I/O failure the journal swallowed, or {@code null}. */
    public String lastFailure() {
        return lastFailure;
    }

    /**
     * Appends one occurrence and regenerates the digest.
     *
     * <p>Never throws: a journal that cannot be written must not change how the tool's actual work
     * ended.
     */
    @Override
    public void record(SelfIssueRecord issue) {
        if (directory == null || issue == null) {
            return;
        }
        synchronized (lock) {
            try {
                Files.createDirectories(directory);
                String line = MAPPER.writeValueAsString(issue) + System.lineSeparator();
                Files.writeString(jsonlPath(), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writeMarkdownLocked();
                lastFailure = null;
            } catch (IOException | RuntimeException e) {
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /**
     * @param includeResolved whether resolved issues are included
     * @return occurrences aggregated by issue id, newest and most severe first
     */
    public List<SelfIssueSummary> summaries(boolean includeResolved) {
        if (directory == null) {
            return List.of();
        }
        synchronized (lock) {
            List<SelfIssueRecord> records = readRecordsLocked();
            Map<String, Resolution> resolutions = readResolutionsLocked();
            List<SelfIssueSummary> summaries = aggregate(records, resolutions).stream()
                    .filter(summary -> includeResolved || !summary.resolved())
                    .sorted(Comparator
                            .comparing(SelfIssueSummary::resolved)
                            .thenComparing(Comparator.comparingInt(SelfIssueSummary::severityRank)
                                    .reversed())
                            .thenComparing(SelfIssueSummary::lastSeen, Comparator.reverseOrder()))
                    .toList();
            return summaries;
        }
    }

    /** @return every occurrence, in the order it was written. */
    public List<SelfIssueRecord> records() {
        if (directory == null) {
            return List.of();
        }
        synchronized (lock) {
            return List.copyOf(readRecordsLocked());
        }
    }

    /** @return ids of issues that are currently open. */
    public Set<String> openIssueIds() {
        return summaries(false).stream().map(SelfIssueSummary::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** @return number of distinct issues that are open. */
    public int openIssueCount() {
        return summaries(false).size();
    }

    /** @return number of distinct issues with a resolution recorded after their latest occurrence. */
    public int resolvedIssueCount() {
        return (int) summaries(true).stream().filter(SelfIssueSummary::resolved).count();
    }

    /**
     * Marks an issue resolved.
     *
     * <p>A resolution is a fact with a timestamp, not a deletion: if the same issue recurs afterwards
     * it is open again automatically. That distinction matters for a journal meant to survive across
     * fix cycles - rewriting history would hide a regression.
     *
     * @param id   issue id, for example {@code CWI-1A2B3C4D}
     * @param note optional note for whoever reads this later
     * @return {@code false} when the id has never been recorded
     */
    public boolean resolve(String id, String note) {
        if (directory == null || id == null || id.isBlank()) {
            return false;
        }
        String normalized = id.trim().toUpperCase(Locale.ROOT);
        synchronized (lock) {
            try {
                List<SelfIssueRecord> records = readRecordsLocked();
                boolean known = records.stream().anyMatch(record -> record.id().equals(normalized));
                if (!known) {
                    return false;
                }
                Resolution resolution = new Resolution(normalized, Instant.now().toString(),
                        note == null ? "" : note);
                Files.createDirectories(directory);
                Files.writeString(resolutionsPath(),
                        MAPPER.writeValueAsString(resolution) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writeMarkdownLocked();
                lastFailure = null;
                return true;
            } catch (IOException | RuntimeException e) {
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
                return false;
            }
        }
    }

    /** Regenerates the digest from the occurrence log. Best effort, like everything else here. */
    public void writeMarkdown() {
        if (directory == null) {
            return;
        }
        synchronized (lock) {
            try {
                writeMarkdownLocked();
                lastFailure = null;
            } catch (IOException | RuntimeException e) {
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /** @return the digest that {@link #writeMarkdown()} writes. */
    public String renderMarkdown() {
        synchronized (lock) {
            return markdown(readRecordsLocked(), readResolutionsLocked());
        }
    }

    /**
     * @param includeResolved whether resolved issues are included
     * @return a pretty-printed JSON document for scripts and agents
     */
    public String renderJson(boolean includeResolved) {
        synchronized (lock) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("schemaVersion", SelfIssueRecord.SCHEMA_VERSION);
            document.put("journal", String.valueOf(markdownPath()));
            document.put("records", String.valueOf(jsonlPath()));
            document.put("open", openIssueCount());
            document.put("resolved", resolvedIssueCount());
            document.put("issues", summaries(includeResolved));
            try {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
            } catch (IOException e) {
                throw new IllegalStateException("cannot render the self-issue journal", e);
            }
        }
    }

    private void writeMarkdownLocked() throws IOException {
        Files.createDirectories(directory);
        Files.writeString(markdownPath(), markdown(readRecordsLocked(), readResolutionsLocked()),
                StandardCharsets.UTF_8);
    }

    private List<SelfIssueRecord> readRecordsLocked() {
        return readJsonLines(jsonlPath(), SelfIssueRecord.class);
    }

    private Map<String, Resolution> readResolutionsLocked() {
        Map<String, Resolution> latest = new LinkedHashMap<>();
        for (Resolution resolution : readJsonLines(resolutionsPath(), Resolution.class)) {
            Resolution existing = latest.get(resolution.id());
            if (existing == null || instant(resolution.resolvedAt()).isAfter(instant(existing.resolvedAt()))) {
                latest.put(resolution.id(), resolution);
            }
        }
        return latest;
    }

    private <T> List<T> readJsonLines(Path file, Class<T> type) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        List<T> parsed = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    parsed.add(MAPPER.readValue(line, type));
                } catch (RuntimeException | IOException _) {
                    // A truncated or future-schema line must not make the rest of the journal unreadable.
                }
            }
        } catch (IOException | RuntimeException _) {
            return List.of();
        }
        return parsed;
    }

    private static List<SelfIssueSummary> aggregate(
            List<SelfIssueRecord> records, Map<String, Resolution> resolutions) {
        Map<String, List<SelfIssueRecord>> byId = new LinkedHashMap<>();
        for (SelfIssueRecord record : records) {
            byId.computeIfAbsent(record.id(), key -> new ArrayList<>()).add(record);
        }
        List<SelfIssueSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<SelfIssueRecord>> entry : byId.entrySet()) {
            List<SelfIssueRecord> occurrences = entry.getValue().stream()
                    .sorted(Comparator.comparing(SelfIssueRecord::recordedAt))
                    .toList();
            SelfIssueRecord latest = occurrences.getLast();
            SelfIssueRecord first = occurrences.getFirst();
            Resolution resolution = resolutions.get(entry.getKey());
            boolean resolved = resolution != null
                    && !instant(resolution.resolvedAt()).isBefore(instant(latest.recordedAt()));
            summaries.add(new SelfIssueSummary(
                    entry.getKey(),
                    latest.severity(),
                    latest.category(),
                    latest.message(),
                    latest.errorType(),
                    latest.source(),
                    first.recordedAt(),
                    latest.recordedAt(),
                    occurrences.size(),
                    latest.phase(),
                    latest.strategy(),
                    latest.adapter(),
                    latest.requirement(),
                    latest.runId(),
                    latest.command(),
                    latest.project(),
                    latest.detail(),
                    resolved,
                    resolved ? resolution.resolvedAt() : null,
                    resolved ? resolution.note() : null));
        }
        return summaries;
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException _) {
            return Instant.EPOCH;
        }
    }

    private String markdown(
            List<SelfIssueRecord> records, Map<String, Resolution> resolutions) {
        List<SelfIssueSummary> all = aggregate(records, resolutions);
        List<SelfIssueSummary> open = all.stream().filter(summary -> !summary.resolved()).toList();
        List<SelfIssueSummary> resolved = all.stream().filter(SelfIssueSummary::resolved).toList();

        StringBuilder out = new StringBuilder();
        out.append("# CodeWitness self-issue log\n\n");
        out.append("> CodeWitness wrote this file when it found a fault in **itself**. It is not a list of\n");
        out.append("> findings about the project under verification; those are in the run reports. A tool\n");
        out.append("> fault never changes a requirement verdict.\n\n");
        out.append("Generated: ").append(Instant.now()).append("  \n");
        out.append("Journal: `").append(jsonlPath()).append("`  \n");
        out.append("Open: **").append(open.size()).append("** · Resolved: **")
                .append(resolved.size()).append("** · Occurrences: **").append(records.size())
                .append("**\n\n");

        out.append("## How to use this file\n\n");
        out.append("Each entry has a stable id, the source frame, the command and run that exposed it, and\n");
        out.append("the stack trace. To fix one, ask for it by id, for example:\n\n");
        out.append("```text\n");
        out.append("Read .codewitness/self-issues/SELF-ISSUES.md and fix CWI-1A2B3C4D.\n");
        out.append("```\n\n");
        out.append("After fixing, keep the regression test and mark the issue resolved:\n\n");
        out.append("```bash\n");
        out.append("codewitness issues --project . --resolve CWI-1A2B3C4D --note \"fixed by ...\"\n");
        out.append("codewitness issues --project .\n");
        out.append("```\n\n");
        if (open.isEmpty() && resolved.isEmpty()) {
            out.append("No self-issues have been recorded.\n");
            return out.toString();
        }

        out.append("## Open issues\n\n");
        if (open.isEmpty()) {
            out.append("None.\n\n");
        } else {
            out.append(table(open));
            out.append('\n');
            for (SelfIssueSummary summary : sorted(open)) {
                appendIssue(out, summary, false);
            }
        }
        if (!resolved.isEmpty()) {
            out.append("## Resolved issues\n\n");
            out.append(table(resolved));
            out.append('\n');
            for (SelfIssueSummary summary : sorted(resolved)) {
                appendIssue(out, summary, true);
            }
        }
        return out.toString();
    }

    private static List<SelfIssueSummary> sorted(List<SelfIssueSummary> summaries) {
        return summaries.stream()
                .sorted(Comparator.comparingInt(SelfIssueSummary::severityRank).reversed()
                        .thenComparing(SelfIssueSummary::lastSeen, Comparator.reverseOrder()))
                .toList();
    }

    private static String table(List<SelfIssueSummary> summaries) {
        StringBuilder out = new StringBuilder();
        out.append("| Issue | Severity | Category | Last seen | Count | What happened |\n");
        out.append("| --- | --- | --- | --- | --- | --- |\n");
        for (SelfIssueSummary summary : sorted(summaries)) {
            out.append("| ").append(summary.id())
                    .append(" | ").append(summary.severity())
                    .append(" | ").append(summary.category())
                    .append(" | ").append(summary.lastSeen())
                    .append(" | ").append(summary.occurrences())
                    .append(" | ").append(escapeTable(oneLine(summary.message())))
                    .append(" |\n");
        }
        return out.toString();
    }

    private static void appendIssue(StringBuilder out, SelfIssueSummary summary, boolean resolved) {
        out.append("### ").append(summary.id()).append(" — ").append(summary.severity())
                .append(" · ").append(summary.category()).append("\n\n");
        out.append("**").append(oneLine(summary.message())).append("**\n\n");
        out.append("- Seen: ").append(summary.occurrences()).append(" time(s), first `")
                .append(summary.firstSeen()).append("`, last `").append(summary.lastSeen())
                .append("`\n");
        if (summary.source() != null) {
            out.append("- Where: `").append(summary.source()).append("`\n");
        }
        List<String> context = new ArrayList<>();
        addContext(context, "phase", summary.phase());
        addContext(context, "strategy", summary.strategy());
        addContext(context, "adapter", summary.adapter());
        addContext(context, "requirement", summary.requirement());
        addContext(context, "run", summary.runId());
        addContext(context, "project", summary.project());
        if (!context.isEmpty()) {
            out.append("- Context: ").append(String.join(" · ", context)).append('\n');
        }
        if (summary.command() != null) {
            out.append("- Command: `").append(summary.command()).append("`\n");
        }
        if (summary.errorType() != null) {
            out.append("- Exception: `").append(summary.errorType()).append("`\n");
        }
        if (resolved) {
            out.append("- Resolved: `").append(summary.resolvedAt()).append("`");
            if (summary.resolutionNote() != null && !summary.resolutionNote().isBlank()) {
                out.append(" — ").append(oneLine(summary.resolutionNote()));
            }
            out.append('\n');
        }
        out.append('\n');
        if (summary.detail() != null && !summary.detail().isBlank()) {
            out.append("<details>\n<summary>Last stack trace</summary>\n\n```text\n");
            out.append(truncate(summary.detail(), 12_000));
            if (!summary.detail().endsWith("\n")) {
                out.append('\n');
            }
            out.append("```\n\n</details>\n\n");
        }
        out.append("To fix: inspect the source frame, reproduce with the recorded command, and add a\n");
        out.append("regression test that fails the same way.\n\n");
    }

    private static void addContext(List<String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.add(key + "=" + value);
        }
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String escapeTable(String text) {
        return text.replace("|", "\\|");
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n... (truncated)";
    }

    /**
     * A resolution row. Kept nested so the journal owns both halves of its on-disk schema.
     *
     * @param id         issue id
     * @param resolvedAt ISO-8601 instant
     * @param note       free-form note, possibly empty
     */
    public record Resolution(String id, String resolvedAt, String note) {
        public Resolution {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
            note = note == null ? "" : note;
        }
    }
}

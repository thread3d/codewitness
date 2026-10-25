package dev.codewitness.cli;

import dev.codewitness.storage.SqliteVerificationStore;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code codewitness report <run-id>} - prints a stored run.
 *
 * <p>Two sources are possible: the report files written next to the run, and the SQLite index. The
 * files are preferred because they are the canonical artefacts; the index is the fallback when only
 * the database was kept.
 */
@Command(name = "report", description = "Print a stored verification run, or list recent runs.")
public final class ReportCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<run-id>", description = "Run id, for example"
            + " CW-2026-10-06-7F31A. Omit to list recent runs.")
    private String runId;

    @Option(names = "--project", paramLabel = "<dir>", description = "Project directory (default '.').")
    private String project = ".";

    @Option(names = "--json", description = "Print the canonical JSON report.")
    private boolean printJson;

    @Option(names = "--limit", description = "Number of runs to list (default 20).")
    private int limit = 20;

    @Override
    public Integer call() {
        Path root = Path.of(project).toAbsolutePath().normalize();
        SelfIssues.install(root);
        Path base = root.resolve(".codewitness");
        Path database = base.resolve("codewitness.db");

        if (runId == null) {
            if (!Files.isRegularFile(database)) {
                System.out.println("No verification history found at " + database);
                return 0;
            }
            try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
                List<dev.codewitness.storage.RunSummary> runs = store.listRuns(limit);
                if (runs.isEmpty()) {
                    System.out.println("No runs recorded in " + database);
                    return 0;
                }
                System.out.printf("%-26s %-22s %-20s %-20s %s%n",
                        "RUN ID", "PROJECT", "RUN STATUS", "REQUIREMENTS", "STARTED");
                for (dev.codewitness.storage.RunSummary run : runs) {
                    System.out.printf("%-26s %-22s %-20s %-20s %s%n", run.runId(), run.projectName(),
                            run.status(), run.overallStatus(), run.startedAt());
                }
            }
            return 0;
        }

        Path runDirectory = base.resolve("runs").resolve(runId);
        Path jsonFile = runDirectory.resolve("report.json");
        String json = null;
        boolean fromIndex = false;
        if (Files.isRegularFile(jsonFile)) {
            try {
                json = Files.readString(jsonFile);
            } catch (Exception e) {
                System.err.println("cannot read " + jsonFile + ": " + e.getMessage());
            }
        }
        if (json == null && Files.isRegularFile(database)) {
            try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
                json = store.findReportJson(runId).orElse(null);
                fromIndex = json != null;
            }
        }
        if (json == null) {
            System.err.println("no stored report for run " + runId + " (looked in " + runDirectory + " and "
                    + database + ")");
            return 2;
        }
        System.out.println(printJson ? json : summarise(json, runDirectory, runId, fromIndex));
        return 0;
    }

    /**
     * Prints a short summary of a stored JSON report without a full parse.
     *
     * <p>The source of the report is stated, because a run whose directory was deleted still has an index
     * row and a reader is entitled to know which of the two stores answered.
     *
     * @param json         stored report text
     * @param runDirectory directory the run belongs to
     * @param runId        run id
     * @param fromIndex    whether the text came from the index rather than from the report file
     * @return the summary
     */
    private static String summarise(String json, Path runDirectory, String runId, boolean fromIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append(fromIndex
                        ? "Stored report (from the index; the run directory " + runDirectory + " is missing)"
                        : "Stored report " + runDirectory.resolve("report.json"))
                .append('\n');
        for (String key : List.of("runId", "status", "statusExplanation")) {
            String value = firstString(json, key);
            if (value != null) {
                sb.append(String.format("%-18s %s%n", key, value));
            }
        }
        sb.append('\n');
        // The paths are only worth printing when the files are there. A run whose directory was deleted keeps
        // its index row, and printing the paths of files that do not exist presented a missing report as one
        // that could be opened.
        Path htmlFile = runDirectory.resolve("report.html");
        Path jsonFile = runDirectory.resolve("report.json");
        if (Files.isRegularFile(htmlFile) || Files.isRegularFile(jsonFile)) {
            sb.append("Open the full report:\n");
            if (Files.isRegularFile(htmlFile)) {
                sb.append("  HTML: ").append(htmlFile).append('\n');
            }
            if (Files.isRegularFile(jsonFile)) {
                sb.append("  JSON: ").append(jsonFile).append('\n');
            }
        } else {
            sb.append("The report files for this run no longer exist; only the summary above was kept.\n");
        }
        sb.append("Re-print the canonical JSON with: codewitness report ").append(runId).append(" --json\n");
        return sb.toString();
    }

    private static String firstString(String json, String key) {
        int index = json.indexOf("\"" + key + "\"");
        if (index < 0) {
            return null;
        }
        int colon = json.indexOf(':', index);
        int quote = json.indexOf('"', colon + 1);
        if (colon < 0 || quote < 0) {
            return null;
        }
        int end = json.indexOf('"', quote + 1);
        return end < 0 ? null : json.substring(quote + 1, end);
    }

    /**
     * Deletes old runs, used by {@code clean}.
     *
     * <p>Two things here are deliberate. Runs are ordered by the modification time of their directory, not
     * by name: a run id is a date plus five random characters, so naming order inside one day is arbitrary.
     * Sorting by name made {@code --keep 3} delete the newest run and keep the oldest, which destroys the
     * evidence a user asked to preserve. And the index is cleaned with the directories: deleting only one of
     * the two stores left rows pointing at directories that no longer existed, which {@code report} then
     * printed as though the reports were still there.
     *
     * @param base      project directory holding {@code .codewitness}
     * @param keep      number of most recent runs to keep when {@code deleteAll} is {@code false}
     * @param deleteAll whether to delete every run instead
     * @return exit code: {@code 0} on success, {@code 3} when something could not be deleted
     */
    static int deleteRuns(Path base, int keep, boolean deleteAll) {
        Path runsDirectory = base.resolve("runs");
        if (!Files.isDirectory(runsDirectory)) {
            System.out.println("Nothing to clean at " + runsDirectory);
            return 0;
        }
        try (var stream = Files.list(runsDirectory)) {
            List<Path> directories = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(ReportCommand::lastModified).reversed())
                    .toList();
            List<String> deletedIds = new java.util.ArrayList<>();
            int deleted = 0;
            for (int i = 0; i < directories.size(); i++) {
                if (deleteAll || i >= keep) {
                    Path directory = directories.get(i);
                    deletedIds.add(directory.getFileName().toString());
                    deleteRecursively(directory);
                    deleted++;
                }
            }
            int rows = deleteIndexRows(base, deletedIds);
            System.out.println("Deleted " + deleted + " run director(ies), kept " + (deleteAll ? 0 : keep)
                    + (rows > 0 ? ", and " + rows + " index entr(ies)" : ""));
            return 0;
        } catch (Exception e) {
            System.err.println("clean failed: " + e.getMessage());
            return 3;
        }
    }

    /**
     * Removes the index rows of the runs whose directories were deleted.
     *
     * <p>Best effort by design: a missing or unreadable index must not stop the directories from being
     * removed, but it is reported rather than assumed, because a row left behind is a run that
     * {@code report} will still claim to have.
     *
     * @param base project directory holding {@code .codewitness}
     * @param runIds runs that were deleted
     * @return number of rows removed, {@code 0} when there was no index to update
     */
    private static int deleteIndexRows(Path base, List<String> runIds) {
        if (runIds.isEmpty()) {
            return 0;
        }
        Path database = base.resolve("codewitness.db");
        if (!Files.isRegularFile(database)) {
            return 0;
        }
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            return store.deleteRuns(runIds);
        } catch (RuntimeException e) {
            System.err.println("warning: the run index could not be updated, so "
                    + "`report` may still list the deleted runs: " + e.getMessage());
            return 0;
        }
    }

    /**
     * @param directory run directory
     * @return last modification time in milliseconds, or {@code 0} when it cannot be read
     */
    private static long lastModified(Path directory) {
        try {
            return Files.getLastModifiedTime(directory).toMillis();
        } catch (java.io.IOException e) {
            return 0L;
        }
    }

    private static void deleteRecursively(Path path) throws java.io.IOException {
        try (var walk = Files.walk(path)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}

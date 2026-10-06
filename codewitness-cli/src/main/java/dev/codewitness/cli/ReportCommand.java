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
            }
        }
        if (json == null) {
            System.err.println("no stored report for run " + runId + " (looked in " + runDirectory + " and "
                    + database + ")");
            return 2;
        }
        System.out.println(printJson ? json : summarise(json, runDirectory, runId));
        return 0;
    }

    /** Prints a short summary of a stored JSON report without a full parse. */
    private static String summarise(String json, Path runDirectory, String runId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Stored report ").append(runDirectory.resolve("report.json")).append('\n');
        for (String key : List.of("runId", "status", "statusExplanation")) {
            String value = firstString(json, key);
            if (value != null) {
                sb.append(String.format("%-18s %s%n", key, value));
            }
        }
        sb.append('\n');
        sb.append("Open the full report:\n");
        sb.append("  HTML: ").append(runDirectory.resolve("report.html")).append('\n');
        sb.append("  JSON: ").append(runDirectory.resolve("report.json")).append('\n');
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

    /** Deletes old run directories, used by {@code clean}. */
    static int deleteRuns(Path base, int keep, boolean deleteAll) {
        Path runsDirectory = base.resolve("runs");
        if (!Files.isDirectory(runsDirectory)) {
            System.out.println("Nothing to clean at " + runsDirectory);
            return 0;
        }
        try (var stream = Files.list(runsDirectory)) {
            List<Path> directories = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            int deleted = 0;
            for (int i = 0; i < directories.size(); i++) {
                if (deleteAll || i >= keep) {
                    deleteRecursively(directories.get(i));
                    deleted++;
                }
            }
            System.out.println("Deleted " + deleted + " run director(ies), kept " + (deleteAll ? 0 : keep));
            return 0;
        } catch (Exception e) {
            System.err.println("clean failed: " + e.getMessage());
            return 3;
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

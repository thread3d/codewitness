package dev.codewitness.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code codewitness clean <project>} - removes stored run data. */
@Command(name = "clean", description = "Delete stored verification runs.")
public final class CleanCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project directory.")
    private String project = ".";

    @Option(names = "--keep", description = "Number of most recent runs to keep (default 5).")
    private int keep = 5;

    @Option(names = "--all", description = "Delete every stored run.")
    private boolean all;

    @Option(names = "--output", paramLabel = "<dir>", description = "Run data directory.")
    private Path outputDirectory;

    @Override
    public Integer call() {
        Path root = Path.of(project).toAbsolutePath().normalize();
        SelfIssues.install(root);
        Path base = outputDirectory != null ? outputDirectory : root.resolve(".codewitness");
        System.out.println("CodeWitness run data: " + base);
        return ReportCommand.deleteRuns(base, keep, all);
    }
}

package dev.codewitness.cli;

import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.storage.SqliteVerificationStore;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code codewitness reproduce <run-id>} - prints how to reproduce a counterexample.
 *
 * <p>A counterexample that cannot be replayed is only an anecdote, so every counterexample carries the
 * exact command that reproduces it. This command surfaces that command, and with {@code --run} it
 * executes the reproduction step as well.
 */
@Command(name = "reproduce", description = "Show (or run) the command that reproduces a counterexample.")
public final class ReproduceCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<run-id>", description = "Run id that produced the counterexample.")
    private String runId;

    @Option(names = "--counterexample", paramLabel = "<id>", description = "Counterexample id, for example CE-001.")
    private String counterexampleId;

    @Option(names = "--project", paramLabel = "<dir>", description = "Project directory (default '.').")
    private String project = ".";

    @Option(names = "--output", paramLabel = "<dir>", description = "Run data directory.")
    private Path outputDirectory;

    @Option(names = "--run", description = "Execute the reproduction command instead of printing it.")
    private boolean execute;

    @Override
    public Integer call() {
        Path root = Path.of(project).toAbsolutePath().normalize();
        SelfIssues.install(root);
        Path base = outputDirectory != null ? outputDirectory : root.resolve(".codewitness");
        Path jsonFile = base.resolve("runs").resolve(runId).resolve("report.json");
        String json = null;
        if (Files.isRegularFile(jsonFile)) {
            try {
                json = Files.readString(jsonFile);
            } catch (Exception e) {
                System.err.println("cannot read " + jsonFile + ": " + e.getMessage());
            }
        }
        if (json == null && Files.isRegularFile(base.resolve("codewitness.db"))) {
            try (SqliteVerificationStore store = new SqliteVerificationStore(base.resolve("codewitness.db"))) {
                json = store.findReportJson(runId).orElse(null);
            }
        }
        if (json == null) {
            System.err.println("no stored report for run " + runId);
            return 2;
        }

        List<String> commands = extractReproductionCommands(json, counterexampleId);
        if (commands.isEmpty()) {
            System.out.println(counterexampleId == null
                    ? "This run recorded no counterexamples, so there is nothing to reproduce."
                    : "No counterexample " + counterexampleId + " was recorded in run " + runId + ".");
            return 0;
        }
        System.out.println("Reproduction command(s) for " + runId
                + (counterexampleId == null ? "" : " / " + counterexampleId) + ":");
        commands.forEach(command -> System.out.println("  " + command));
        if (!execute) {
            System.out.println();
            System.out.println("Run them from the project directory. Add --run to execute the first one now.");
            return 0;
        }
        restoreGeneratedTest(root, base.resolve("runs").resolve(runId), testClassName(commands.getFirst()));
        return runCommand(root, commands.getFirst());
    }

    /**
     * Runs a recorded reproduction command.
     *
     * <p>Ground rule 6 of {@code CONTRIBUTING.md} says all process execution goes through the sandbox,
     * and this is process execution of a command string that came out of a stored report. It used to
     * bypass the sandbox entirely: a raw shell with inherited stdio, no timeout, no output cap, no
     * working-directory check and the caller's whole environment. It now runs through the same
     * {@link LocalProcessSandbox} the verification phases use, so it gets a sanitised environment, a
     * hard timeout and capped output.
     *
     * <p>The recorded command is a single string, so it is handed to the platform shell exactly as it
     * was printed. The working directory must be inside the project, which the sandbox enforces.
     */
    private static int runCommand(Path projectRoot, String command) {
        System.out.println();
        System.out.println("$ " + command);
        LocalProcessSandbox sandbox = new LocalProcessSandbox(List.of(projectRoot));
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        List<String> shell = windows
                ? List.of("cmd", "/c", command)
                : List.of("/bin/sh", "-c", command);
        ProcessResult result = sandbox.runner().run(ProcessSpec.builder(shell, projectRoot)
                .timeout(Duration.ofMinutes(30))
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            System.err.println("could not run the reproduction command: " + result.failureReason());
            return 3;
        }
        System.out.print(result.stdout());
        System.err.print(result.stderr());
        if (result.timedOut()) {
            System.err.println("the reproduction command timed out after " + result.duration().toSeconds() + "s");
            return 3;
        }
        System.out.println("exit code " + result.exitCode());
        return result.exitCode();
    }

    /** @return the test class name inside a reproduction command, or {@code null}. */
    static String testClassName(String command) {
        if (command == null) {
            return null;
        }
        int at = command.indexOf("-Dtest=");
        if (at < 0) {
            return null;
        }
        String rest = command.substring(at + "-Dtest=".length());
        int end = rest.indexOf(' ');
        String value = end < 0 ? rest : rest.substring(0, end);
        int comma = value.indexOf(',');
        return comma < 0 ? value : value.substring(0, comma);
    }

    /**
     * Restores the generated test source into the project before replaying it.
     *
     * <p>CodeWitness removes generated tests from the project once a run has finished and keeps a copy
     * in the run directory. Replaying a counterexample therefore means copying that copy back first,
     * which is what this does, so that the printed reproduction command works as printed.
     */
    private static void restoreGeneratedTest(Path projectRoot, Path runDirectory, String className) {
        if (className == null) {
            return;
        }
        Path artifacts = runDirectory.resolve("artifacts").resolve("generated-tests");
        if (!Files.isDirectory(artifacts)) {
            System.err.println("note: no generated test sources were kept for this run; re-run the "
                    + "verification with report.keep_generated_tests=true to replay it in place");
            return;
        }
        try (var walk = Files.walk(artifacts)) {
            Path source = walk.filter(path -> path.getFileName().toString().equals(className + ".java"))
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                System.err.println("note: " + className + ".java was not found under " + artifacts);
                return;
            }
            String relative = source.toString().replace('\\', '/');
            String packagePath = relative.substring(relative.indexOf("dev/codewitness/generated/"));
            Path target = projectRoot.resolve("src/test/java").resolve(packagePath);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Restored " + target + " so that the failing test can run.");
        } catch (Exception e) {
            System.err.println("note: could not restore the generated test source: " + e.getMessage());
        }
    }

    /** Extracts every reproduction command from a stored JSON report. */
    static List<String> extractReproductionCommands(String json, String counterexampleId) {
        List<String> commands = new ArrayList<>();
        int index = 0;
        while (true) {
            int at = json.indexOf("\"reproduction\"", index);
            if (at < 0) {
                break;
            }
            index = at + 1;
            if (counterexampleId != null) {
                int idAt = json.lastIndexOf("\"id\"", at);
                if (idAt < 0) {
                    continue;
                }
                String id = firstStringAfter(json, idAt);
                if (id == null || !id.equals(counterexampleId)) {
                    continue;
                }
            }
            int commandAt = json.indexOf("\"command\"", at);
            if (commandAt < 0) {
                continue;
            }
            int arrayStart = json.indexOf('[', commandAt);
            int arrayEnd = json.indexOf(']', arrayStart);
            if (arrayStart < 0 || arrayEnd < 0) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"([^\"]*)\"")
                    .matcher(json.substring(arrayStart, arrayEnd));
            while (matcher.find()) {
                parts.add(matcher.group(1));
            }
            if (!parts.isEmpty()) {
                commands.add(String.join(" ", parts));
            }
            if (counterexampleId != null) {
                break;
            }
        }
        return commands;
    }

    /** @return the generated test source recorded for a counterexample, or {@code null}. */
    static String extractTestArtifact(String json, String counterexampleId) {
        if (counterexampleId == null) {
            return null;
        }
        int idAt = json.indexOf("\"" + counterexampleId + "\"");
        if (idAt < 0) {
            return null;
        }
        int artifactAt = json.indexOf("\"testArtifact\"", idAt);
        if (artifactAt < 0) {
            return null;
        }
        int nextCounterexample = json.indexOf("\"requirementId\"", artifactAt);
        if (nextCounterexample > 0) {
            // the artifact belongs to the counterexample only if it appears before the next one
            int nextId = json.indexOf("\"summary\"", artifactAt);
            if (nextId > 0 && nextId < artifactAt) {
                return null;
            }
        }
        return firstStringAfter(json, artifactAt);
    }

    private static String firstStringAfter(String json, int index) {
        int colon = json.indexOf(':', index);
        int quote = json.indexOf('"', colon + 1);
        int end = json.indexOf('"', quote + 1);
        return quote < 0 || end < 0 ? null : json.substring(quote + 1, end);
    }
}

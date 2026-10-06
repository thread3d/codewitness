package dev.codewitness.cli;

import dev.codewitness.core.config.ConfigLoader;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.RunId;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.java.JavaAdapterBundle;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code codewitness test <project>} - runs the existing test suite only.
 *
 * <p>The fast path: build, run tests, report. It exists so that the deterministic phases can be used
 * on their own when full verification would take too long, and so that a failing build or test suite
 * can be diagnosed without the rest of the pipeline running.
 */
@Command(name = "test", description = "Compile the project and run its existing tests.")
public final class TestCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project directory.")
    private String project;

    @Option(names = "--config", paramLabel = "<file>", description = "Configuration file.")
    private Path configFile;

    @Option(names = "--output", paramLabel = "<dir>", description = "Directory for run data.")
    private Path outputDirectory;

    @Option(names = "--in-place", description = "Run directly in the project directory.")
    private boolean inPlace;

    @Override
    public Integer call() {
        ProjectRef projectRef = CliSupport.projectRef(project);
        ConfigLoader.Loaded loaded = CliSupport.loadConfig(projectRef.root(), configFile);
        VerificationConfig config = loaded.config();

        LanguageAdapterRegistry languages = new LanguageAdapterRegistry();
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        JavaAdapterBundle.register(languages, providers, true);

        LanguageAdapter adapter = languages.select(projectRef, config.language().adapter()).orElse(null);
        if (adapter == null) {
            System.err.println("No language adapter supports " + projectRef.root());
            return 2;
        }

        Path base = CliSupport.runBase(projectRef.root(), outputDirectory, config);
        Path runDirectory = base.resolve("runs").resolve(RunId.generate().value());
        ArtifactStore artifacts = new ArtifactStore(runDirectory);
        WorkspaceManager.Workspace workspace = WorkspaceManager.prepare(projectRef, runDirectory,
                inPlace ? WorkspaceManager.Mode.IN_PLACE : WorkspaceManager.Mode.COPY);
        ExecutionSandbox sandbox = new LocalProcessSandbox(List.of(workspace.root(), runDirectory));

        VerificationContext context = VerificationContext.builder()
                .runId(runDirectory.getFileName().toString())
                .projectRef(projectRef)
                .projectInfo(dev.codewitness.core.model.ProjectInfo.builder(projectRef).build())
                .config(config)
                .workspace(workspace)
                .artifacts(artifacts)
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .requirements(RequirementSet.empty())
                .sandbox(sandbox)
                .evidenceLedger(new dev.codewitness.core.evidence.EvidenceLedger("test"))
                .progress(CliSupport.consoleProgress(false))
                .build();

        var info = adapter.inspect(projectRef, context);
        context = VerificationContext.builder()
                .runId(runDirectory.getFileName().toString())
                .projectRef(projectRef)
                .projectInfo(info)
                .config(config)
                .workspace(workspace)
                .artifacts(artifacts)
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .requirements(RequirementSet.empty())
                .sandbox(sandbox)
                .evidenceLedger(new dev.codewitness.core.evidence.EvidenceLedger("test"))
                .progress(CliSupport.consoleProgress(false))
                .build();

        System.out.println("CODEWITNESS  test");
        System.out.println("project      " + info.describe());

        var build = adapter.build(context);
        System.out.println("build        " + build.describe());
        if (!build.success()) {
            System.out.println(firstLines(build.output(), 20));
            System.out.println();
            System.out.println("The build failed, so no tests ran. This is an infrastructure result: CodeWitness"
                    + " reports it as BLOCKED, not as a code defect.");
            return 2;
        }

        TestResult result = adapter.runTests(context);
        System.out.println();
        System.out.println("Tests");
        System.out.println("  " + result.describe());
        for (TestCaseResult failure : result.failures()) {
            System.out.println("  FAILED " + failure.displayName() + ": " + oneLine(failure.failureMessage()));
        }
        System.out.println();
        System.out.println("Reports stored in " + runDirectory);
        return result.anyFailure() ? 1 : 0;
    }

    private static String firstLines(String text, int count) {
        if (text == null) {
            return "";
        }
        return text.lines().limit(count).reduce("", (a, b) -> a + b + "\n");
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}

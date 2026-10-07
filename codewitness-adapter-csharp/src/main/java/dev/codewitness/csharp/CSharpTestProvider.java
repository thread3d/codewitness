package dev.codewitness.csharp;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a .NET project's existing tests with {@code dotnet test}.
 *
 * <p>Each test project is run on its own, with its own results directory and its own TRX report. Running
 * a whole solution in one command is tempting, but every test project in that run would write a report
 * under the same name, and a report that overwrote another one would silently drop a project's results.
 * One command per test project costs a few seconds and makes each observation unambiguous.
 *
 * <p>The report is read rather than the console, so every case carries the class it belongs to, its
 * outcome and, on failure, the assertion message. Restore is left enabled here: this is the phase whose
 * result has to be trustworthy, and a project whose packages are not restored cannot be tested at all.
 * When no test project exists the phase reports that as a fact instead of running a test host that would
 * find nothing.
 */
public final class CSharpTestProvider implements VerificationTools.TestRunner {

    /** Name of the TRX report inside each project's results directory. */
    public static final String REPORT_NAME = "results.trx";

    @Override
    public String id() {
        return "dotnet-test";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CSHARP);
    }

    /** @return the test projects of this run, in the order the detector reports them. */
    public static List<Path> testTargets(VerificationContext context) {
        return CSharpProjectDetector.testProjects(context.projectRoot());
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        List<Path> targets = testTargets(context);
        if (targets.isEmpty()) {
            return TestResult.notExecuted("no project references a .NET test host "
                    + "(Microsoft.NET.Test.Sdk or Microsoft.Testing.Platform), so there is no test project "
                    + "to run");
        }
        Path root = context.projectRoot();
        List<TestCaseResult> cases = new ArrayList<>();
        List<Path> reports = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        int index = 0;
        for (Path target : targets) {
            index++;
            Path resultsDirectory = context.artifacts().artifactsDirectory()
                    .resolve("dotnet/tests/" + index);
            Path report = resultsDirectory.resolve(REPORT_NAME);
            try {
                // A report left by an earlier attempt would be indistinguishable from a fresh one.
                Files.createDirectories(resultsDirectory);
                Files.deleteIfExists(report);
            } catch (IOException e) {
                notes.add("cannot prepare the results directory " + resultsDirectory + ": " + e);
                continue;
            }
            List<String> command = commandFor(context, target, resultsDirectory);
            context.emit("tests", "running dotnet test on " + target.getFileName());

            ProcessResult result = context.runner().run(ProcessSpec.builder(command, root)
                    .environment(DotnetTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(2_000_000)
                    .build());
            if (!result.started()) {
                notes.add("dotnet could not be started: " + result.failureReason());
                continue;
            }
            if (!Files.isRegularFile(report)) {
                // The SDK writes a report for every run it completes, including a failed one; no report
                // means the run never reached the test host, which is a fact worth stating, not a pass.
                notes.add("dotnet test on " + target.getFileName() + " produced no TRX report ("
                        + result.describeOutcome() + "); last output: "
                        + DotnetTool.lastLines(result.combinedOutput(), 4));
                continue;
            }
            TestResult parsed = CSharpTestReportParser.parse(report,
                    "parsed from the TRX report; dotnet test " + result.describeOutcome());
            if (!parsed.executed()) {
                notes.add(parsed.note());
                continue;
            }
            cases.addAll(parsed.cases());
            reports.add(report);
        }

        if (cases.isEmpty()) {
            return TestResult.notExecuted("dotnet test reported no test case: "
                    + (notes.isEmpty() ? "no test project produced a report" : String.join("; ", notes)));
        }
        String note = "parsed from " + reports.size() + " TRX report(s)"
                + (notes.isEmpty() ? "" : "; " + String.join("; ", notes));
        Duration duration = cases.stream().map(TestCaseResult::duration).reduce(Duration.ZERO, Duration::plus);
        return TestResult.from(cases, duration, reports, note);
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        List<Path> targets = testTargets(context);
        if (targets.isEmpty()) {
            return List.of();
        }
        Path resultsDirectory = context.artifacts().artifactsDirectory().resolve("dotnet/tests/1");
        return commandFor(context, targets.getFirst(), resultsDirectory);
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        Path target = replayTarget(context, testCase);
        if (target == null) {
            return List.of();
        }
        List<String> command = new ArrayList<>();
        command.add(DotnetTool.dotnet(context.projectInfo(), context));
        command.add("test");
        command.add(CSharpBuildProvider.relativeArgument(context.projectRoot(), target));
        command.addAll(DotnetTool.commonArguments());
        // A class name is not filter-safe: the filter grammar spends '(' and ')' on grouping, and a theory
        // case's display name carries them. Matching on the fully qualified class and method with the
        // contains operator replays every case of the method, which is what a counterexample needs.
        command.add("--filter");
        command.add("FullyQualifiedName~" + testCase.suite() + "." + testCase.name());
        return List.copyOf(command);
    }

    /**
     * @return the test project that declares the class the failing case belongs to, so the replay command
     *     names the project that can actually run it; the first test project when the class cannot be
     *     located.
     */
    static Path replayTarget(VerificationContext context, TestCaseResult testCase) {
        List<Path> targets = testTargets(context);
        if (targets.isEmpty()) {
            return null;
        }
        String suite = testCase.suite();
        int dot = suite == null ? -1 : suite.lastIndexOf('.');
        String simpleName = dot < 0 ? suite : suite.substring(dot + 1);
        if (simpleName != null && !simpleName.isBlank()) {
            for (Path target : targets) {
                Path directory = target.getParent();
                if (directory != null && Files.isRegularFile(directory.resolve(simpleName + ".cs"))) {
                    return target;
                }
            }
        }
        return targets.getFirst();
    }

    /** @return the argv that runs one test project and writes its TRX report. */
    static List<String> commandFor(VerificationContext context, Path target, Path resultsDirectory) {
        List<String> command = new ArrayList<>();
        command.add(DotnetTool.dotnet(context.projectInfo(), context));
        command.add("test");
        command.add(CSharpBuildProvider.relativeArgument(context.projectRoot(), target));
        command.addAll(DotnetTool.commonArguments());
        command.add("--results-directory");
        command.add(resultsDirectory.toString());
        command.add("--logger");
        command.add("trx;LogFileName=" + REPORT_NAME);
        return List.copyOf(command);
    }
}

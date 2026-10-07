package dev.codewitness.go;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a Go module's existing tests with {@code go test -json ./...}.
 *
 * <p>One command covers the whole module: unit tests beside the code, external test packages and
 * examples. The results are read from Go's own JSON event stream by {@link GoTestReportParser}, and
 * the raw stream is stored as an artefact so the evidence can be checked after the run.
 *
 * <p>{@code -count=1} disables Go's test result cache on purpose: a cached "ok (cached)" line is not
 * an experiment that ran, and verification must never accept a remembered pass as an observation. A
 * run that produced no case at all is reported as "tests did not execute", never as a pass.
 */
public final class GoTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "go-test";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        List<String> command = testCommand(context);
        if (command.isEmpty()) {
            return TestResult.notExecuted("no go.mod was found, so the tests cannot be run");
        }
        context.emit("tests", "running go test");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(GoTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000)
                .build());

        List<Path> reports = new ArrayList<>();
        if (!result.stdout().isBlank()) {
            reports.add(context.artifacts().write("go/tests/go-test.json", result.stdout()));
        }
        if (!result.started()) {
            return TestResult.notExecuted("go could not be started: " + result.failureReason());
        }
        return GoTestReportParser.parse(result.stdout(), result.stderr(),
                "parsed from go test -json; " + result.describeOutcome(), reports);
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        if (!GoTool.hasManifest(context)) {
            return List.of();
        }
        // -count=1 disables the test cache so the run reflects this build, not an earlier one.
        return List.of(GoTool.go(context.projectInfo(), context), "test", "-json", "-count=1", "./...");
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        String go = GoTool.go(context.projectInfo(), context);
        // Go selects a test by a regular expression on its name, and -count=1 keeps the replay from
        // being answered out of the test cache. The name is quoted first: a Go test name is not
        // regex-safe (a subtest is named TestX/case(1)), and an unquoted metacharacter would make the
        // replay select nothing instead of the test that failed.
        List<String> command = new ArrayList<>(
                List.of(go, "test", "-count=1", "-run", "^" + quoteMeta(testCase.name()) + "$"));
        if (testCase.suite() != null && !testCase.suite().isBlank()) {
            command.add(testCase.suite());
        } else {
            command.add("./...");
        }
        return List.copyOf(command);
    }

    /**
     * Quotes every Go regular-expression metacharacter in {@code value}, as {@code regexp.QuoteMeta}
     * does.
     *
     * <p>{@code -run} takes a regular expression, so a test name must be escaped before it is
     * interpolated. The escaped set is Go's own and is written out here by hand because this adapter
     * depends on the core only, never on a third-party regex library.
     *
     * @param value the literal text to match
     * @return the text with Go's metacharacters ({@code \.+*?()|[]{}^$}) backslash-escaped
     */
    static String quoteMeta(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.toString();
    }
}

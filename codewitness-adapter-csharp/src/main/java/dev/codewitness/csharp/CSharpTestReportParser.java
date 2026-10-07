package dev.codewitness.csharp;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the TRX report {@code dotnet test} writes into an aggregate result.
 *
 * <p>Results are read from the report rather than from the console, because the console prints a
 * human-oriented summary while the report carries, per case, the fully qualified class it belongs to,
 * its outcome, its duration and - when it failed - the failure message and stack trace. That is the
 * difference between an observation and a reading of a log: an assertion message ends up in the
 * counterexample verbatim, which is how the structured
 * {@code CODEWITNESS_COUNTEREXAMPLE|...} line a generated test fails with survives into the report.
 *
 * <p>The class name is taken from the {@code TestDefinitions} entry the result refers to by id, not
 * from the result's display name. A display name is {@code Namespace.Class.Method} for a plain case but
 * carries the arguments for a theory case, and splitting the wrong end of it would attribute a test to
 * a class that does not exist. The definition is the compiler's own answer.
 *
 * <p>Outcomes map onto the four the model has. VSTest has more than four, and the extras are not
 * distinctions the engine can use: a case that was not run, was skipped, was inconclusive, was not
 * runnable or was reported after the run aborted is all "not evidence", and is recorded as skipped
 * rather than as a pass. Only {@code Passed} is a pass and only {@code Failed} is a failure.
 */
public final class CSharpTestReportParser {

    private CSharpTestReportParser() {
    }

    /**
     * Parses one TRX report.
     *
     * @param report the {@code .trx} file
     * @param note   explanation carried into the result, including the tool run's outcome
     * @return the parsed result, or a not-executed result when the report could not be read
     */
    public static TestResult parse(Path report, String note) {
        Document document;
        try {
            document = CSharpXml.parse(report);
        } catch (Exception e) {
            return TestResult.notExecuted("the TRX report " + report.getFileName() + " could not be read: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        List<TestCaseResult> cases = cases(document);
        if (cases.isEmpty()) {
            return TestResult.notExecuted("the TRX report " + report.getFileName()
                    + " recorded no test case, so nothing was executed");
        }
        Duration duration = cases.stream().map(TestCaseResult::duration).reduce(Duration.ZERO, Duration::plus);
        return TestResult.from(cases, duration, List.of(report), note);
    }

    /** @return every case in the report, in the order the report lists them. */
    static List<TestCaseResult> cases(Document document) {
        Map<String, Definition> definitions = definitions(document);
        List<TestCaseResult> cases = new ArrayList<>();
        for (Element results : CSharpXml.children(document.getDocumentElement(), "Results")) {
            for (Element result : CSharpXml.children(results, "UnitTestResult")) {
                String testId = CSharpXml.attribute(result, "testId");
                Definition definition = definitions.get(testId);
                String suite = definition == null ? suiteFromTestName(CSharpXml.attribute(result, "testName"))
                        : definition.className();
                if (suite == null) {
                    // A result with neither a definition nor a parsable display name cannot be attributed
                    // to a class; the run's own name for it is the only honest identifier left.
                    suite = "unknown";
                }
                String name = definition == null ? nameFromTestName(CSharpXml.attribute(result, "testName"))
                        : definition.name();
                TestOutcome outcome = outcome(CSharpXml.attribute(result, "outcome"));
                String message = null;
                String stackTrace = null;
                if (outcome.isFailure()) {
                    Element output = CSharpXml.firstDescendant(result, "Output");
                    Element errorInfo = CSharpXml.firstDescendant(output, "ErrorInfo");
                    message = errorInfo == null ? null : CSharpXml.text(errorInfo, "Message");
                    stackTrace = errorInfo == null ? null : CSharpXml.text(errorInfo, "StackTrace");
                    if (message == null) {
                        // A failure the test host reported without an assertion message (an adapter crash,
                        // a process exit) still has to say something; the display name is all there is.
                        message = "the test host reported no failure message for "
                                + suite + "." + name;
                    }
                }
                cases.add(new TestCaseResult(suite, name, outcome, timeSpan(CSharpXml.attribute(result, "duration")),
                        failureType(outcome, message), message, stackTrace));
            }
        }
        return cases;
    }

    /** @return the class and method each test id refers to, as the report's {@code TestDefinitions} state. */
    private static Map<String, Definition> definitions(Document document) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (Element container : CSharpXml.children(document.getDocumentElement(), "TestDefinitions")) {
            for (Element unitTest : CSharpXml.children(container, "UnitTest")) {
                String id = CSharpXml.attribute(unitTest, "id");
                Element method = CSharpXml.firstDescendant(unitTest, "TestMethod");
                if (id == null || method == null) {
                    continue;
                }
                String className = CSharpXml.attribute(method, "className");
                String name = CSharpXml.attribute(method, "name");
                if (className != null && name != null) {
                    definitions.put(id, new Definition(className, name));
                }
            }
        }
        return definitions;
    }

    /**
     * @return the class part of a {@code Namespace.Class.Method} display name, or the whole name when it
     *     has no dot.
     */
    static String suiteFromTestName(String testName) {
        if (testName == null || testName.isBlank()) {
            return null;
        }
        int dot = testName.lastIndexOf('.');
        return dot < 0 ? testName : testName.substring(0, dot);
    }

    /** @return the method part of a display name, or the whole name when it has no dot. */
    static String nameFromTestName(String testName) {
        if (testName == null || testName.isBlank()) {
            return "unknown";
        }
        int dot = testName.lastIndexOf('.');
        return dot < 0 ? testName : testName.substring(dot + 1);
    }

    /**
     * @return the model's outcome for a VSTest outcome string.
     *
     * <p>Everything that is not {@code Passed} or {@code Failed} is recorded as skipped: a case that did
     * not run is not evidence, and counting it as a pass would be the single most misleading thing a test
     * report could do.
     */
    static TestOutcome outcome(String value) {
        String outcome = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (outcome) {
            case "passed" -> TestOutcome.PASSED;
            case "failed" -> TestOutcome.FAILED;
            case "error", "timeout", "passedbutrunaborted", "aborted" -> TestOutcome.ERROR;
            default -> TestOutcome.SKIPPED;
        };
    }

    private static String failureType(TestOutcome outcome, String message) {
        if (!outcome.isFailure()) {
            return null;
        }
        if (message != null) {
            if (message.contains("Assert.") || message.contains("Xunit")) {
                return "assertion failure";
            }
            if (message.contains("Exception")) {
                return "exception";
            }
        }
        return outcome == TestOutcome.ERROR ? "test host error" : "test failure";
    }

    /**
     * @return the duration of a TRX {@code hh:mm:ss.fffffff} value, or zero when it is absent or
     *     malformed.
     */
    static Duration timeSpan(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        String[] parts = value.strip().split(":");
        if (parts.length != 3) {
            return Duration.ZERO;
        }
        try {
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            return Duration.ofMillis(Math.round((hours * 3600 + minutes * 60 + seconds) * 1000));
        } catch (NumberFormatException _) {
            return Duration.ZERO;
        }
    }

    /** A test class and method as the report's definitions state them. */
    private record Definition(String className, String name) {
    }
}

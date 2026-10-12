package dev.codewitness.pascal;

import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a Pascal test run reported.
 *
 * <p>The fpcunit console runner writes a JUnit-shaped XML report, so a run driven through it produces
 * one case per test with the failing assertion's message. A project whose test program is not an
 * fpcunit runner is judged by its exit status instead, and a test program that found a counterexample
 * reports it in the same structured line every generated test uses, so the engine can reconstruct it.
 *
 * <p>A test that did not compile is an {@code ERROR}, never a {@code FAILED}: it is a statement about
 * the build, not about the behaviour the test meant to constrain.
 */
public final class PascalTestReportParser {

    private static final Pattern TEST_TAG = Pattern.compile("<Test\\b([^>]*?)(?:/>|>(.*?)</Test>)", Pattern.DOTALL);
    private static final Pattern SUITE_TAG = Pattern.compile("<TestSuite\\b([^>]*)>");
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("Name=\"([^\"]*)\"");
    private static final Pattern ATTRIBUTE_RESULT = Pattern.compile("Result=\"([^\"]*)\"");
    private static final Pattern ATTRIBUTE_ELAPSED = Pattern.compile("ElapsedTime=\"([^\"]*)\"");
    private static final Pattern EXCEPTION_CLASS = Pattern.compile("<ExceptionClass>(.*?)</ExceptionClass>", Pattern.DOTALL);
    private static final Pattern EXCEPTION_MESSAGE =
            Pattern.compile("<ExceptionMessage>(.*?)</ExceptionMessage>", Pattern.DOTALL);
    private static final Pattern MESSAGE = Pattern.compile("<Message>(.*?)</Message>", Pattern.DOTALL);
    private static final Pattern RUN_TESTS = Pattern.compile("(?i)Number of run tests:\\s*(\\d+)");
    private static final Pattern ERRORS = Pattern.compile("(?i)Number of errors:\\s*(\\d+)");
    private static final Pattern FAILURES = Pattern.compile("(?i)Number of failures:\\s*(\\d+)");

    private PascalTestReportParser() {
    }

    /**
     * Parses an fpcunit XML report.
     *
     * @param xml           the report text
     * @param fallbackSuite the suite name to use for a case the report does not nest in a named suite
     * @return one case per {@code <Test>} element, in report order
     */
    public static List<TestCaseResult> parseXml(String xml, String fallbackSuite) {
        List<TestCaseResult> cases = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return cases;
        }
        Matcher suites = SUITE_TAG.matcher(xml);
        List<int[]> suitePositions = new ArrayList<>();
        List<String> suiteNames = new ArrayList<>();
        while (suites.find()) {
            suitePositions.add(new int[] {suites.start()});
            suiteNames.add(attribute(suites.group(1), ATTRIBUTE_NAME));
        }
        Matcher tests = TEST_TAG.matcher(xml);
        while (tests.find()) {
            String attributes = tests.group(1);
            String body = tests.group(2) == null ? "" : tests.group(2);
            String name = attribute(attributes, ATTRIBUTE_NAME);
            if (name.isBlank()) {
                continue;
            }
            String result = attribute(attributes, ATTRIBUTE_RESULT);
            String suite = enclosingSuite(suitePositions, suiteNames, tests.start(), fallbackSuite);
            TestOutcome outcome = outcomeOf(result);
            String exceptionClass = unescape(firstGroup(EXCEPTION_CLASS, body));
            String message = unescape(firstGroup(EXCEPTION_MESSAGE, body));
            if (message.isBlank()) {
                message = unescape(firstGroup(MESSAGE, body));
            }
            cases.add(new TestCaseResult(
                    suite,
                    name,
                    outcome,
                    duration(attribute(attributes, ATTRIBUTE_ELAPSED)),
                    exceptionClass.isBlank() ? null : exceptionClass,
                    message.isBlank() ? null : message,
                    null));
        }
        return cases;
    }

    /**
     * Reads the summary a plain fpcunit run prints, when it printed one.
     *
     * @return a single synthetic case carrying the totals, or empty when the output is not a summary
     */
    public static Optional<TestCaseResult> parsePlainSummary(String output, String suite) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        Matcher run = RUN_TESTS.matcher(output);
        if (!run.find()) {
            return Optional.empty();
        }
        int total = integer(run.group(1));
        int errors = integer(firstGroup(ERRORS, output));
        int failures = integer(firstGroup(FAILURES, output));
        TestOutcome outcome = errors + failures == 0 ? TestOutcome.PASSED : TestOutcome.FAILED;
        String message = errors + failures == 0
                ? null
                : total + " test(s): " + failures + " failure(s), " + errors + " error(s)";
        return Optional.of(new TestCaseResult(suite, "fpcunit", outcome, Duration.ZERO,
                errors + failures == 0 ? null : "TestSummary", message, null));
    }

    /**
     * Judges a test program's own process by what it reported.
     *
     * @param suite the suite name
     * @param name  the case name
     * @param run   the process that ran the test program
     * @return the case, with {@code FAILED} only for an observed failure
     */
    public static TestCaseResult fromExecution(String suite, String name, ProcessResult run) {
        if (!run.started()) {
            return error(suite, name, "the test program could not be started: " + run.failureReason());
        }
        if (run.timedOut()) {
            return error(suite, name, "the test program timed out after " + run.duration().toSeconds() + "s");
        }
        String output = run.combinedOutput();
        String counterexample = counterexampleLine(output);
        if (counterexample != null) {
            return new TestCaseResult(suite, name, TestOutcome.FAILED, run.duration(),
                    "Counterexample", counterexample, null);
        }
        if (run.exitCode() != 0) {
            return new TestCaseResult(suite, name, TestOutcome.FAILED, run.duration(),
                    "TestFailure", summarise(output), null);
        }
        return TestCaseResult.passed(suite, name, run.duration());
    }

    /** @return a case that records a test program the compiler rejected, which is an error and not a failure. */
    public static TestCaseResult compileFailure(String suite, String name, ProcessResult compile) {
        String message = compile.started()
                ? "the test program did not compile: " + compile.describeOutcome()
                : "the test program could not be compiled: " + compile.failureReason();
        return new TestCaseResult(suite, name, TestOutcome.ERROR, compile.duration(),
                "CompileError", message, null);
    }

    /** @return the first counterexample protocol line in the output, or {@code null}. */
    static String counterexampleLine(String output) {
        if (output == null) {
            return null;
        }
        int index = output.indexOf(CounterexampleProtocol.MARKER);
        if (index < 0) {
            return null;
        }
        String line = output.substring(index);
        int newline = line.indexOf('\n');
        return newline >= 0 ? line.substring(0, newline).strip() : line.strip();
    }

    private static TestCaseResult error(String suite, String name, String message) {
        return new TestCaseResult(suite, name, TestOutcome.ERROR, Duration.ZERO, "TestError", message, null);
    }

    private static String summarise(String output) {
        return PascalTool.lastLines(output, 8);
    }

    private static TestOutcome outcomeOf(String result) {
        if (result == null) {
            return TestOutcome.ERROR;
        }
        return switch (result.toLowerCase(Locale.ROOT)) {
            case "ok", "passed", "pass" -> TestOutcome.PASSED;
            case "failed", "failure" -> TestOutcome.FAILED;
            case "ignored", "skipped" -> TestOutcome.SKIPPED;
            default -> TestOutcome.ERROR;
        };
    }

    private static String enclosingSuite(List<int[]> positions, List<String> names, int offset, String fallback) {
        String suite = fallback;
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i)[0] < offset) {
                String candidate = names.get(i);
                if (!candidate.isBlank()) {
                    suite = candidate;
                }
            }
        }
        return suite == null || suite.isBlank() ? "fpcunit" : suite;
    }

    private static String attribute(String attributes, Pattern pattern) {
        if (attributes == null) {
            return "";
        }
        return unescape(firstGroup(pattern, attributes));
    }

    private static String firstGroup(Pattern pattern, String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static Duration duration(String text) {
        if (text == null || text.isBlank()) {
            return Duration.ZERO;
        }
        String[] parts = text.strip().split(":");
        try {
            if (parts.length == 3) {
                long hours = Long.parseLong(parts[0]);
                long minutes = Long.parseLong(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                return Duration.ofMillis(Math.round(((hours * 60 + minutes) * 60 + seconds) * 1000));
            }
        } catch (NumberFormatException _) {
            return Duration.ZERO;
        }
        return Duration.ZERO;
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (RuntimeException _) {
            return 0;
        }
    }

    /** @return the text with the XML entities a report can carry decoded. */
    static String unescape(String value) {
        if (value == null) {
            return "";
        }
        Map<String, String> named = new LinkedHashMap<>();
        named.put("&lt;", "<");
        named.put("&gt;", ">");
        named.put("&quot;", "\"");
        named.put("&apos;", "'");
        named.put("&amp;", "&");
        String decoded = value;
        for (Map.Entry<String, String> entry : named.entrySet()) {
            decoded = decoded.replace(entry.getKey(), entry.getValue());
        }
        Matcher numeric = Pattern.compile("&#(x?)([0-9A-Fa-f]+);").matcher(decoded);
        StringBuilder result = new StringBuilder();
        while (numeric.find()) {
            int code = numeric.group(1).isEmpty()
                    ? Integer.parseInt(numeric.group(2))
                    : Integer.parseInt(numeric.group(2), 16);
            numeric.appendReplacement(result, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        numeric.appendTail(result);
        return result.toString();
    }
}

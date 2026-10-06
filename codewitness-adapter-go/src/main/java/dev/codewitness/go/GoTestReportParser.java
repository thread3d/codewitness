package dev.codewitness.go;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code go test -json} output into an aggregate result.
 *
 * <p>Unlike Rust's libtest, Go has a machine readable test protocol, so the results are read from the
 * tool's own event stream rather than from console text. Each line is one event:
 *
 * <pre>
 * {"Action":"run","Package":"example.com/ledger","Test":"TestFee"}
 * {"Action":"output","Package":"example.com/ledger","Test":"TestFee","Output":"--- FAIL: TestFee\n"}
 * {"Action":"fail","Package":"example.com/ledger","Test":"TestFee","Elapsed":0.01}
 * </pre>
 *
 * <p>A case is attributed to its package's module-qualified import path, which is the same value
 * {@code RequirementCodeMapper} derives for a Go test file, so an existing test can be traced to the
 * source that mentions the requirement. Package-level {@code fail} events with no test name are how
 * Go reports a package that could not be built; they are never turned into a passing case.
 *
 * <p>Failure output is accumulated from the {@code output} events that precede a case's terminal
 * event, so a counterexample carries the tool's own text rather than our reading of a log.
 */
public final class GoTestReportParser {

    private GoTestReportParser() {
    }

    /** Parses a {@code go test -json} run into an aggregate result. */
    public static TestResult parse(String stdout, String stderr, String note, List<Path> reports) {
        if (isBlank(stdout) && isBlank(stderr)) {
            return TestResult.notExecuted("the Go test runner produced no output");
        }
        Map<String, StringBuilder> outputs = new LinkedHashMap<>();
        Map<String, RawCase> cases = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Set<String> failedPackages = new LinkedHashSet<>();
        Set<String> packagesWithCases = new LinkedHashSet<>();
        StringBuilder packageFailureLog = new StringBuilder();
        Duration duration = Duration.ZERO;
        int parsedEvents = 0;

        for (String line : split(stdout)) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            Map<String, Object> event = GoJson.parseObject(trimmed);
            if (event == null) {
                continue;
            }
            parsedEvents++;
            // Go capitalises its event field names; the lower-case spellings are accepted too so a
            // future protocol change that only alters casing does not silently parse to nothing.
            String action = field(event, "Action", "action");
            String pkg = field(event, "Package", "package");
            String test = field(event, "Test", "test");
            String key = key(pkg, test);
            if ("output".equals(action)) {
                String output = field(event, "Output", "output");
                outputs.computeIfAbsent(key, ignored -> new StringBuilder()).append(output == null ? "" : output);
                if (isBlankOrNull(test)) {
                    packageFailureLog.append(output == null ? "" : output);
                }
                continue;
            }
            if ("build-output".equals(action)) {
                packageFailureLog.append(field(event, "Output", "output"));
                continue;
            }
            if ("build-fail".equals(action)) {
                failedPackages.add(pkg == null ? "<unknown>" : pkg);
                continue;
            }
            if (!"pass".equals(action) && !"fail".equals(action) && !"skip".equals(action)) {
                continue;
            }
            if (isBlankOrNull(test)) {
                if ("fail".equals(action)) {
                    failedPackages.add(pkg == null ? "<unknown>" : pkg);
                }
                continue;
            }
            packagesWithCases.add(pkg);
            if (!cases.containsKey(key)) {
                order.add(key);
            }
            cases.put(key, new RawCase(pkg, test, outcome(action), elapsed(event)));
            duration = duration.plus(elapsed(event));
        }

        if (cases.isEmpty()) {
            String combined = combine(stdout, stderr);
            if (parsedEvents == 0) {
                return TestResult.notExecuted("go test produced no parsable JSON event; last output: "
                        + GoTool.lastLines(combined, 4));
            }
            if (!failedPackages.isEmpty()) {
                return TestResult.notExecuted("go test could not build or run "
                        + String.join(", ", failedPackages) + ": "
                        + GoTool.lastLines(packageFailureLog.toString().isBlank()
                                ? combined : packageFailureLog.toString(), 6));
            }
            return TestResult.notExecuted("go test ran but reported no test case; last output: "
                    + GoTool.lastLines(combined, 4));
        }

        List<TestCaseResult> results = new ArrayList<>(order.size());
        for (String key : order) {
            RawCase testCase = cases.get(key);
            String text = output(outputs, key);
            boolean failed = testCase.outcome().isFailure();
            results.add(new TestCaseResult(
                    testCase.suite(), testCase.name(), testCase.outcome(), testCase.duration(),
                    failed ? failureType(text) : null,
                    failed ? text : null,
                    failed ? text : null));
        }
        String detail = note;
        if (!failedPackages.isEmpty()) {
            detail = (note == null ? "" : note + "; ") + "go test could not build or run "
                    + String.join(", ", failedPackages);
        }
        return TestResult.from(results, duration, reports, detail);
    }

    private static String output(Map<String, StringBuilder> outputs, String key) {
        StringBuilder sb = outputs.get(key);
        return sb == null ? null : sb.toString().strip();
    }

    private static String failureType(String text) {
        return text != null && text.contains("panic:") ? "panic" : "go test failure";
    }

    private static TestOutcome outcome(String action) {
        return switch (action) {
            case "pass" -> TestOutcome.PASSED;
            case "skip" -> TestOutcome.SKIPPED;
            default -> TestOutcome.FAILED;
        };
    }

    private static String key(String pkg, String test) {
        return (pkg == null ? "" : pkg) + '\u0000' + (test == null ? "" : test);
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    /** @return the first present string field, so both spellings of an event key are understood. */
    private static String field(Map<String, Object> event, String primary, String alternative) {
        String value = string(event.get(primary));
        return value != null ? value : string(event.get(alternative));
    }

    private static Duration elapsed(Map<String, Object> event) {
        return seconds(first(event, "Elapsed", "elapsed"));
    }

    private static Object first(Map<String, Object> event, String primary, String alternative) {
        Object value = event.get(primary);
        return value != null ? value : event.get(alternative);
    }

    private static Duration seconds(Object value) {
        if (value instanceof Double number) {
            return Duration.ofMillis((long) (number * 1000));
        }
        return Duration.ZERO;
    }

    private static boolean isBlankOrNull(String value) {
        return value == null || value.isBlank();
    }

    private static List<String> split(String text) {
        if (text == null) {
            return List.of();
        }
        return List.of(text.split("\\R", -1));
    }

    private static String combine(String stdout, String stderr) {
        if (isBlank(stdout)) {
            return stderr == null ? "" : stderr;
        }
        if (isBlank(stderr)) {
            return stdout;
        }
        return stdout + System.lineSeparator() + stderr;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** A case as read from its terminal event, before failure output is attached. */
    private record RawCase(String suite, String name, TestOutcome outcome, Duration duration) {
    }
}

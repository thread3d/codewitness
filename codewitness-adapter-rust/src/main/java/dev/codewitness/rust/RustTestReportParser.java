package dev.codewitness.rust;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the test output of Cargo's libtest harness into an aggregate result.
 *
 * <p>Rust's test runner has no JUnit XML report and the JSON format is unstable, so unlike the Java
 * and Python adapters the results are read from the runner's own line protocol. That protocol is
 * small and stable:
 *
 * <pre>
 * running 2 tests
 * test test_fee_is_added ... ok
 * test test_negative_amount ... FAILED
 *
 * failures:
 *
 * ---- test_negative_amount stdout ----
 * thread 'test_negative_amount' panicked at tests/ledger.rs:12:5:
 * CODEWITNESS_COUNTEREXAMPLE|case=0|inputs={amount=-10000}|...
 *
 * test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.01s
 * </pre>
 *
 * <p>Cargo splits the run across two streams: the case lines and failure detail go to standard
 * output, while the {@code Running tests/ledger.rs (...)} lines that name the target go to standard
 * error. Cargo runs the test binaries one after another, so the order of {@code Running} lines and
 * the order of {@code running N tests} blocks agree; this parser pairs them to attribute each case
 * to the test target it came from. That identifier — {@code ledger} for {@code tests/ledger.rs} — is
 * exactly what requirement mapping uses, so a test result can always be traced to its source file.
 */
public final class RustTestReportParser {

    private static final Pattern RUNNING = Pattern.compile("^\\s*Running\\s+(?:unittests\\s+)?(\\S+\\.rs)\\s+\\(");
    private static final Pattern RUNNING_TESTS = Pattern.compile("^running\\s+\\d+\\s+tests?$");
    private static final Pattern CASE = Pattern.compile("^test\\s+(.+?)\\s+\\.\\.\\.\\s+(ok|FAILED|ignored)\\s*$");
    private static final Pattern FAILURE_BLOCK = Pattern.compile("^----\\s+(.+?)\\s+(?:stdout|stderr)\\s+----\\s*$");
    private static final Pattern FINISHED = Pattern.compile("finished in ([0-9.]+)s");

    private RustTestReportParser() {
    }

    /** Parses a Cargo test run into an aggregate result. */
    public static TestResult parse(String stdout, String stderr, String note, List<Path> reports) {
        if (isBlank(stdout) && isBlank(stderr)) {
            return TestResult.notExecuted("the Rust test runner produced no output");
        }
        List<String> targets = targetSuites(stderr);
        List<RawCase> raw = new ArrayList<>();
        Map<String, StringBuilder> failureDetails = new LinkedHashMap<>();
        Duration duration = Duration.ZERO;
        String suite = null;
        int targetIndex = -1;
        StringBuilder currentDetail = null;

        for (String line : stdout.split("\\R", -1)) {
            if (RUNNING_TESTS.matcher(line.strip()).matches()) {
                targetIndex++;
                suite = targetIndex < targets.size() ? targets.get(targetIndex) : null;
                currentDetail = null;
                continue;
            }
            Matcher block = FAILURE_BLOCK.matcher(line);
            if (block.matches()) {
                currentDetail = failureDetails.computeIfAbsent(block.group(1), key -> new StringBuilder());
                continue;
            }
            if (currentDetail != null) {
                if (line.strip().equals("failures:") || line.strip().startsWith("test result:")) {
                    currentDetail = null;
                } else {
                    currentDetail.append(line).append('\n');
                    continue;
                }
            }
            Matcher finished = FINISHED.matcher(line);
            if (finished.find()) {
                duration = duration.plus(seconds(finished.group(1)));
            }
            Matcher testCase = CASE.matcher(line);
            if (testCase.matches() && suite != null) {
                TestOutcome outcome = switch (testCase.group(2)) {
                    case "ok" -> TestOutcome.PASSED;
                    case "ignored" -> TestOutcome.SKIPPED;
                    default -> TestOutcome.FAILED;
                };
                raw.add(new RawCase(suite, testCase.group(1).strip(), outcome));
            }
        }

        if (raw.isEmpty()) {
            return TestResult.notExecuted("cargo test ran but reported no test case; last output: "
                    + Cargo.lastLines(combine(stdout, stderr), 4));
        }
        // Failure detail blocks come after the case lines, so they are attached in a second pass.
        List<TestCaseResult> cases = new ArrayList<>(raw.size());
        for (RawCase testCase : raw) {
            StringBuilder detail = failureDetails.get(testCase.name());
            String text = detail == null ? null : detail.toString().strip();
            cases.add(new TestCaseResult(testCase.suite(), testCase.name(), testCase.outcome(), Duration.ZERO,
                    testCase.outcome() == TestOutcome.FAILED ? "panic" : null, text, text));
        }
        return TestResult.from(cases, duration, reports, note);
    }

    /**
     * @return the test target for each {@code Running} block, in order, with {@code null} for the
     *     documentation-test target, whose cases are not attributed to a requirement.
     */
    private static List<String> targetSuites(String stderr) {
        List<String> suites = new ArrayList<>();
        if (stderr == null || stderr.isBlank()) {
            return suites;
        }
        for (String line : stderr.split("\\R", -1)) {
            Matcher running = RUNNING.matcher(line);
            if (running.find()) {
                suites.add(stem(running.group(1)));
                continue;
            }
            if (line.strip().startsWith("Doc-tests ")) {
                suites.add(null);
            }
        }
        return suites;
    }

    /** @return the test target identifier for a source path, which is the file name without {@code .rs}. */
    static String stem(String path) {
        String name = path == null ? "" : path.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String file = slash < 0 ? name : name.substring(slash + 1);
        return file.endsWith(".rs") ? file.substring(0, file.length() - 3) : file;
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

    private static Duration seconds(String value) {
        try {
            return Duration.ofMillis((long) (Double.parseDouble(value) * 1000));
        } catch (NumberFormatException _) {
            return Duration.ZERO;
        }
    }

    /** A case as first read from the {@code test name ... outcome} line, before failure detail is attached. */
    private record RawCase(String suite, String name, TestOutcome outcome) {
    }
}

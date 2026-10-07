package dev.codewitness.ruby;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the JSON that RSpec's {@code --format json} formatter writes.
 *
 * <p>Reading RSpec's own report rather than scraping the progress dots it prints means the recorded
 * results are the runner's, not our reading of its console output. It is also what lets a generated
 * example's {@code CODEWITNESS_COUNTEREXAMPLE|...} line reach the engine: the line is in the failure's
 * message, and the message is carried as RSpec wrote it, up to the length bound below.
 *
 * <p>The report is one JSON object holding an {@code examples} array, a {@code summary} object and a
 * {@code version}. Each example names the file it came from as {@code file_path}, and that path is what
 * this parser turns into the suite name the engine maps against requirements: the leading {@code ./} is
 * dropped, the path separators become dots and the {@code .rb} extension is removed, so
 * {@code spec/ledger_spec.rb} becomes {@code spec.ledger_spec}. That is the identifier
 * {@code RequirementCodeMapper} builds for the same file when it scans the test tree, so a result can be
 * attributed to the requirement the test is about; a suite that kept RSpec's spelling would match no
 * requirement and the example would be executed but unattributable.
 *
 * <p>The example's own {@code id} ({@code ./spec/ledger_spec.rb[1:1]}) is deliberately not used here.
 * It is the identifier RSpec accepts for replay, but it is not the file's name, and a suite derived from
 * it would match nothing the engine looks up. Replay does not need it either: the full description is
 * what {@code --example} matches, and the full description is carried as the test case's name.
 *
 * <p>An example's outcome is its {@code status} and nothing else. RSpec attaches an {@code exception}
 * object to each failure, but its mere presence is not the outcome: the runner already stamped one, and
 * a parser that overrode it would be inventing a result. The exception's message and backtrace are kept
 * as the failure text, because that is where a failing example says what it saw - including the
 * counterexample line a generated test emits.
 *
 * <p>A report that cannot be read is never silently dropped and never turned into a pass: it is recorded
 * as an error case naming the parser failure, exactly as the PHP adapter records an unreadable JUnit
 * report. Malformed input therefore produces an observation, never an exception.
 */
public final class RubyTestReportParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * How much of a failure's message and backtrace is kept.
     *
     * <p>The PHPUnit parser carries the failure text verbatim, because the text is short and is where a
     * generated test puts its counterexample line. A Ruby backtrace is bounded by nothing, and the engine
     * embeds this text in reports and in LLM prompts, so the combined text is capped here. The cap
     * truncates the tail, after the message, so the message - the part a human reads and the part the
     * counterexample protocol lives in - always survives.
     */
    private static final int MESSAGE_LIMIT = 4_000;

    /** Appended to a failure text that was capped, so a reader can tell it was cut rather than ended. */
    private static final String TRUNCATION_MARK = "...";

    /** The suite the engine looks up when an example names no usable file. */
    private static final String UNKNOWN = "unknown";

    private RubyTestReportParser() {
    }

    /**
     * Parses RSpec JSON reports into an aggregate test result.
     *
     * @param reports     the report files to read; RSpec writes one, but the list is what the PHPUnit
     *                    parser takes and keeping it means a caller that ever collects several needs no
     *                    second parser
     * @param projectRoot root an absolute {@code file_path} is made relative to, or {@code null}
     * @param note        explanation carried into the report
     * @return the aggregated result, or "not executed" when no report contained an example. A report that
     *     could not be read contributes an error case rather than an exception
     */
    public static TestResult parse(List<Path> reports, Path projectRoot, String note) {
        List<TestCaseResult> cases = new ArrayList<>();
        JsonNode summary = null;
        for (Path report : reports) {
            try {
                JsonNode root = MAPPER.readTree(Files.readString(report, StandardCharsets.UTF_8));
                if (summary == null) {
                    summary = root.path("summary");
                }
                JsonNode examples = root.path("examples");
                if (examples.isArray()) {
                    for (JsonNode example : examples) {
                        cases.add(toCase(example, projectRoot));
                    }
                }
            } catch (Exception e) {
                // A report nobody could read is recorded as an error case, never silently dropped and
                // never turned into a pass: a malformed report is itself evidence about the run.
                cases.add(parseFailure(report.getFileName().toString(), e));
            }
        }
        if (cases.isEmpty()) {
            return TestResult.notExecuted("the RSpec JSON report contained no examples");
        }
        return aggregate(cases, summary, reports, note);
    }

    /**
     * Parses one RSpec JSON report file.
     *
     * @param report      the report file
     * @param projectRoot root an absolute {@code file_path} is made relative to, or {@code null}
     * @param note        explanation carried into the report
     * @return the result of the report
     */
    public static TestResult parse(Path report, Path projectRoot, String note) {
        return parse(List.of(report), projectRoot, note);
    }

    /**
     * Parses RSpec's JSON report from text.
     *
     * <p>This is the shape a test can drive without a filesystem, and the shape a caller uses when it has
     * already read the report itself. It reports exactly what the file form reports: empty input is "not
     * executed", unparseable input is an error case, and no input produces an exception.
     *
     * @param json        the report's text
     * @param projectRoot root an absolute {@code file_path} is made relative to, or {@code null}
     * @param note        explanation carried into the report
     * @return the parsed result
     */
    public static TestResult parse(String json, Path projectRoot, String note) {
        if (json == null || json.isBlank()) {
            return TestResult.notExecuted("the RSpec JSON report could not be read: it was empty");
        }
        List<TestCaseResult> cases = new ArrayList<>();
        JsonNode summary = null;
        try {
            JsonNode root = MAPPER.readTree(json);
            summary = root.path("summary");
            JsonNode examples = root.path("examples");
            if (examples.isArray()) {
                for (JsonNode example : examples) {
                    cases.add(toCase(example, projectRoot));
                }
            }
        } catch (Exception e) {
            // The name the test provider writes the report under, so a synthetic case reads the same
            // whether it came from the file or from text a caller already holds.
            cases.add(parseFailure("rspec.json", e));
        }
        if (cases.isEmpty()) {
            return TestResult.notExecuted("the RSpec JSON report contained no examples");
        }
        return aggregate(cases, summary, List.of(), note);
    }

    /** @return an error case recording that a report could not be parsed. */
    private static TestCaseResult parseFailure(String reportName, Exception e) {
        return new TestCaseResult(reportName, "report-parse-failure", TestOutcome.ERROR, Duration.ZERO,
                e.getClass().getSimpleName(), e.getMessage(), null);
    }

    private static TestCaseResult toCase(JsonNode example, Path projectRoot) {
        String suite = suiteOf(text(example.path("file_path")), projectRoot);
        String name = text(example.path("full_description"));
        if (name == null) {
            name = text(example.path("description"));
        }
        if (name == null) {
            name = UNKNOWN;
        }
        Duration duration = seconds(example.path("run_time"));

        String failureType = null;
        String failureMessage = null;
        String stackTrace = null;
        JsonNode exception = example.path("exception");
        if (exception.isObject()) {
            failureType = text(exception.path("class"));
            stackTrace = join(exception.path("backtrace"));
            failureMessage = failureText(text(exception.path("message")), stackTrace);
        }
        return new TestCaseResult(suite, name, outcome(text(example.path("status"))), duration,
                failureType, failureMessage, stackTrace);
    }

    /**
     * @param message  the exception's message, or {@code null}
     * @param backtrace the exception's backtrace joined by newlines, or {@code null}
     * @return the failure text: the message, with the backtrace appended when there is one, capped at
     *     {@value #MESSAGE_LIMIT} characters
     *
     * <p>The message comes first because it is the sentence a human reads and the line a generated test's
     * counterexample protocol writes; the backtrace follows because it is the part that can be made
     * arbitrarily long by a project CodeWitness treats as hostile.
     */
    private static String failureText(String message, String backtrace) {
        String combined = message == null ? backtrace
                : backtrace == null ? message : message + System.lineSeparator() + backtrace;
        return clip(combined);
    }

    /**
     * @param value text to bound, possibly {@code null} or blank
     * @return the text with surrounding whitespace removed and the tail dropped past
     *     {@value #MESSAGE_LIMIT} characters, or {@code null} when nothing is left
     */
    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() <= MESSAGE_LIMIT) {
            return text;
        }
        // A cut inside a surrogate pair would leave half a character, so the split point is moved back
        // rather than repaired afterwards.
        int end = MESSAGE_LIMIT;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + TRUNCATION_MARK;
    }

    /** @return the backtrace lines joined by newlines, or {@code null} when there are none. */
    private static String join(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode line : array) {
            if (!line.isTextual()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(System.lineSeparator());
            }
            sb.append(line.asText());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * @param status the example's {@code status}, possibly {@code null}
     * @return the model's outcome. RSpec's three statuses map one to one; anything else - a status a
     *     newer RSpec may add, or none at all - is an error, because an example whose outcome this
     *     parser does not understand did not demonstrably pass, and evidence that was not observed must
     *     not be counted as success
     */
    private static TestOutcome outcome(String status) {
        if (status == null) {
            return TestOutcome.ERROR;
        }
        return switch (status.strip().toLowerCase(Locale.ROOT)) {
            case "passed" -> TestOutcome.PASSED;
            case "failed" -> TestOutcome.FAILED;
            case "pending" -> TestOutcome.SKIPPED;
            default -> TestOutcome.ERROR;
        };
    }

    /**
     * Builds the aggregate from the cases and RSpec's own summary.
     *
     * <p>The counters come from {@code summary} rather than from the cases, because that is the runner's
     * account of the same run and it is the only place the errors that happened outside any example are
     * counted. {@code example_count} covers the examples only, so the passes are what is left of it after
     * the failures and the pending examples; {@code errors_outside_of_examples_count} is carried as the
     * error count without being subtracted from that total, because those errors are additional to the
     * examples rather than among them. A report with no summary - one this parser had to substitute a
     * case for - falls back to counting the cases.
     *
     * @param cases   the parsed examples
     * @param summary the report's {@code summary} object, or {@code null} when there was none
     * @param reports the report files the cases came from
     * @param note    explanation carried into the report
     * @return the aggregated result
     */
    private static TestResult aggregate(List<TestCaseResult> cases, JsonNode summary,
            List<Path> reports, String note) {
        if (summary == null || !summary.isObject()) {
            Duration total = Duration.ZERO;
            for (TestCaseResult testCase : cases) {
                total = total.plus(testCase.duration());
            }
            return TestResult.from(cases, total, reports, note);
        }
        int total = count(summary, "example_count", cases.size());
        int failed = count(summary, "failure_count", 0);
        int skipped = count(summary, "pending_count", 0);
        int errors = count(summary, "errors_outside_of_examples_count", 0);
        int passed = Math.max(0, total - failed - skipped);
        return new TestResult(true, note, total, passed, failed, errors, skipped,
                seconds(summary.path("duration")), cases, reports);
    }

    /** @return a non-negative counter from the summary, or {@code fallback} when it is absent. */
    private static int count(JsonNode summary, String field, int fallback) {
        JsonNode node = summary.path(field);
        return node.isNumber() ? Math.max(0, node.asInt(fallback)) : fallback;
    }

    /** @return the number of seconds a JSON number denotes, or zero when it is not a usable number. */
    private static Duration seconds(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return Duration.ZERO;
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos((long) (value * 1_000_000_000L));
    }

    /**
     * @param filePath    RSpec's {@code file_path} for an example, or {@code null}
     * @param projectRoot root an absolute path is made relative to, or {@code null}
     * @return the suite name the engine maps a requirement through: the path with the leading {@code ./}
     *     dropped, its separators turned into dots and its {@code .rb} extension removed. An absolute
     *     path that does not lie under the project root yields {@code "unknown"} rather than a dotted
     *     absolute path, because such a path is not a name this project's tests can be attributed
     *     through
     */
    static String suiteOf(String filePath, Path projectRoot) {
        if (filePath == null) {
            return UNKNOWN;
        }
        String path = filePath.strip();
        if (path.isEmpty()) {
            return UNKNOWN;
        }
        try {
            Path candidate = Path.of(path);
            if (candidate.isAbsolute()) {
                if (projectRoot == null) {
                    return UNKNOWN;
                }
                Path root = projectRoot.toAbsolutePath().normalize();
                if (!candidate.normalize().startsWith(root)) {
                    return UNKNOWN;
                }
                path = root.relativize(candidate.normalize()).toString();
            }
        } catch (RuntimeException _) {
            // Not a path the platform can parse: it is used as it was written rather than discarded.
        }
        String name = path.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.endsWith(".rb")) {
            name = name.substring(0, name.length() - ".rb".length());
        }
        name = name.replace('/', '.');
        return name.isBlank() ? UNKNOWN : name;
    }

    /** @return the node's text, or {@code null} when it is missing, null or blank. */
    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /** Deletes a previous report so that a stale one can never be mistaken for this run's results. */
    public static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException _) {
            // best effort: a stale report would only add noise
        }
    }
}

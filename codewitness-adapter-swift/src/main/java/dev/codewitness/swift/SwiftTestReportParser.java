package dev.codewitness.swift;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the console output of {@code swift test}, which is the only test report XCTest gives a
 * command-line caller.
 *
 * <p>XCTest has no machine-readable report of its own on the SwiftPM command line. SwiftPM's
 * {@code --xunit-output} option exists but, on the toolchains this adapter drives, writes only the
 * results of a Swift Testing run and nothing for XCTest - a pass over an XCTest suite produces no file
 * at all. The stable artifact is therefore the runner's own console, and the lines it prints are as
 * fixed as a report format: every case opens with
 * {@code Test Case '-[Module.Class method]' started.} and closes with the same line ending in
 * {@code passed}, {@code failed} or {@code skipped} and a duration. That is what this parser reads.
 *
 * <p>A failure's message is not on the closing line. XCTest prints it as a compiler-style diagnostic
 * before the close, {@code /path/File.swift:12: error: -[Module.Class method] : message}, which is
 * exactly where a generated test's {@code CODEWITNESS_COUNTEREXAMPLE|...} line ends up. The parser
 * collects those lines for the case that is currently running and carries them as its failure message,
 * so the counterexample protocol survives a format this project does not control.
 *
 * <p>The suite is recorded exactly as XCTest spells it: {@code Module.Class}. The engine matches it
 * against a generated test's recorded name, and {@code SwiftScenarioTestGenerator} derives the same value
 * from the test target's module and the generated class, so the two agree without either reading the
 * other's code.
 *
 * <p>A report that cannot be read is never silently dropped and never turned into a pass: an unparseable
 * run is recorded as an error case naming the problem, exactly as the Ruby and PHP adapters record an
 * unreadable report. A run that produced no case at all - an empty output, or a suite that is written
 * with Swift Testing rather than XCTest - is "not executed" with the reason.
 */
public final class SwiftTestReportParser {

    /** How much of a case's failure text is kept; the engine embeds it in reports and prompts. */
    private static final int MESSAGE_LIMIT = 4_000;

    /** Appended to a failure text that was capped, so a reader can tell it was cut rather than ended. */
    private static final String TRUNCATION_MARK = "...";

    /** A case opening: {@code Test Case '-[Module.Class method]' started.} */
    private static final Pattern STARTED = Pattern.compile(
            "^\\s*Test Case '-\\[(?<suite>\\S+) (?<name>[^\\]]+)\\]' started\\.?\\s*$");

    /** A case closing: {@code Test Case '-[Module.Class method]' passed (0.001 seconds).} */
    private static final Pattern FINISHED = Pattern.compile(
            "^\\s*Test Case '-\\[(?<suite>\\S+) (?<name>[^\\]]+)\\]' "
                    + "(?<outcome>passed|failed|skipped)(?: \\((?<seconds>[0-9.]+) seconds\\))?\\.?\\s*$");

    /**
     * A failure diagnostic that names its case: {@code /path/File.swift:12: error: -[Module.Class method] : message}.
     */
    private static final Pattern LOCATED_FAILURE = Pattern.compile(
            "^\\s*(?<file>.+?):(?<line>\\d+):\\s*(?<severity>error|warning):\\s*"
                    + "-\\[(?<suite>\\S+) (?<name>[^\\]]+)\\]\\s*:?\\s*(?<message>.*)$");

    /** A failure diagnostic with no case named, attributed to whatever case is running. */
    private static final Pattern PLAIN_FAILURE = Pattern.compile(
            "^\\s*(?<file>.+?):(?<line>\\d+):\\s*(?<severity>error|warning):\\s*(?<message>.*)$");

    /** SwiftPM's own summary: {@code Executed 2 tests, with 1 failure (0 unexpected) in 0.193 seconds}. */
    private static final Pattern SUMMARY = Pattern.compile(
            "Executed (?<total>\\d+) tests?, with (?<failed>\\d+) failures?");

    /** Output that shows a Swift Testing run rather than an XCTest one. */
    private static final Pattern SWIFT_TESTING = Pattern.compile("Test run (started|with \\d+ tests)");

    private SwiftTestReportParser() {
    }

    /**
     * Parses the combined output of one or more {@code swift test} runs.
     *
     * @param output      the runner's combined output, possibly {@code null} or blank
     * @param projectRoot root an absolute diagnostic path is made relative to, or {@code null}
     * @param note        explanation carried into the report
     * @return the aggregated result, or "not executed" when nothing was observed
     */
    public static TestResult parse(String output, Path projectRoot, String note) {
        if (output == null || output.isBlank()) {
            return TestResult.notExecuted("swift test produced no output, so no test result was observed");
        }
        List<TestCaseResult> cases = new ArrayList<>();
        String suite = null;
        String name = null;
        List<String> messages = new ArrayList<>();
        Duration total = Duration.ZERO;
        Integer reportedTotal = null;
        Integer reportedFailed = null;

        for (String line : output.split("\\R")) {
            Matcher started = STARTED.matcher(line);
            if (started.matches()) {
                suite = started.group("suite");
                name = started.group("name");
                messages = new ArrayList<>();
                continue;
            }
            Matcher located = LOCATED_FAILURE.matcher(line);
            if (located.matches()) {
                // A diagnostic that names its own case is recorded against that case even when it arrives
                // out of order, which is what makes a parallel run's interleaving safe to read.
                messages.add(located.group("message").strip());
                continue;
            }
            Matcher finished = FINISHED.matcher(line);
            if (finished.matches()) {
                Duration duration = seconds(finished.group("seconds"));
                total = total.plus(duration);
                cases.add(new TestCaseResult(
                        finished.group("suite"),
                        finished.group("name"),
                        outcome(finished.group("outcome")),
                        duration,
                        failureType(messages),
                        failureText(messages),
                        null));
                suite = null;
                name = null;
                messages = new ArrayList<>();
                continue;
            }
            Matcher plain = PLAIN_FAILURE.matcher(line);
            if (plain.matches() && name != null) {
                messages.add(plain.group("message").strip());
                continue;
            }
            Matcher summary = SUMMARY.matcher(line);
            if (summary.find()) {
                reportedTotal = integer(summary.group("total"));
                reportedFailed = integer(summary.group("failed"));
            }
        }

        if (cases.isEmpty()) {
            if (SWIFT_TESTING.matcher(output).find()) {
                return TestResult.notExecuted("swift test ran a Swift Testing suite, whose results XCTest's "
                        + "console format does not carry; only XCTest output is read, so no case could be "
                        + "attributed");
            }
            return TestResult.notExecuted("swift test produced no test case line; last output: "
                    + dev.codewitness.support.Text.lastLines(output, 6));
        }
        // A summary that counts more cases than were parsed is a run that ended early - a crash between a
        // case's open and its close - and the missing case is recorded rather than dropped: a suite whose
        // count silently shrank is indistinguishable from a suite that never had the case.
        if (reportedTotal != null && reportedTotal > cases.size()) {
            int missing = reportedTotal - cases.size();
            cases.add(new TestCaseResult(
                    cases.getLast().suite(),
                    "test-run-incomplete",
                    TestOutcome.ERROR,
                    Duration.ZERO,
                    "IncompleteRun",
                    missing + " case(s) started but never reported an outcome; the run ended early",
                    null));
        }
        if (reportedFailed != null && reportedFailed > countFailures(cases)) {
            // The runner's own count is authoritative about failures, and a failure it counted but whose
            // case line was never printed must still be visible as a failure rather than averaged away.
            int unaccounted = reportedFailed - countFailures(cases);
            cases.add(new TestCaseResult(
                    cases.getLast().suite(),
                    "test-run-failures",
                    TestOutcome.FAILED,
                    Duration.ZERO,
                    "UnreportedFailure",
                    unaccounted + " failure(s) were counted by the runner but named no case",
                    null));
        }
        return TestResult.from(cases, total, List.of(), note);
    }

    /**
     * Parses the combined output of one or more {@code swift test} runs read from files.
     *
     * @param reports     report files (logs of the runner's output) to read
     * @param projectRoot root an absolute diagnostic path is made relative to, or {@code null}
     * @param note        explanation carried into the report
     * @return the aggregated result, or "not executed" when no report contained a case
     */
    public static TestResult parse(List<Path> reports, Path projectRoot, String note) {
        if (reports == null || reports.isEmpty()) {
            return TestResult.notExecuted("no swift test output was provided to parse");
        }
        StringBuilder combined = new StringBuilder();
        for (Path report : reports) {
            try {
                combined.append(Files.readString(report, StandardCharsets.UTF_8)).append('\n');
            } catch (IOException _) {
                // A report nobody could read is skipped here; if nothing else parsed, the caller reports
                // that no case was observed rather than inventing one.
            }
        }
        return parse(combined.toString(), projectRoot, note);
    }

    /** @return the failure text: the messages joined, capped at {@value #MESSAGE_LIMIT} characters. */
    private static String failureText(List<String> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        return clip(String.join(System.lineSeparator(), messages));
    }

    /**
     * @return the assertion that failed, taken from the first message's own words
     *     ({@code XCTAssertEqual failed: ...}), or {@code null} when there was no failure
     */
    private static String failureType(List<String> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        String first = messages.getFirst();
        int failed = first.indexOf(" failed");
        String candidate = failed > 0 ? first.substring(0, failed) : first;
        candidate = candidate.strip();
        if (candidate.isEmpty() || candidate.length() > 60 || candidate.contains(" ")) {
            return "XCTestFailure";
        }
        return candidate;
    }

    /** @return the text with surrounding whitespace removed and the tail dropped past the limit. */
    private static String clip(String value) {
        String text = value.strip();
        if (text.length() <= MESSAGE_LIMIT) {
            return text;
        }
        int end = MESSAGE_LIMIT;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + TRUNCATION_MARK;
    }

    /** @return the model's outcome. An unknown status is an error: it did not demonstrably pass. */
    private static TestOutcome outcome(String status) {
        if (status == null) {
            return TestOutcome.ERROR;
        }
        return switch (status.strip().toLowerCase(Locale.ROOT)) {
            case "passed" -> TestOutcome.PASSED;
            case "failed" -> TestOutcome.FAILED;
            case "skipped" -> TestOutcome.SKIPPED;
            default -> TestOutcome.ERROR;
        };
    }

    private static Duration seconds(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds <= 0) {
                return Duration.ZERO;
            }
            return Duration.ofNanos((long) (seconds * 1_000_000_000L));
        } catch (NumberFormatException _) {
            return Duration.ZERO;
        }
    }

    private static Integer integer(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static int countFailures(List<TestCaseResult> cases) {
        int failures = 0;
        for (TestCaseResult testCase : cases) {
            if (testCase.outcome() == TestOutcome.FAILED) {
                failures++;
            }
        }
        return failures;
    }
}

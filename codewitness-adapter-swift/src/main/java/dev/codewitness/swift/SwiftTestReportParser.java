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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * fixed as a report format. This parser reads both dialects of that format, because they are not the
 * same text: Apple's XCTest, which a macOS toolchain ships, prints every case as
 * {@code Test Case '-[Module.Class method]' started.} and closes it with the same line ending in
 * {@code passed}, {@code failed} or {@code skipped} and a duration, while swift-corelibs-xctest, which a
 * Linux toolchain ships, prints {@code Test Case 'Module.Class.method' started at <date>} and closes it
 * the same way. Reading only Apple's spelling would make every suite on a Linux runner look like it
 * never ran a case, which is why {@link #identify(String)} normalises the case token from either form to
 * one suite and method name.
 *
 * <p>A failure's message is not on the closing line. XCTest prints it as a compiler-style diagnostic
 * before the close, {@code /path/File.swift:12: error: -[Module.Class method] : message} on Apple's
 * XCTest and {@code /path/File.swift:12: error: Module.Class.method : message} on corelibs, which is
 * exactly where a generated test's {@code CODEWITNESS_COUNTEREXAMPLE|...} line ends up. The parser
 * records those lines against the case each one names and carries them as that case's failure message,
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

    /** The method name recorded when a case token names no method this parser can decompose. */
    private static final String UNKNOWN = "unknown";

    /**
     * A case line, in either of XCTest's two console dialects.
     *
     * <p>Apple's XCTest prints a case as {@code Test Case '-[Module.Class method]' started.} and
     * {@code … passed (0.001 seconds).}, while swift-corelibs-xctest - the XCTest a Linux toolchain
     * ships - prints the same case as {@code Test Case 'Module.Class.method' started at <date>} and
     * {@code … passed (0.001 seconds)}: no square-bracket wrapper, a dot-separated name, a timestamp
     * after "started", and no trailing period. Both name the case identically in the failure line, and
     * only the token and the words after it differ, so one pattern captures both and
     * {@link #identify(String)} normalises the token. Reading only Apple's spelling would make every
     * suite on a Linux runner look like it never ran a case.
     */
    private static final Pattern CASE = Pattern.compile("^\\s*Test Case '(?<token>.+?)' (?<rest>.*)$");

    /**
     * A failure diagnostic, in either dialect:
     * {@code /path/File.swift:12: error: -[Module.Class method] : message} on Apple's XCTest and
     * {@code /path/File.swift:12: error: Module.Class.method : message} on corelibs.
     *
     * <p>The token between the severity and the message is the case, in the same two spellings
     * {@link #CASE} handles; it is non-greedy up to the first {@code " : "}, which is the separator in
     * both dialects and is not something a case name contains.
     */
    private static final Pattern FAILURE = Pattern.compile(
            "^\\s*(?<file>.+?):(?<line>\\d+):\\s*(?<severity>error|warning):\\s*"
                    + "(?<token>.+?)\\s*:\\s*(?<message>.*)$");

    /** The duration XCTest prints in a case's closing line, in either dialect. */
    private static final Pattern CASE_SECONDS = Pattern.compile("\\((?<seconds>[0-9.]+) seconds\\)");

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
        // Failure messages are keyed by the case the failure line names, not by "the case currently
        // running": both dialects name the case on the failure line itself. That is what makes a parallel
        // run's interleaved output safe to read, and it means a case whose opening line was never printed
        // still carries its failure text.
        Map<String, List<String>> messagesByCase = new LinkedHashMap<>();
        Duration total = Duration.ZERO;
        Integer reportedTotal = null;
        Integer reportedFailed = null;

        for (String line : output.split("\\R")) {
            Matcher failure = FAILURE.matcher(line);
            if (failure.matches()) {
                CaseId failed = identify(failure.group("token"));
                if (failed != null) {
                    messagesByCase.computeIfAbsent(failed.key(), key -> new ArrayList<>())
                            .add(failure.group("message").strip());
                }
                continue;
            }
            Matcher caseLine = CASE.matcher(line);
            if (caseLine.matches()) {
                CaseId id = identify(caseLine.group("token"));
                String rest = caseLine.group("rest").strip();
                if (id == null || rest.startsWith("started")) {
                    // An opening line only names the case; it carries no result.
                    continue;
                }
                TestOutcome outcome = outcomeOf(rest);
                if (outcome == null) {
                    // A line the dialect prints for something other than a result - a performance
                    // measurement, say - is not a case result and is not invented into one.
                    continue;
                }
                List<String> messages = messagesByCase.getOrDefault(id.key(), List.of());
                Duration duration = seconds(firstGroup(CASE_SECONDS, rest));
                total = total.plus(duration);
                cases.add(new TestCaseResult(
                        id.suite(), id.name(), outcome, duration,
                        failureType(messages), failureText(messages), null));
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

    /**
     * @return the outcome a case's closing line states, or {@code null} when the line is not a result.
     *
     * <p>An unknown status is not mapped to an error here: a line that is not a case result at all - a
     * performance measurement, say - must be left alone rather than recorded as a case that failed. A
     * case whose outcome cannot be read is still recorded, by the summary reconciliation below, as an
     * error rather than as a pass.
     */
    private static TestOutcome outcomeOf(String rest) {
        if (rest == null) {
            return null;
        }
        String value = rest.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("passed")) {
            return TestOutcome.PASSED;
        }
        if (value.startsWith("failed")) {
            return TestOutcome.FAILED;
        }
        if (value.startsWith("skipped")) {
            return TestOutcome.SKIPPED;
        }
        return null;
    }

    /**
     * @param token the case token from a case or failure line
     * @return the case's suite and name, in either dialect, or {@code null} when the token names nothing
     *
     * <p>Apple's XCTest wraps the case in {@code -[Module.Class method]}, so the suite ends at the last
     * space; corelibs prints {@code Module.Class.method}, so it ends at the last dot. A token with
     * neither is kept whole as the suite with an unknown method, which is the honest reading of a line
     * this parser cannot decompose rather than a guessed class name.
     */
    static CaseId identify(String token) {
        if (token == null) {
            return null;
        }
        String text = token.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("-[") && text.endsWith("]")) {
            text = text.substring(2, text.length() - 1).strip();
            int space = text.lastIndexOf(' ');
            if (space <= 0 || space == text.length() - 1) {
                return new CaseId(text, UNKNOWN);
            }
            return new CaseId(text.substring(0, space).strip(), text.substring(space + 1).strip());
        }
        int dot = text.lastIndexOf('.');
        if (dot <= 0 || dot == text.length() - 1) {
            return new CaseId(text, UNKNOWN);
        }
        return new CaseId(text.substring(0, dot).strip(), text.substring(dot + 1).strip());
    }

    /** A case's suite and method name, normalised across XCTest's two console dialects. */
    record CaseId(String suite, String name) {

        /** @return a key two spellings of the same case share, so a failure line and its case line pair. */
        String key() {
            return suite + "|" + name;
        }
    }

    /** @return the first capture of a pattern in the text, or {@code null} when it does not match. */
    private static String firstGroup(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
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

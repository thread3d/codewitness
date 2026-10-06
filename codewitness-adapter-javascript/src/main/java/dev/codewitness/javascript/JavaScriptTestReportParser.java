package dev.codewitness.javascript;

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
 * Parses the JUnit XML that Node's own test runner produces.
 *
 * <p>Node has a machine readable reporter, so unlike the Rust adapter this adapter does not parse a
 * console protocol: the test phase runs {@code node --test --test-reporter=junit} and reads the file it
 * writes. The format is small:
 *
 * <pre>
 * &lt;testsuites&gt;
 *   &lt;testcase name="deposit adds" time="0.0009" classname="test" file="/p/tests/ledger.test.js"/&gt;
 *   &lt;testcase name="rejects zero" time="0.001" classname="test" file=".../ledger.test.js"&gt;
 *     &lt;failure type="testCodeFailure" message="..."/&gt;
 *   &lt;/testcase&gt;
 * &lt;/testsuites&gt;
 * </pre>
 *
 * <p>Node reports the test <em>file</em> and the case <em>name</em>, so the suite is derived from the
 * file: the extension and the {@code .test}/{@code .spec} marker are dropped, which is the same
 * identifier {@link dev.codewitness.core.engine.RequirementCodeMapper} builds when it scans the same
 * file. A generated test's suite is therefore exactly the class name the generator chose, which is what
 * lets the engine attribute a failure to the obligation that produced it.
 *
 * <p>The runner wraps a failing case in several XML levels, so the failure message is taken from the
 * first place it appears — the {@code failure} attribute — and only falls back to the nested element.
 * Entities are decoded before the counterexample protocol is read, because the protocol line contains
 * {@code <=} and {@code <} is escaped in the report.
 */
public final class JavaScriptTestReportParser {

    private static final Pattern TESTCASE = Pattern.compile("<testcase\\b([^>]*?)(/?)>", Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z_][A-Za-z0-9_:.-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);");
    private static final Pattern FAILURE_ATTRIBUTE = Pattern.compile(
            "<failure\\b([^>]*)>", Pattern.DOTALL);
    private static final Pattern ERROR_ELEMENT = Pattern.compile("<error\\b");
    private static final Pattern SKIPPED_ELEMENT = Pattern.compile("<skipped\\b");

    private JavaScriptTestReportParser() {
    }

    /** Parses a JUnit XML report file written by Node's test runner. */
    public static TestResult parse(Path report, String note, List<Path> reports) {
        String xml;
        try {
            xml = Files.readString(report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return TestResult.notExecuted("cannot read the Node test report " + report + ": " + e);
        }
        return parse(xml, note, reports);
    }

    /** Parses JUnit XML text. */
    public static TestResult parse(String xml, String note, List<Path> reports) {
        if (xml == null || xml.isBlank()) {
            return TestResult.notExecuted("the Node test runner produced no JUnit report");
        }
        List<TestCaseResult> cases = new ArrayList<>();
        Duration duration = Duration.ZERO;
        Matcher matcher = TESTCASE.matcher(xml);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            boolean selfClosing = matcher.group(2).equals("/");
            Map<String, String> values = attributes(attributes);
            String name = values.getOrDefault("name", "<unnamed>");
            String suite = suiteOf(values.get("file"), values.get("classname"));

            String body = "";
            if (!selfClosing) {
                int end = xml.indexOf("</testcase>", matcher.end());
                body = end < 0 ? xml.substring(matcher.end()) : xml.substring(matcher.end(), end);
            }
            TestOutcome outcome = outcome(values, body);
            Duration caseDuration = seconds(values.get("time"));
            duration = duration.plus(caseDuration);
            String failure = outcome.isFailure() ? failureText(values, body) : null;
            cases.add(new TestCaseResult(suite, name, outcome, caseDuration,
                    outcome.isFailure() ? failureType(values, body) : null, failure, failure));
        }
        if (cases.isEmpty()) {
            return TestResult.notExecuted("node --test ran but its JUnit report contained no test case; last "
                    + "report content: " + summarize(xml));
        }
        return TestResult.from(cases, duration, reports, note);
    }

    private static TestOutcome outcome(Map<String, String> values, String body) {
        if (values.containsKey("failure") || body.contains("<failure")) {
            return TestOutcome.FAILED;
        }
        if (ERROR_ELEMENT.matcher(body).find() || values.containsKey("error")) {
            return TestOutcome.ERROR;
        }
        if (SKIPPED_ELEMENT.matcher(body).find() || values.containsKey("skipped")) {
            return TestOutcome.SKIPPED;
        }
        return TestOutcome.PASSED;
    }

    private static String failureType(Map<String, String> values, String body) {
        Matcher failure = FAILURE_ATTRIBUTE.matcher(body);
        if (failure.find()) {
            String type = attributes(failure.group(1)).get("type");
            if (type != null && !type.isBlank()) {
                return type;
            }
        }
        return "testCodeFailure";
    }

    private static String failureText(Map<String, String> values, String body) {
        String attribute = values.get("failure");
        if (attribute != null && !attribute.isBlank()) {
            return attribute;
        }
        Matcher failure = FAILURE_ATTRIBUTE.matcher(body);
        if (failure.find()) {
            String message = attributes(failure.group(1)).get("message");
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        int end = body.indexOf('>');
        return end < 0 ? body : body.substring(end + 1).strip();
    }

    private static Map<String, String> attributes(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null) {
            return values;
        }
        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            values.put(matcher.group(1), unescape(matcher.group(2)));
        }
        return values;
    }

    /**
     * @param file      the test file path Node reported, or {@code null}
     * @param classname the suite name Node reported, or {@code null}
     * @return the identifier the core maps a test to: the file stem without its extension or its
     *     {@code .test}/{@code .spec} marker, falling back to the reported class name
     */
    public static String suiteOf(String file, String classname) {
        if (file != null && !file.isBlank()) {
            String name = file.replace('\\', '/');
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            if (name.endsWith(".test") || name.endsWith(".spec")) {
                name = name.substring(0, name.length() - 5);
            }
            if (!name.isBlank()) {
                return name;
            }
        }
        if (classname != null && !classname.isBlank() && !classname.equals("test")) {
            return classname;
        }
        return "test";
    }

    /** Decodes the XML entities Node escapes in an attribute value or element body. */
    static String unescape(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value;
        }
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                replacement = codePoint(entity.substring(2), 16);
            } else if (entity.startsWith("#")) {
                replacement = codePoint(entity.substring(1), 10);
            } else {
                replacement = switch (entity.toLowerCase(Locale.ROOT)) {
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "amp" -> "&";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    default -> matcher.group(0);
                };
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String codePoint(String value, int radix) {
        try {
            return new String(Character.toChars(Integer.parseInt(value, radix)));
        } catch (RuntimeException _) {
            return "";
        }
    }

    private static Duration seconds(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        try {
            return Duration.ofNanos((long) (Double.parseDouble(value.trim()) * 1_000_000_000L));
        } catch (NumberFormatException _) {
            return Duration.ZERO;
        }
    }

    private static String summarize(String xml) {
        String text = xml.strip().replaceAll("\\s+", " ");
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}

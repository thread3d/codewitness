package dev.codewitness.php;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the JUnit XML that PHPUnit's {@code --log-junit} option writes.
 *
 * <p>Reading PHPUnit's own report rather than scraping its console output means the recorded results
 * are the runner's, not our reading of its log. It is also what lets a generated test's
 * {@code CODEWITNESS_COUNTEREXAMPLE|...} line reach the engine: the line is in the failure text, and
 * the failure text is carried verbatim.
 *
 * <p>PHPUnit nests {@code <testsuite>} elements - the outermost names the configuration file, then the
 * configured suite, then the test class - so this parser collects {@code <testcase>} elements from the
 * whole document rather than from a single {@code <testsuite>}. The aggregate duration is the sum of the
 * test case times for the same reason: summing the suite times would count a nested suite's cases once
 * per enclosing suite.
 *
 * <p>A test class is reported twice, as {@code classname} with dots and as {@code class} with
 * backslashes. The dotted {@code classname} is preferred and every backslash is normalised to a dot,
 * because the dotted form is the name the engine's requirement mapping derives for a PHP test file; a
 * suite name that kept PHP's own spelling would match no requirement and the test would be executed but
 * unattributable.
 */
public final class PhpTestReportParser {

    /** Elements inside a {@code <testcase>} that carry no outcome and are therefore ignored. */
    private static final List<String> OUTPUT_ELEMENTS = List.of("system-out", "system-err");

    private PhpTestReportParser() {
    }

    /**
     * Parses a list of JUnit XML reports into an aggregate result.
     *
     * @param reports     the report files to read
     * @param projectRoot root the fallback suite name is derived relative to, or {@code null}
     * @param note        explanation carried into the report
     * @return the aggregated result, or "not executed" when no report contained a test case
     */
    public static TestResult parse(List<Path> reports, Path projectRoot, String note) {
        List<TestCaseResult> cases = new ArrayList<>();
        Duration total = Duration.ZERO;
        for (Path report : reports) {
            try {
                Document document = parseXml(report);
                NodeList testCases = document.getElementsByTagName("testcase");
                for (int i = 0; i < testCases.getLength(); i++) {
                    TestCaseResult testCase = toCase((Element) testCases.item(i), projectRoot);
                    cases.add(testCase);
                    total = total.plus(testCase.duration());
                }
            } catch (Exception e) {
                // A report nobody could read is recorded as an error case, never silently dropped and
                // never turned into a pass: a malformed report is itself evidence about the run.
                cases.add(new TestCaseResult(report.getFileName().toString(), "report-parse-failure",
                        TestOutcome.ERROR, Duration.ZERO, e.getClass().getSimpleName(), e.getMessage(), null));
            }
        }
        if (cases.isEmpty()) {
            return TestResult.notExecuted("the PHPUnit JUnit report contained no test cases");
        }
        return TestResult.from(cases, total, reports, note);
    }

    private static TestCaseResult toCase(Element testCase, Path projectRoot) {
        String suite = normalise(attribute(testCase, "classname"));
        if (suite == null) {
            suite = normalise(attribute(testCase, "class"));
        }
        if (suite == null) {
            suite = suiteFromFile(attribute(testCase, "file"), projectRoot);
        }
        if (suite == null) {
            suite = "unknown";
        }
        String name = attribute(testCase, "name");
        if (name == null) {
            name = "unknown";
        }
        Duration duration = seconds(attribute(testCase, "time"));

        NodeList children = testCase.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String tag = element.getTagName().toLowerCase(Locale.ROOT);
            switch (tag) {
                case "failure" -> {
                    // The text is verbatim: it is where a generated test puts its counterexample line.
                    return new TestCaseResult(suite, name, TestOutcome.FAILED, duration,
                            attribute(element, "type"), failureText(element), element.getTextContent());
                }
                case "error" -> {
                    return new TestCaseResult(suite, name, TestOutcome.ERROR, duration,
                            attribute(element, "type"), failureText(element), element.getTextContent());
                }
                case "skipped" -> {
                    return new TestCaseResult(suite, name, TestOutcome.SKIPPED, duration, null,
                            attribute(element, "message"), null);
                }
                default -> {
                    if (!OUTPUT_ELEMENTS.contains(tag)) {
                        // Any other outcome element a newer PHPUnit may add is not a pass: a case whose
                        // outcome this parser does not understand did not demonstrably run, and evidence
                        // that was not observed must not be counted as success.
                        return new TestCaseResult(suite, name, TestOutcome.SKIPPED, duration, null,
                                attribute(element, "message"), null);
                    }
                }
            }
        }
        return new TestCaseResult(suite, name, TestOutcome.PASSED, duration, null, null, null);
    }

    /**
     * @return the failure's message: the element's {@code message} attribute when the report has one,
     *     otherwise the element's own text.
     *
     * <p>PHPUnit puts the assertion's text in the element body and does not write a {@code message}
     * attribute, so a parser that only read the attribute would hand the engine a null message for every
     * PHP failure - and the counterexample the engine reports would read "test failed" instead of naming
     * what the runner actually said. Either source is the tool's own words, so neither is an invention.
     */
    private static String failureText(Element element) {
        String message = attribute(element, "message");
        return message != null ? message : element.getTextContent();
    }

    /**
     * @return the suite name derived from the test file's path relative to the project root, or
     *     {@code null} when PHPUnit reported no file or the file lies outside the project.
     *
     * <p>This is the last resort for a report whose test cases carry neither {@code classname} nor
     * {@code class}: the file's own location is then the most specific true statement available, and it
     * may well map to no requirement, which the report says rather than attributing the test to one.
     */
    private static String suiteFromFile(String file, Path projectRoot) {
        if (file == null || projectRoot == null) {
            return null;
        }
        try {
            Path path = Path.of(file);
            if (!path.isAbsolute() || !path.startsWith(projectRoot)) {
                return null;
            }
            String relative = projectRoot.relativize(path).toString();
            if (relative.endsWith(".php")) {
                relative = relative.substring(0, relative.length() - ".php".length());
            }
            return normalise(relative.replace(java.io.File.separatorChar, '.').replace('/', '.'));
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** @return a PHP qualified name with every backslash turned into a dot, or {@code null} when blank. */
    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replace('\\', '.');
    }

    private static Document parseXml(Path file) throws Exception {
        return dev.codewitness.support.XmlDocuments.parse(file);
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private static Duration seconds(String value) {
        if (value == null) {
            return Duration.ZERO;
        }
        try {
            return Duration.ofMillis((long) (Double.parseDouble(value) * 1000));
        } catch (NumberFormatException _) {
            return Duration.ZERO;
        }
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

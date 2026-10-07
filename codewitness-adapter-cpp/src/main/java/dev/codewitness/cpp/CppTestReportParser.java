package dev.codewitness.cpp;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the JUnit report CTest writes into an aggregate result.
 *
 * <p>Results are read from the report rather than from the console, because the console prints a
 * human-oriented progress line per test while the report carries, per test, the name it was registered
 * under, its outcome and its duration. CTest has no notion of a test case inside a test process: a CTest
 * test <em>is</em> the process, which is why the suite and the case carry the same name here, and why the
 * fixture names each CTest test after the test source file it runs. That convention is what lets the
 * requirement mapper derive the same identifier from a test file that this parser reads out of the report.
 *
 * <p>One detail of the format matters more than the rest. CTest writes a failure element whose
 * {@code message} attribute is the word {@code Failed} and nothing else; the test's own output - including
 * the single {@code CODEWITNESS_COUNTEREXAMPLE|...} line a generated test fails with - is in the
 * {@code system-out} element beside it. The failure text this parser records therefore joins the
 * element's own message to the captured standard output and standard error, because a counterexample whose
 * inputs were dropped would not be reproducible.
 *
 * <p>Only a passing test is a pass. A test CTest marked disabled, not run, or skipped is recorded as
 * skipped: a case that did not run is not evidence, and counting it as a pass would be the single most
 * misleading thing a test report could do.
 */
public final class CppTestReportParser {

    private CppTestReportParser() {
    }

    /**
     * Parses one CTest JUnit report.
     *
     * @param report the JUnit document
     * @param note   explanation carried into the result, including the tool run's outcome
     * @return the parsed result, or a not-executed result when the report could not be read
     */
    public static TestResult parse(Path report, String note) {
        Document document;
        try {
            document = CppXml.parse(report);
        } catch (Exception e) {
            return TestResult.notExecuted("the JUnit report " + report.getFileName() + " could not be read: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        List<TestCaseResult> cases = cases(document);
        if (cases.isEmpty()) {
            return TestResult.notExecuted("the JUnit report " + report.getFileName()
                    + " recorded no test case, so nothing was executed");
        }
        Duration duration = cases.stream().map(TestCaseResult::duration).reduce(Duration.ZERO, Duration::plus);
        return TestResult.from(cases, duration, List.of(report), note);
    }

    /** @return every case in the report, in the order the report lists them. */
    static List<TestCaseResult> cases(Document document) {
        List<TestCaseResult> cases = new ArrayList<>();
        Element root = document.getDocumentElement();
        for (Element testCase : CppXml.descendants(root, "testcase")) {
            String name = CppXml.attribute(testCase, "name");
            if (name == null) {
                // A test case with no name cannot be attributed to anything, so it is not recorded as one.
                continue;
            }
            String suite = suite(testCase, name);
            TestOutcome outcome = outcome(testCase);
            String message = null;
            String failureType = null;
            if (outcome.isFailure()) {
                message = failureText(testCase);
                failureType = outcome == TestOutcome.ERROR ? "test error" : "test failure";
            }
            cases.add(new TestCaseResult(suite, name, outcome, seconds(CppXml.attribute(testCase, "time")),
                    failureType, message, null));
        }
        return cases;
    }

    /**
     * @return the name the test belongs to: the class the report gives it, then the enclosing suite's
     *     name when that is one, and finally the test's own name.
     *
     * <p>CTest names its single suite {@code (empty)}, which is a placeholder rather than a name; a suite
     *     that carries it is not used.
     */
    static String suite(Element testCase, String name) {
        String className = CppXml.attribute(testCase, "classname");
        if (className != null && !className.isBlank() && !className.equals("(empty)")) {
            return className;
        }
        Element suite = enclosingSuite(testCase);
        String suiteName = suite == null ? null : CppXml.attribute(suite, "name");
        if (suiteName != null && !suiteName.isBlank() && !suiteName.equals("(empty)")) {
            return suiteName;
        }
        return name;
    }

    private static Element enclosingSuite(Element testCase) {
        Node parent = testCase.getParentNode();
        while (parent instanceof Element element) {
            if ("testsuite".equals(element.getTagName()) || "testsuite".equals(element.getLocalName())) {
                return element;
            }
            parent = element.getParentNode();
        }
        return null;
    }

    /**
     * @return the model's outcome for a CTest test case.
     *
     * <p>The child element is what decides: a {@code <failure>} is a failure, an {@code <error>} is an
     *     error, and a {@code <skipped>} is a case that did not run. The {@code status} attribute is
     *     consulted only when no child element says anything, and anything that is not a run or a failure
     *     is recorded as skipped rather than as a pass.
     */
    static TestOutcome outcome(Element testCase) {
        if (!CppXml.children(testCase, "failure").isEmpty()) {
            return TestOutcome.FAILED;
        }
        if (!CppXml.children(testCase, "error").isEmpty()) {
            return TestOutcome.ERROR;
        }
        if (!CppXml.children(testCase, "skipped").isEmpty()) {
            return TestOutcome.SKIPPED;
        }
        String status = CppXml.attribute(testCase, "status");
        String normalised = status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "", "run", "passed" -> TestOutcome.PASSED;
            case "fail", "failed" -> TestOutcome.FAILED;
            case "error" -> TestOutcome.ERROR;
            default -> TestOutcome.SKIPPED;
        };
    }

    /**
     * @return everything the report recorded about why a test failed: the failure element's own message,
     *     then the test's captured output, which is where a structured counterexample line ends up.
     */
    static String failureText(Element testCase) {
        StringBuilder text = new StringBuilder();
        Element failure = firstElement(testCase, "failure");
        if (failure == null) {
            failure = firstElement(testCase, "error");
        }
        if (failure != null) {
            String message = CppXml.attribute(failure, "message");
            if (message != null && !message.isBlank()) {
                text.append(message.strip());
            }
            String type = CppXml.attribute(failure, "type");
            if (type != null && !type.isBlank()) {
                text.append(text.isEmpty() ? "" : " (").append(type.strip()).append(')');
            }
        }
        // The captured output is appended whether or not the element carried a message: a generated test
        // prints its counterexample to standard output and exits non-zero, and CTest records "Failed" as
        // the message, so without this the inputs that reproduce the violation would be lost.
        appendOutput(text, CppXml.text(testCase, "system-out"));
        appendOutput(text, CppXml.text(testCase, "system-err"));
        return text.isEmpty() ? "the test failed and the report recorded no message" : text.toString();
    }

    private static void appendOutput(StringBuilder text, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(output.strip());
    }

    private static Element firstElement(Element parent, String tag) {
        List<Element> children = CppXml.children(parent, tag);
        return children.isEmpty() ? null : children.getFirst();
    }

    /**
     * @return the duration of a JUnit {@code time} attribute, which is seconds with a fractional part, or
     *     zero when it is absent or malformed
     */
    static Duration seconds(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        try {
            return Duration.ofMillis(Math.round(Double.parseDouble(value.strip()) * 1000));
        } catch (NumberFormatException _) {
            return Duration.ZERO;
        }
    }
}

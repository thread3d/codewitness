package dev.codewitness.java;

import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Parses JUnit style XML test reports.
 *
 * <p>The same format is produced by Maven Surefire, Maven Failsafe and Gradle's JUnit XML reporter,
 * so one parser covers all three. Parsing the raw reports (rather than scraping console output)
 * means the recorded test results are the tool's own output, not an interpretation of its log.
 */
public final class TestReportParser {

    private TestReportParser() {
    }

    /** Directories, relative to a module, that may contain test reports. */
    private static final List<String> REPORT_DIRECTORIES = List.of(
            "target/surefire-reports",
            "target/failsafe-reports",
            "build/test-results/test",
            "build/test-results/testDebugUnitTest");

    /** Finds all test report files for a project, in a stable order. */
    public static List<Path> findReportFiles(ProjectInfo project) {
        List<Path> reports = new ArrayList<>();
        for (var module : project.modules()) {
            for (String relative : REPORT_DIRECTORIES) {
                Path directory = module.path().resolve(relative);
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (Stream<Path> walk = Files.list(directory)) {
                    walk.filter(p -> p.getFileName().toString().startsWith("TEST-")
                                    && p.getFileName().toString().endsWith(".xml"))
                            .sorted()
                            .forEach(reports::add);
                } catch (IOException ignored) {
                    // an unreadable report directory simply yields no reports
                }
            }
        }
        return reports;
    }

    /** Parses a list of JUnit XML reports into an aggregate result. */
    public static TestResult parse(List<Path> reports, String note) {
        List<TestCaseResult> cases = new ArrayList<>();
        Duration total = Duration.ZERO;
        for (Path report : reports) {
            try {
                Document document = JavaProjectDetector.parseXml(report);
                Element suite = document.getDocumentElement();
                String suiteName = attribute(suite, "name");
                NodeList testCases = suite.getElementsByTagName("testcase");
                for (int i = 0; i < testCases.getLength(); i++) {
                    Element testCase = (Element) testCases.item(i);
                    cases.add(toCase(suiteName, testCase));
                }
                total = total.plus(seconds(attribute(suite, "time")));
            } catch (Exception e) {
                // a single malformed report must not lose the other results
                cases.add(new TestCaseResult(report.getFileName().toString(), "report-parse-failure",
                        TestOutcome.ERROR, Duration.ZERO, e.getClass().getSimpleName(), e.getMessage(), null));
            }
        }
        if (cases.isEmpty() && !reports.isEmpty()) {
            return TestResult.notExecuted("test reports exist but contained no test cases");
        }
        return TestResult.from(cases, total, reports, note);
    }

    private static TestCaseResult toCase(String suiteName, Element testCase) {
        String className = attribute(testCase, "classname");
        String name = attribute(testCase, "name");
        String suite = className == null || className.isBlank() ? suiteName : className;
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
                    return new TestCaseResult(suite, name, TestOutcome.FAILED, duration,
                            attribute(element, "type"), attribute(element, "message"),
                            element.getTextContent());
                }
                case "error" -> {
                    return new TestCaseResult(suite, name, TestOutcome.ERROR, duration,
                            attribute(element, "type"), attribute(element, "message"),
                            element.getTextContent());
                }
                case "skipped" -> {
                    return new TestCaseResult(suite, name, TestOutcome.SKIPPED, duration, null,
                            attribute(element, "message"), null);
                }
                default -> {
                    // unknown child element: ignore
                }
            }
        }
        return new TestCaseResult(suite, name, TestOutcome.PASSED, duration, null, null, null);
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
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }

    /** Deletes previous test reports so that stale results can never be mistaken for fresh ones. */
    public static void clearReports(ProjectInfo project) {
        for (Path report : findReportFiles(project)) {
            try {
                Files.deleteIfExists(report);
            } catch (IOException ignored) {
                // best effort; a stale report would only add noise
            }
        }
    }
}

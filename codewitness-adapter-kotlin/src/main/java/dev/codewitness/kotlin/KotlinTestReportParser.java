package dev.codewitness.kotlin;

import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
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
 * Parses the JUnit XML test reports a Kotlin project's build tool writes.
 *
 * <p>Kotlin tests run on the JVM and are reported by the same machinery as Java ones: Maven Surefire and
 * Gradle's JUnit XML reporter both write a {@code TEST-<class>.xml} file in which a Kotlin class appears
 * under its fully qualified name and a Kotlin function under its own name. Reading those reports rather
 * than scraping console output means the recorded results are the tool's own output, and it is what lets
 * a generated test's counterexample line reach the engine through a format as ordinary as a test report.
 */
public final class KotlinTestReportParser {

    /** Directories, relative to a module, that may contain JUnit XML reports. */
    private static final List<String> REPORT_DIRECTORIES = List.of(
            "target/surefire-reports",
            "target/failsafe-reports",
            "build/test-results/test",
            "build/test-results/testDebugUnitTest");

    private KotlinTestReportParser() {
    }

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
                    walk.filter(path -> path.getFileName().toString().startsWith("TEST-")
                                    && path.getFileName().toString().endsWith(".xml"))
                            .sorted()
                            .forEach(reports::add);
                } catch (IOException _) {
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
                Document document = KotlinProjectDetector.parseXml(report);
                Element suite = document.getDocumentElement();
                String suiteName = attribute(suite, "name");
                NodeList testCases = suite.getElementsByTagName("testcase");
                for (int i = 0; i < testCases.getLength(); i++) {
                    cases.add(toCase(suiteName, (Element) testCases.item(i)));
                }
                total = total.plus(seconds(attribute(suite, "time")));
            } catch (Exception e) {
                // A single malformed report must not lose the other results. It is recorded as an error
                // case rather than skipped, because a report nobody could read is not a passing test.
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
            // The failure text is carried verbatim: it is where a generated test puts its
            // CODEWITNESS_COUNTEREXAMPLE|... line, and reformatting it would lose the counterexample.
            switch (element.getTagName().toLowerCase(Locale.ROOT)) {
                case "failure" -> {
                    return new TestCaseResult(suite, name, TestOutcome.FAILED, duration,
                            attribute(element, "type"), attribute(element, "message"), element.getTextContent());
                }
                case "error" -> {
                    return new TestCaseResult(suite, name, TestOutcome.ERROR, duration,
                            attribute(element, "type"), attribute(element, "message"), element.getTextContent());
                }
                case "skipped" -> {
                    // A case that did not run is not evidence, so anything that is not a pass or a failure
                    // is recorded as skipped rather than assumed to have passed.
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
        } catch (NumberFormatException _) {
            return Duration.ZERO;
        }
    }

    /** Deletes previous test reports so that stale results can never be mistaken for fresh ones. */
    public static void clearReports(ProjectInfo project) {
        for (Path report : findReportFiles(project)) {
            try {
                Files.deleteIfExists(report);
            } catch (IOException _) {
                // best effort; a stale report would only add noise
            }
        }
    }
}

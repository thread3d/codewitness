package dev.codewitness.python;

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
 * Parses the JUnit XML that both pytest and CodeWitness's standard-library runner produce.
 *
 * <p>Parsing the raw report rather than scraping console output means the recorded results are the
 * runner's own output, not an interpretation of its log. The format is identical enough between the
 * two runners that one parser covers both; where they differ is only in how a test's class is named,
 * and that is normalised here.
 *
 * <p>pytest names a method inside a class {@code tests.test_ledger.LedgerTest}, while
 * CodeWitness's runner always names the module. The parser resolves the longest dotted prefix that
 * corresponds to a real file under the root it is given, so a test is always mapped to the module a
 * requirement can be attributed to.
 */
public final class PythonTestReportParser {

    private PythonTestReportParser() {
    }

    /** Parses a list of JUnit XML reports into an aggregate result. */
    public static TestResult parse(List<Path> reports, Path root, String note) {
        List<TestCaseResult> cases = new ArrayList<>();
        Duration total = Duration.ZERO;
        for (Path report : reports) {
            try {
                Document document = parseXml(report);
                Element documentElement = document.getDocumentElement();
                List<Element> suites = suitesOf(documentElement);
                for (Element suite : suites) {
                    total = total.plus(seconds(attribute(suite, "time")));
                    NodeList testCases = suite.getElementsByTagName("testcase");
                    for (int i = 0; i < testCases.getLength(); i++) {
                        cases.add(toCase((Element) testCases.item(i), root));
                    }
                }
            } catch (Exception e) {
                // A single malformed report must not lose the results the other reports carry.
                cases.add(new TestCaseResult(report.getFileName().toString(), "report-parse-failure",
                        TestOutcome.ERROR, Duration.ZERO, e.getClass().getSimpleName(), e.getMessage(), null));
            }
        }
        if (cases.isEmpty()) {
            return TestResult.notExecuted("test reports exist but contained no test cases");
        }
        return TestResult.from(cases, total, reports, note);
    }

    private static List<Element> suitesOf(Element documentElement) {
        List<Element> suites = new ArrayList<>();
        if (documentElement.getTagName().equalsIgnoreCase("testsuites")) {
            NodeList children = documentElement.getElementsByTagName("testsuite");
            for (int i = 0; i < children.getLength(); i++) {
                suites.add((Element) children.item(i));
            }
        } else {
            suites.add(documentElement);
        }
        return suites;
    }

    private static TestCaseResult toCase(Element testCase, Path root) {
        String classname = attribute(testCase, "classname");
        String method = attribute(testCase, "name");
        String suite = classname == null ? "unknown" : classname;
        String name = method == null ? "unknown" : method;

        Resolved resolved = resolveModule(classname, root);
        if (resolved != null) {
            suite = resolved.module();
            if (!resolved.remainder().isBlank()) {
                name = resolved.remainder() + "." + name;
            }
        }

        Duration duration = seconds(attribute(testCase, "time"));
        NodeList children = testCase.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
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
                    return new TestCaseResult(suite, name, TestOutcome.SKIPPED, duration, null,
                            attribute(element, "message"), null);
                }
                default -> {
                    // an unknown child element is ignored, exactly like an unexpected XML extension
                }
            }
        }
        return new TestCaseResult(suite, name, TestOutcome.PASSED, duration, null, null, null);
    }

    /**
     * Splits a reported class name into the module it lives in and any trailing class name.
     *
     * @param classname reported class name, for example {@code tests.test_ledger.LedgerTest}
     * @param root      directory the module paths are relative to
     * @return the resolved module and remainder, or {@code null} when the name is not a module path
     */
    static Resolved resolveModule(String classname, Path root) {
        if (classname == null || classname.isBlank() || root == null) {
            return null;
        }
        String[] parts = classname.split("\\.");
        for (int length = parts.length; length >= 1; length--) {
            StringBuilder module = new StringBuilder();
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    module.append('.');
                }
                module.append(parts[i]);
            }
            Path candidate = root.resolve(module.toString().replace('.', '/') + ".py");
            if (Files.isRegularFile(candidate)) {
                StringBuilder remainder = new StringBuilder();
                for (int i = length; i < parts.length; i++) {
                    if (remainder.length() > 0) {
                        remainder.append('.');
                    }
                    remainder.append(parts[i]);
                }
                return new Resolved(module.toString(), remainder.toString());
            }
        }
        return null;
    }

    private static Document parseXml(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // Security: strip any DOCTYPE and disable external entity resolution, because the report is
        // produced from a project that CodeWitness treats as hostile.
        String withoutDoctype = content.replaceAll("(?s)<!DOCTYPE[^>]*>", "");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(withoutDoctype)));
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

    /** A dotted module path and the class or nested name that followed it. */
    record Resolved(String module, String remainder) {
    }

    public static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException _) {
            // best effort: a stale report would only add noise
        }
    }
}

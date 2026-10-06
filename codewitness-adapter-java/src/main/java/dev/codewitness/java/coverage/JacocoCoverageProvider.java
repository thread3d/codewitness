package dev.codewitness.java.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.BuildToolLauncher;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coverage measurement through JaCoCo.
 *
 * <p>Coverage is read from the XML report rather than from console output. When no report exists the
 * provider tries to produce one by attaching the JaCoCo agent to the project's own test run; if that
 * fails - no Maven, no network for the plugin, a build that ignores {@code argLine} - coverage is
 * reported as <em>not measured</em> with the reason, and no requirement can be marked verified on
 * the strength of it.
 */
public final class JacocoCoverageProvider implements VerificationTools.CoverageProvider {

    /**
     * Version of the JaCoCo Maven plugin injected into the project under verification.
     *
     * <p>0.8.14 is the first release with official Java 25 support (class file version 69); 0.8.15
     * is the current stable release and also covers Java 26. Older builds fail to instrument Java 25
     * bytecode, which would silently degrade coverage to "not measured".
     */
    private static final String JACOCO_VERSION = "0.8.15";

    private final BuildToolLauncher launcher;

    public JacocoCoverageProvider(BuildToolLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public String id() {
        return "jacoco";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.buildSystem().id().equals("maven") || project.buildSystem().id().equals("gradle");
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> reports = findReports(project);
        String note = null;
        if (reports.isEmpty()) {
            if (project.buildSystem().id().equals("maven")) {
                note = attemptJacocoRun(context);
                reports = findReports(project);
            } else {
                note = "no JaCoCo report found; for Gradle add the jacoco plugin to the project";
            }
        }
        if (reports.isEmpty()) {
            return CoverageReport.notMeasured(id(), note == null ? "no JaCoCo XML report found" : note);
        }
        return parse(reports);
    }

    private String attemptJacocoRun(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> executable = launcher.resolve(project, project.buildRoot());
        if (executable == null) {
            return "no Maven executable found, so coverage could not be measured";
        }
        List<String> goals = new ArrayList<>(executable);
        goals.add("-B");
        goals.add("-ntp");
        goals.add("-Dstyle.color=never");
        goals.add("-Dmaven.test.failure.ignore=true");
        goals.add("org.jacoco:jacoco-maven-plugin:" + JACOCO_VERSION + ":prepare-agent");
        goals.add("test");
        goals.add("org.jacoco:jacoco-maven-plugin:" + JACOCO_VERSION + ":report");
        ProcessResult result = context.runner().run(ProcessSpec.builder(goals, project.buildRoot())
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            return "coverage could not be measured: " + result.failureReason();
        }
        return "coverage was measured with the JaCoCo agent attached to the test run ("
                + result.describeOutcome() + ")";
    }

    private static List<Path> findReports(ProjectInfo project) {
        List<Path> reports = new ArrayList<>();
        for (var module : project.modules()) {
            for (String relative : List.of("target/site/jacoco/jacoco.xml", "build/reports/jacoco/test/jacocoTestReport.xml")) {
                Path report = module.path().resolve(relative);
                if (Files.isRegularFile(report)) {
                    reports.add(report);
                }
            }
        }
        return reports;
    }

    /** Parses JaCoCo XML reports into a coverage summary. */
    public static CoverageReport parse(List<Path> reports) {
        int lineCovered = 0;
        int lineTotal = 0;
        int branchCovered = 0;
        int branchTotal = 0;
        Map<String, Double> perClass = new LinkedHashMap<>();

        for (Path report : reports) {
            try {
                Element root = dev.codewitness.java.JavaProjectDetector.parseReportXml(report).getDocumentElement();
                NodeList children = root.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (!(node instanceof Element element)) {
                        continue;
                    }
                    switch (element.getTagName()) {
                        case "counter" -> {
                            String type = element.getAttribute("type");
                            if ("LINE".equals(type)) {
                                lineCovered += integer(element, "covered");
                                lineTotal += integer(element, "covered") + integer(element, "missed");
                            } else if ("BRANCH".equals(type)) {
                                branchCovered += integer(element, "covered");
                                branchTotal += integer(element, "covered") + integer(element, "missed");
                            }
                        }
                        case "package" -> {
                            NodeList classes = element.getElementsByTagName("class");
                            for (int c = 0; c < classes.getLength(); c++) {
                                Element classElement = (Element) classes.item(c);
                                double percentage = 0;
                                NodeList counters = classElement.getElementsByTagName("counter");
                                for (int k = 0; k < counters.getLength(); k++) {
                                    Element counter = (Element) counters.item(k);
                                    if ("LINE".equals(counter.getAttribute("type"))) {
                                        int covered = integer(counter, "covered");
                                        int missed = integer(counter, "missed");
                                        percentage = covered + missed == 0 ? 0
                                                : 100.0 * covered / (covered + missed);
                                    }
                                }
                                perClass.put(classElement.getAttribute("name"), percentage);
                            }
                        }
                        default -> {
                            // other elements are not needed
                        }
                    }
                }
            } catch (Exception e) {
                // a malformed report is skipped; the remaining reports still count
            }
        }
        return new CoverageReport(true, "jacoco", "parsed from " + reports.size() + " XML report(s)",
                lineCovered, lineTotal, branchCovered, branchTotal,
                new LinkedHashMap<>(perClass), reports);
    }

    private static int integer(Element element, String attribute) {
        try {
            return Integer.parseInt(element.getAttribute(attribute));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

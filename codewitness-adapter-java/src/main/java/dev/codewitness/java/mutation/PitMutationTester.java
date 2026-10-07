package dev.codewitness.java.mutation;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.BuildToolLauncher;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mutation testing through PIT.
 *
 * <p>Mutation testing is the reason CodeWitness can say something meaningful about a passing test
 * suite: a test that passes says nothing unless it <em>could</em> have failed. Stale reports are
 * deleted before the run, and when PIT cannot run the result is "not measured" with the tool's own
 * output as the explanation rather than an optimistic default.
 */
public final class PitMutationTester implements VerificationTools.MutationTester {

    /**
     * Version of PIT injected into the project under verification. PIT gained Java 25 support in the
     * 1.25.x line (issue #1439, closed 2026-02-02) with further Java 25 mutator fixes in 1.25.8;
     * 1.30.0 is current. Older builds cannot mutate class file version 69.
     */
    private static final String PIT_VERSION = "1.30.0";

    /** Version of the PIT JUnit 5 plugin; without it PIT cannot run a JUnit 5 suite at all. */
    private static final String PIT_JUNIT5_PLUGIN_VERSION = "1.2.3";

    private final BuildToolLauncher launcher;

    public PitMutationTester(BuildToolLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public String id() {
        return "pitest";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA)
                && project.buildSystem().id().equals("maven") && project.hasTests();
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (!context.state().existingTests().executed()) {
            return MutationReport.notMeasured(id(), "the existing tests did not run, so mutation testing has no "
                    + "suite to measure");
        }
        if (!context.state().existingTests().anyFailure() && context.state().existingTests().total() == 0) {
            return MutationReport.notMeasured(id(), "the project has no executed test cases");
        }
        List<String> executable = launcher.resolve(project, project.buildRoot());
        if (executable == null) {
            return MutationReport.notMeasured(id(), "no Maven executable found");
        }

        clearReports(project);
        List<String> goals = new ArrayList<>(executable);
        goals.add("-B");
        goals.add("-ntp");
        goals.add("-Dstyle.color=never");
        goals.add("org.pitest:pitest-maven:" + PIT_VERSION + ":mutationCoverage");
        goals.add("-DoutputFormats=XML");
        goals.add("-DtimestampedReports=false");
        goals.add("-DwithHistory=false");
        goals.add("-DfullMutationMatrix=false");
        goals.add("-DmutationThreshold=0");
        goals.add("-DfailWhenNoMutations=false");
        goals.add("-Djunit5PluginVersion=" + PIT_JUNIT5_PLUGIN_VERSION);
        String targetClasses = targetClasses(project);
        if (targetClasses != null) {
            goals.add("-DtargetClasses=" + targetClasses);
        }
        ProcessResult result = context.runner().run(ProcessSpec.builder(goals, project.buildRoot())
                .timeout(context.config().mutation().timeout())
                .maxOutputBytes(1_000_000)
                .build());

        List<Path> reports = findReports(project);
        if (reports.isEmpty()) {
            String output = result.combinedOutput();
            String hint = "";
            if (output.contains("pitest junit 5 plugin is not installed")
                    || output.contains("could not run any tests")) {
                hint = " The project runs JUnit 5 tests but does not give PIT a JUnit 5 plugin. Declare "
                        + "org.pitest:pitest-junit5-plugin:" + PIT_JUNIT5_PLUGIN_VERSION
                        + " as a dependency of the pitest-maven plugin in the project's pom.xml and re-run; "
                        + "CodeWitness does not modify the project under verification. Example: "
                        + "<plugin><artifactId>pitest-maven</artifactId><dependencies><dependency>"
                        + "<groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId>"
                        + "<version>" + PIT_JUNIT5_PLUGIN_VERSION + "</version></dependency></dependencies></plugin>";
            } else if (output.contains("No mutations found")) {
                hint = " PIT found no classes to mutate: check that targetClasses covers the project's "
                        + "packages (" + String.valueOf(targetClasses(project)) + ").";
            }
            return MutationReport.notMeasured(id(), "PIT produced no XML report ("
                    + result.describeOutcome() + ")." + hint + " Last output: " + tail(output, 800));
        }
        return parse(reports);
    }

    private static String targetClasses(ProjectInfo project) {
        String packages = project.attributes().getOrDefault("rootPackages", "");
        if (packages.isBlank()) {
            return null;
        }
        List<String> patterns = new ArrayList<>();
        for (String pkg : packages.split(",")) {
            if (!pkg.isBlank()) {
                patterns.add(pkg.strip() + ".*");
            }
        }
        return patterns.isEmpty() ? null : String.join(",", patterns);
    }

    private static void clearReports(ProjectInfo project) {
        for (Path report : findReports(project)) {
            try {
                Files.deleteIfExists(report);
            } catch (IOException _) {
                // best effort
            }
        }
    }

    private static List<Path> findReports(ProjectInfo project) {
        List<Path> reports = new ArrayList<>();
        for (var module : project.modules()) {
            Path report = module.path().resolve("target/pit-reports/mutations.xml");
            if (Files.isRegularFile(report)) {
                reports.add(report);
            }
        }
        return reports;
    }

    /** Parses PIT {@code mutations.xml} reports. */
    public static MutationReport parse(List<Path> reports) {
        int generated = 0;
        int killed = 0;
        int survived = 0;
        int noCoverage = 0;
        int timedOut = 0;
        List<SurvivingMutant> survivors = new ArrayList<>();

        for (Path report : reports) {
            try {
                Element root = dev.codewitness.java.JavaProjectDetector.parseReportXml(report).getDocumentElement();
                NodeList mutations = root.getElementsByTagName("mutation");
                for (int i = 0; i < mutations.getLength(); i++) {
                    Element mutation = (Element) mutations.item(i);
                    generated++;
                    String status = mutation.getAttribute("status");
                    MutantStatus mutantStatus = parseStatus(status);
                    switch (mutantStatus) {
                        case KILLED -> killed++;
                        case SURVIVED -> survived++;
                        case NO_COVERAGE -> noCoverage++;
                        case TIMED_OUT -> timedOut++;
                        default -> {
                            // NON_VIABLE, MEMORY_ERROR and RUN_ERROR are counted as generated only
                        }
                    }
                    if (mutantStatus == MutantStatus.SURVIVED || mutantStatus == MutantStatus.NO_COVERAGE
                            || mutantStatus == MutantStatus.TIMED_OUT) {
                        survivors.add(new SurvivingMutant(
                                mutation.getAttribute("id"),
                                mutantStatus,
                                text(mutation, "mutator"),
                                text(mutation, "mutatedClass"),
                                text(mutation, "mutatedMethod"),
                                integer(mutation, "lineNumber"),
                                text(mutation, "description"),
                                text(mutation, "killingTest")));
                    }
                }
            } catch (Exception e) {
                // skip malformed report
            }
        }
        return new MutationReport(true, "pitest", "parsed from " + reports.size() + " XML report(s)",
                generated, killed, survived, noCoverage, timedOut, survivors, reports);
    }

    private static MutantStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return MutantStatus.UNKNOWN;
        }
        try {
            return MutantStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MutantStatus.UNKNOWN;
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static int integer(Element parent, String tag) {
        String value = text(parent, tag);
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(text.length() - max);
    }
}

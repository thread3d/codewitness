package dev.codewitness.java.coverage;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.java.BuildToolLauncher;
import dev.codewitness.java.TestContexts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JaCoCo coverage is read from the XML report. The report carries a DOCTYPE declaration, so the
 * parser must strip it rather than feed it to a strict, resolution-capable parser: the regression
 * this guards against silently reported 0/0 coverage.
 */
class JacocoCoverageProviderTest {

    @TempDir
    Path tmp;

    private static final String JACOCO_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="sample">
              <sessioninfo id="host-1" start="1" dump="2"/>
              <package name="com/example">
                <class name="com/example/Foo" sourcefilename="Foo.java">
                  <method name="add" desc="(II)I" line="3"/>
                  <counter type="LINE" missed="1" covered="3"/>
                  <counter type="BRANCH" missed="0" covered="2"/>
                </class>
                <class name="com/example/Bar" sourcefilename="Bar.java">
                  <counter type="LINE" missed="2" covered="2"/>
                </class>
                <counter type="LINE" missed="3" covered="5"/>
              </package>
              <counter type="INSTRUCTION" missed="10" covered="20"/>
              <counter type="LINE" missed="3" covered="7"/>
              <counter type="BRANCH" missed="1" covered="3"/>
            </report>
            """;

    @Test
    void parsesLineAndBranchCountersFromAReportWithADoctype() throws IOException {
        Path report = TestContexts.write(tmp, "jacoco.xml", JACOCO_XML);

        CoverageReport coverage = JacocoCoverageProvider.parse(List.of(report));

        assertTrue(coverage.measured(), "a report exists, so coverage was measured");
        assertEquals("jacoco", coverage.tool());
        assertEquals(7, coverage.lineCovered());
        assertEquals(10, coverage.lineTotal());
        assertEquals(3, coverage.branchCovered());
        assertEquals(4, coverage.branchTotal());
        assertEquals(70.0, coverage.lineCoveragePercent(), 0.0001);
        assertEquals(75.0, coverage.branchCoveragePercent(), 0.0001);
        assertEquals(List.of(report), coverage.reports());
        assertTrue(coverage.note().contains("parsed from 1 XML report(s)"), coverage.note());
    }

    @Test
    void reportsAPerClassLinePercentage() throws IOException {
        Path report = TestContexts.write(tmp, "jacoco.xml", JACOCO_XML);

        CoverageReport coverage = JacocoCoverageProvider.parse(List.of(report));

        assertEquals(2, coverage.perClassLineCoverage().size());
        assertEquals(75.0, coverage.perClassLineCoverage().get("com/example/Foo"), 0.0001);
        assertEquals(50.0, coverage.perClassLineCoverage().get("com/example/Bar"), 0.0001);
    }

    @Test
    void aMalformedReportDoesNotDiscardTheValidOnes() throws IOException {
        Path good = TestContexts.write(tmp, "good.xml", JACOCO_XML);
        Path bad = TestContexts.write(tmp, "bad.xml",
                "<?xml version=\"1.0\"?>\n<!DOCTYPE report PUBLIC \"x\" \"y\">\n<report><counter type=\"LINE\"");

        CoverageReport coverage = JacocoCoverageProvider.parse(List.of(good, bad));

        assertTrue(coverage.measured());
        assertEquals(7, coverage.lineCovered(), "the valid report still counts");
        assertEquals(10, coverage.lineTotal());
        assertTrue(coverage.note().contains("2 XML report(s)"), coverage.note());
    }

    @Test
    void aGradleProjectWithoutAReportIsNotMeasured() {
        JacocoCoverageProvider provider =
                new JacocoCoverageProvider(new BuildToolLauncher(ProcessRunner.denying("no build in tests")));

        CoverageReport coverage = provider.measure(context(project(BuildSystemId.GRADLE), tmp.resolve("gradle-run")));

        assertFalse(coverage.measured());
        assertTrue(coverage.note().contains("Gradle"), coverage.note());
        assertEquals("jacoco", coverage.tool());
    }

    @Test
    void onlyMavenAndGradleProjectsAreSupported() {
        JacocoCoverageProvider provider =
                new JacocoCoverageProvider(new BuildToolLauncher(ProcessRunner.denying("no build in tests")));

        assertTrue(provider.supports(project(BuildSystemId.MAVEN)));
        assertTrue(provider.supports(project(BuildSystemId.GRADLE)));
        assertFalse(provider.supports(project(BuildSystemId.NONE)));
        assertEquals("jacoco", provider.id());
    }

    private VerificationContext context(ProjectInfo project, Path runDirectory) {
        ProjectRef ref = project.project();
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(ref)
                .projectInfo(project)
                .config(VerificationConfig.defaults())
                .workspace(new WorkspaceManager.Workspace(ref.root(), WorkspaceManager.Mode.IN_PLACE, "test"))
                .artifacts(new ArtifactStore(runDirectory))
                .budget(new BudgetUsage.Tracker(Budget.defaults()))
                .sandbox(new LocalProcessSandbox(List.of(ref.root())))
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }

    private ProjectInfo project(BuildSystemId buildSystem) {
        Path root = tmp.resolve("project-" + buildSystem.id());
        ProjectRef ref = ProjectRef.of(root);
        return ProjectInfo.builder(ref)
                .language(LanguageId.JAVA)
                .buildSystem(buildSystem)
                .addModule(new ModuleInfo("sample", root, "jar", List.of(), List.of(), "17"))
                .build();
    }
}

package dev.codewitness.kotlin.coverage;

import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.kotlin.KotlinProjectDetector;
import dev.codewitness.kotlin.KotlinTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage is read from JaCoCo's XML, which is the same report for Kotlin as for Java because both compile to
 * JVM bytecode. What is Kotlin's is the shape of the class names inside it: a file's top-level functions are
 * collected into a synthetic {@code LedgerKt} class, and a provider that hid those would under-report a
 * Kotlin project's coverage.
 */
class KotlinCoverageProviderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("line and branch counters are summed across reports and per class")
    void parsesJacocoReports() throws IOException {
        Path report = KotlinTestContexts.write(tmp, "target/site/jacoco/jacoco.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="ledger">
                  <package name="com/example/account">
                    <class name="com/example/account/WithdrawalLedger" sourcefilename="WithdrawalLedger.kt">
                      <counter type="LINE" missed="2" covered="8"/>
                      <counter type="BRANCH" missed="1" covered="3"/>
                    </class>
                    <class name="com/example/account/WithdrawalLedgerKt" sourcefilename="WithdrawalLedger.kt">
                      <counter type="LINE" missed="0" covered="4"/>
                      <counter type="BRANCH" missed="0" covered="0"/>
                    </class>
                  </package>
                  <counter type="LINE" missed="2" covered="12"/>
                  <counter type="BRANCH" missed="1" covered="3"/>
                  <counter type="INSTRUCTION" missed="9" covered="40"/>
                </report>
                """);

        CoverageReport coverage = KotlinCoverageProvider.parse(List.of(report));

        assertTrue(coverage.measured());
        assertEquals("jacoco", coverage.tool());
        assertEquals(12, coverage.lineCovered());
        assertEquals(14, coverage.lineTotal());
        assertEquals(3, coverage.branchCovered());
        assertEquals(4, coverage.branchTotal());
        assertEquals(2, coverage.perClassLineCoverage().size(), coverage.perClassLineCoverage().toString());
        assertEquals(80.0, coverage.perClassLineCoverage().get("com/example/account/WithdrawalLedger"), 0.001);
        assertEquals(100.0, coverage.perClassLineCoverage().get("com/example/account/WithdrawalLedgerKt"), 0.001,
                "the synthetic class a Kotlin file's top-level functions are compiled into is reported too");
    }

    @Test
    @DisplayName("a malformed report is skipped and the remaining ones still count")
    void aMalformedReportDoesNotLoseTheOthers() throws IOException {
        Path broken = KotlinTestContexts.write(tmp, "broken.xml", "<report><package>");
        Path good = KotlinTestContexts.write(tmp, "good.xml", """
                <report name="ledger">
                  <counter type="LINE" missed="1" covered="1"/>
                </report>
                """);

        CoverageReport coverage = KotlinCoverageProvider.parse(List.of(broken, good));

        assertEquals(1, coverage.lineCovered());
        assertEquals(2, coverage.lineTotal());
    }

    @Test
    @DisplayName("no report means not measured, with the reason, and never a percentage")
    void missingReportsAreReportedAsNotMeasured() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("project"));
        KotlinTestContexts.write(root, "build.gradle.kts", KotlinTestContexts.GRADLE_BUILD);
        var context = KotlinTestContexts.context(root, root.resolve(".codewitness"));

        CoverageReport coverage = new KotlinCoverageProvider(
                new dev.codewitness.kotlin.KotlinBuildTools(context.runner())).measure(context);

        assertFalse(coverage.measured());
        assertTrue(coverage.note().contains("jacoco plugin"),
                "the reason a Gradle project has no report is stated: " + coverage.note());
    }

    @Test
    @DisplayName("the provider claims Kotlin under Maven and Gradle, and nothing else")
    void supportsOnlyKotlinJvmBuilds() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("supported"));
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);
        var context = KotlinTestContexts.context(root, root.resolve(".codewitness"));
        KotlinCoverageProvider provider = new KotlinCoverageProvider(
                new dev.codewitness.kotlin.KotlinBuildTools(context.runner()));

        assertTrue(provider.supports(context.projectInfo()));
        assertEquals("kotlin-jacoco", provider.id());

        ProjectInfo javaProject = ProjectInfo.builder(context.projectInfo().project())
                .language(LanguageId.JAVA)
                .buildSystem(context.projectInfo().buildSystem())
                .build();
        assertFalse(provider.supports(javaProject),
                "the Java adapter has its own coverage provider; two would both run");
    }

    @Test
    @DisplayName("reports are found where Maven and Gradle each write them")
    void findsReportsInBothLocations() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("reports"));
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);
        KotlinTestContexts.write(root, "target/site/jacoco/jacoco.xml", "<report/>");

        ProjectInfo info = KotlinProjectDetector.inspect(dev.codewitness.core.model.ProjectRef.of(root));

        assertEquals(List.of(root.resolve("target/site/jacoco/jacoco.xml")),
                KotlinCoverageProvider.findReports(info));
    }
}

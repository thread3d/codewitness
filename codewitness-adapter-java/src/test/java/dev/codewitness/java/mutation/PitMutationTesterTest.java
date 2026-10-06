package dev.codewitness.java.mutation;

import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.java.BuildToolLauncher;
import dev.codewitness.java.TestContexts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PIT reports carry a DOCTYPE too. Mutation results are the reason a passing test suite means
 * something, so a parser that silently reports "no mutations" would turn a weak suite into a
 * confident claim.
 */
class PitMutationTesterTest {

    @TempDir
    Path tmp;

    private static final String PIT_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mutations PUBLIC "-//PITEST//DTD MUTATIONS 1.0//EN" "http://pitest.org/dtd/mutations.dtd">
            <mutations partial="false">
              <mutation detected="true" status="KILLED" numberOfTestsRun="2">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <mutatedMethod>add</mutatedMethod>
                <methodDescription>(II)I</methodDescription>
                <lineNumber>12</lineNumber>
                <mutator>MathMutator</mutator>
                <indexes><index>3</index></indexes>
                <blocks><block>0</block></blocks>
                <killingTest>com.example.FooTest.testAdd</killingTest>
                <description>replaced int addition with subtraction</description>
              </mutation>
              <mutation detected="false" status="SURVIVED" numberOfTestsRun="2">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <mutatedMethod>check</mutatedMethod>
                <lineNumber>20</lineNumber>
                <mutator>ConditionalsBoundaryMutator</mutator>
                <description>changed conditional boundary</description>
              </mutation>
              <mutation detected="false" status="NO_COVERAGE" numberOfTestsRun="0">
                <sourceFile>Baz.java</sourceFile>
                <mutatedClass>com.example.Baz</mutatedClass>
                <mutatedMethod>unused</mutatedMethod>
                <lineNumber>5</lineNumber>
                <mutator>VoidMethodCallMutator</mutator>
                <description>removed call</description>
              </mutation>
              <mutation status="TIMED_OUT" numberOfTestsRun="1">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <mutatedMethod>loop</mutatedMethod>
                <lineNumber>30</lineNumber>
                <mutator>IncrementsMutator</mutator>
                <description>timed out</description>
              </mutation>
              <mutation status="NON_VIABLE" numberOfTestsRun="0">
                <sourceFile>Foo.java</sourceFile>
                <mutatedClass>com.example.Foo</mutatedClass>
                <mutatedMethod>never</mutatedMethod>
                <lineNumber>40</lineNumber>
                <mutator>UnknownMutator</mutator>
                <description>not viable</description>
              </mutation>
            </mutations>
            """;

    @Test
    void parsesMutationCountsFromAReportWithADoctype() throws IOException {
        Path report = TestContexts.write(tmp, "mutations.xml", PIT_XML);

        MutationReport mutation = PitMutationTester.parse(List.of(report));

        assertTrue(mutation.measured(), "a report exists, so mutation testing was measured");
        assertEquals("pitest", mutation.tool());
        assertEquals(5, mutation.generated());
        assertEquals(1, mutation.killed());
        assertEquals(1, mutation.survived());
        assertEquals(1, mutation.noCoverage());
        assertEquals(1, mutation.timedOut());
        assertEquals(100.0 / 3, mutation.scorePercent(), 0.0001,
                "the score counts covered mutants: no-coverage mutants are excluded");
        assertEquals(List.of(report), mutation.reports());
        assertTrue(mutation.note().contains("parsed from 1 XML report(s)"), mutation.note());
        assertTrue(mutation.describe().contains("1 survived"), mutation.describe());
    }

    @Test
    void recordsSurvivorDetails() throws IOException {
        Path report = TestContexts.write(tmp, "mutations.xml", PIT_XML);

        List<SurvivingMutant> survivors = PitMutationTester.parse(List.of(report)).survivors();

        assertEquals(3, survivors.size(), "survived, no-coverage and timed-out mutants all need attention");
        SurvivingMutant survived = survivors.get(0);
        assertEquals(MutantStatus.SURVIVED, survived.status());
        assertEquals("ConditionalsBoundaryMutator", survived.mutator());
        assertEquals("com.example.Foo", survived.className());
        assertEquals("check", survived.method());
        assertEquals(20, survived.line());
        assertEquals("changed conditional boundary", survived.description());
        assertNull(survived.killingTest(), "a surviving mutant has no killing test");
        assertEquals("com.example.Foo#check:20", survived.location());

        assertEquals(MutantStatus.NO_COVERAGE, survivors.get(1).status());
        assertEquals("com.example.Baz", survivors.get(1).className());
        assertEquals(MutantStatus.TIMED_OUT, survivors.get(2).status());
        assertEquals("com.example.Foo", survivors.get(2).className());
    }

    @Test
    void aMalformedReportDoesNotDiscardTheValidOnes() throws IOException {
        Path good = TestContexts.write(tmp, "good.xml", PIT_XML);
        Path bad = TestContexts.write(tmp, "bad.xml",
                "<?xml version=\"1.0\"?>\n<!DOCTYPE mutations PUBLIC \"x\" \"y\">\n<mutations><mutation");

        MutationReport mutation = PitMutationTester.parse(List.of(good, bad));

        assertEquals(5, mutation.generated(), "the valid report still counts");
        assertEquals(1, mutation.killed());
        assertTrue(mutation.note().contains("2 XML report(s)"), mutation.note());
    }

    @Test
    void anUnknownStatusCountsAsGeneratedOnly() throws IOException {
        Path report = TestContexts.write(tmp, "unknown.xml", """
                <?xml version="1.0"?>
                <mutations>
                  <mutation status="WAT" id="9">
                    <mutatedClass>com.example.Foo</mutatedClass>
                    <mutatedMethod>m</mutatedMethod>
                    <lineNumber>1</lineNumber>
                  </mutation>
                </mutations>
                """);

        MutationReport mutation = PitMutationTester.parse(List.of(report));

        assertEquals(1, mutation.generated());
        assertEquals(0, mutation.killed());
        assertEquals(0, mutation.survived());
        assertTrue(mutation.survivors().isEmpty());
    }

    @Test
    void onlyMavenProjectsWithTestsAreSupported() {
        PitMutationTester tester = new PitMutationTester(
                new BuildToolLauncher(ProcessRunner.denying("no build in tests")));

        assertTrue(tester.supports(project(BuildSystemId.MAVEN, true)));
        assertFalse(tester.supports(project(BuildSystemId.MAVEN, false)),
                "mutation testing needs an existing test suite");
        assertFalse(tester.supports(project(BuildSystemId.GRADLE, true)));
        assertEquals("pitest", tester.id());
    }

    private ProjectInfo project(BuildSystemId buildSystem, boolean withTests) {
        Path root = tmp.resolve("project-" + buildSystem.id() + "-" + withTests);
        Path tests = withTests ? root.resolve("src/test/java") : null;
        try {
            java.nio.file.Files.createDirectories(root.resolve("src/main/java"));
            if (tests != null) {
                java.nio.file.Files.createDirectories(tests);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .buildSystem(buildSystem)
                .addModule(new ModuleInfo("sample", root, "jar",
                        List.of(root.resolve("src/main/java")),
                        tests == null ? List.of() : List.of(tests), "17"))
                .build();
    }
}

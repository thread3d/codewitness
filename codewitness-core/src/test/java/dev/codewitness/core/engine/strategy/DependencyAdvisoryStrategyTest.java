package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.engine.RequirementCodeMapper;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.AdvisoryScan;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DependencyVulnerability;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract that a high severity advisory fails a requirement.
 *
 * <p>The point of these tests is the <em>attribution</em>, not the failing. It is easy to make every
 * requirement fail whenever any dependency anywhere is vulnerable; that would be a worse report, because
 * it would assert something the evidence does not support.
 */
class DependencyAdvisoryStrategyTest {

    @TempDir
    Path tmp;

    private static final DependencyVulnerability HIGH =
            new DependencyVulnerability("com.example:vulnerable", "1.0", "CVE-2026-0001", Severity.HIGH,
                    "Remote code execution through the deserialiser.", "OSV");

    @Test
    void failsTheRequirementWhoseOwnModuleResolvesTheVulnerableDependency() {
        Fixture fixture = fixture(Severity.HIGH, HIGH, requirementA(), "module-a");

        StrategyOutcome outcome = verify(fixture);

        assertEquals(1, outcome.counterexamples().size(), "the advisory ships with module-a's code");
        Counterexample counterexample = outcome.counterexamples().getFirst();
        assertEquals("REQ-A", counterexample.requirementId());
        assertEquals(Counterexample.DiscoveryMethod.DEPENDENCY_ADVISORY, counterexample.discoveredBy());
        assertEquals("com.example:vulnerable", counterexample.inputs().get("dependency"));
        assertEquals("module-a", counterexample.inputs().get("module"));
        assertNotNull(counterexample.reproduction(), "a counterexample must carry a way to reproduce it");
    }

    @Test
    void doesNotFailARequirementInAModuleThatDoesNotResolveIt() {
        // REQ-B's code lives in module-b; the vulnerable artifact is pulled in by module-a alone.
        Fixture fixture = fixture(Severity.HIGH, HIGH, requirementB(), "module-a");

        StrategyOutcome outcome = verify(fixture);

        assertTrue(outcome.counterexamples().isEmpty(),
                "module-b does not depend on the vulnerable artifact, so REQ-B is not refuted by it");
    }

    @Test
    void anAdvisoryThatStatesNoSeverityNeverFailsARequirement() {
        DependencyVulnerability unknownSeverity = new DependencyVulnerability("com.example:vulnerable", "1.0",
                "CVE-2026-0001", Severity.UNKNOWN, "severity not stated", "OSV");

        StrategyOutcome outcome = verify(fixture(Severity.HIGH, unknownSeverity, requirementA(), "module-a"));

        assertTrue(outcome.counterexamples().isEmpty(),
                "an advisory whose severity nobody stated refutes nothing");
    }

    @Test
    void advisoriesBelowTheThresholdAreReportedButDoNotFail() {
        DependencyVulnerability medium = new DependencyVulnerability("com.example:vulnerable", "1.0",
                "CVE-2026-0001", Severity.MEDIUM, "moderate issue", "OSV");

        StrategyOutcome outcome = verify(fixture(Severity.HIGH, medium, requirementA(), "module-a"));

        assertTrue(outcome.counterexamples().isEmpty(), "MEDIUM is below a HIGH threshold");
        assertEquals(1, outcome.evidence().size(), "it is still recorded as evidence");
        assertTrue(outcome.evidence().getFirst().result().contains("CVE-2026-0001"),
                outcome.evidence().getFirst().result());
    }

    @Test
    void aThresholdOfNoneReportsTheAdvisoryWithoutFailingAnything() {
        StrategyOutcome outcome = verify(fixture(null, HIGH, requirementA(), "module-a"));

        assertTrue(outcome.counterexamples().isEmpty(), "fail_on: none must not fail a requirement");
        assertEquals(1, outcome.evidence().size());
        assertTrue(outcome.notes().stream().anyMatch(note -> note.contains("threshold is 'none'")),
                outcome.notes().toString());
    }

    @Test
    void saysSoWhenARequirementCannotBeTracedToAModule() {
        // REQ-ORPHAN declares no paths, so nothing says which module would deliver it.
        StrategyOutcome outcome = verify(fixture(Severity.HIGH, HIGH, requirementOrphan(), "module-a"));

        assertTrue(outcome.counterexamples().isEmpty());
        assertTrue(outcome.notes().stream().anyMatch(note -> note.contains("could be traced to a module")),
                outcome.notes().toString());
    }

    @Test
    void placesAFileInTheMostSpecificModule() {
        ProjectInfo project = project();

        assertEquals("module-a", DependencyAdvisoryStrategy.moduleOf(
                "module-a/src/main/java/A.java", project));
        assertEquals("module-a", DependencyAdvisoryStrategy.moduleOf(
                tmp.resolve("module-a/src/main/java/A.java").toString(), project));
        assertNull(DependencyAdvisoryStrategy.moduleOf("Somewhere.java", project));
        assertNull(DependencyAdvisoryStrategy.moduleOf(null, project));
    }

    @Test
    @DisplayName("a language with one module owns every file of that language")
    void aSingleModuleLanguageOwnsItsFiles() {
        // npm, PyPI, crates.io and Go projects always have exactly one module, and in isolated mode its path
        // points into the run's copy of the project rather than at a path a requirement could name. Without
        // this fallback an advisory would be found and then never charged to anything, which makes the scan
        // decorative.
        ProjectInfo single = ProjectInfo.builder(ProjectRef.of(tmp))
                .language(LanguageId.TYPESCRIPT)
                .addModule(new ModuleInfo("frontend", tmp.resolve("workspace"), "module", List.of(), List.of(), null))
                .build();

        assertEquals("frontend", DependencyAdvisoryStrategy.moduleOf("ledger.test.ts", single));
        // Two modules is a different question, and there the answer stays "cannot be placed".
        assertNull(DependencyAdvisoryStrategy.moduleOf("Somewhere.java", project()));
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(VerificationContext context, Requirement requirement) {
    }

    private Requirement requirementA() {
        return Requirement.builder("REQ-A", "Module a must behave.").title("A")
                .attribute("paths", "module-a/src/main/java/A.java").build();
    }

    private Requirement requirementB() {
        return Requirement.builder("REQ-B", "Module b must behave.").title("B")
                .attribute("paths", "module-b/src/main/java/B.java").build();
    }

    private Requirement requirementOrphan() {
        return Requirement.builder("REQ-ORPHAN", "No code was declared for this requirement.")
                .title("Orphan").build();
    }

    private ProjectInfo project() {
        return ProjectInfo.builder(ProjectRef.of(tmp))
                .language(LanguageId.JAVA)
                .addModule(new ModuleInfo("module-a", tmp.resolve("module-a"), "jar", List.of(), List.of(), "17"))
                .addModule(new ModuleInfo("module-b", tmp.resolve("module-b"), "jar", List.of(), List.of(), "17"))
                .build();
    }

    /**
     * A context over two modules where only {@code vulnerableModule} resolves the advisory, so a test
     * can show that an advisory is charged to one requirement and not to another.
     */
    private Fixture fixture(
            Severity failOn, DependencyVulnerability vulnerability, Requirement requirement, String vulnerableModule) {
        List<String> vulnerable = List.of("com.example:vulnerable:jar:1.0:compile");
        List<String> safe = List.of("com.example:safe:jar:2.0:compile");
        // The inventory keys each module by its language, so that a same-named module in another
        // language cannot be charged with this one's dependencies.
        Map<String, List<String>> byModule = Map.of(
                DependencyReport.moduleKey(LanguageId.JAVA, "module-a"),
                vulnerableModule.equals("module-a") ? vulnerable : safe,
                DependencyReport.moduleKey(LanguageId.JAVA, "module-b"),
                vulnerableModule.equals("module-b") ? vulnerable : safe);
        DependencyReport dependencies = new DependencyReport(true, "maven-dependencies", "", 1,
                List.of("com.example:vulnerable:jar:1.0:compile", "com.example:safe:jar:2.0:compile"), byModule,
                PackageCoordinate.parseMaven(List.of("com.example:vulnerable:jar:1.0:compile",
                        "com.example:safe:jar:2.0:compile")),
                List.of(vulnerability),
                AdvisoryScan.performed("osv", "https://api.osv.dev", Instant.now(), "queried 1"));

        VerificationConfig defaults = VerificationConfig.defaults();
        VerificationConfig config = new VerificationConfig(
                defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                defaults.mutation(), defaults.propertyTesting(), defaults.staticAnalysis(),
                new VerificationConfig.Security(true, new VerificationConfig.VulnerabilityScan(
                        true, "osv", "https://api.osv.dev", Duration.ofSeconds(5), 100, failOn)),
                defaults.llm(), defaults.report(), defaults.sandbox(), List.of());

        VerificationContext context = VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(ProjectRef.of(tmp))
                .projectInfo(project())
                .config(config)
                .workspace(new WorkspaceManager.Workspace(tmp, WorkspaceManager.Mode.IN_PLACE, "test"))
                .artifacts(new ArtifactStore(tmp.resolve("artifacts")))
                .budget(new BudgetUsage.Tracker(Budget.defaults()))
                .sandbox(new LocalProcessSandbox(List.of(tmp)))
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
        context.state().dependencies(dependencies);
        return new Fixture(context, requirement);
    }

    private StrategyOutcome verify(Fixture fixture) {
        RequirementCodeMapper mapper = RequirementCodeMapper.build(
                fixture.context().projectInfo(), new RequirementSet(List.of(fixture.requirement())));
        return new DependencyAdvisoryStrategy(mapper)
                .verify(fixture.context(), fixture.requirement(), List.of());
    }
}

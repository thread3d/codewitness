package dev.codewitness.storage;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DependencyVulnerability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementProperty;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.EnvironmentSnapshot;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.result.PhaseRecord;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.result.VerificationRun;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds small but complete {@link VerificationReport} fixtures out of the real core classes, so the
 * storage tests exercise the same objects the verification engine produces.
 */
final class ReportFixtures {

    static final Instant STARTED = Instant.parse("2026-10-06T10:15:30Z");
    static final Instant FINISHED = Instant.parse("2026-10-06T10:21:00Z");

    private ReportFixtures() {
    }

    static ProjectInfo project(Path root) {
        return project(ProjectRef.of(root));
    }

    static ProjectInfo project(ProjectRef ref) {
        Path root = ref.root();
        return ProjectInfo.builder(ref)
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .buildRoot(root)
                .javaVersion("17")
                .addModule(new ModuleInfo(
                        "demo",
                        root,
                        "jar",
                        List.of(root.resolve("src/main/java")),
                        List.of(root.resolve("src/test/java")),
                        "17"))
                .addTestFramework("junit5")
                .attribute("descriptor", "pom.xml")
                .addDetectedDescriptor(root.resolve("pom.xml"))
                .build();
    }

    static RequirementSet requirements() {
        VerificationObligation boundary = new VerificationObligation(
                "OB-1",
                "REQ-1",
                "discount must never exceed 1000",
                ObligationKind.BOUNDARY_VALUE,
                ObligationOrigin.REQUIREMENT_TEXT,
                "com.example.Calc#discount",
                Map.of("limit", 1000),
                false);
        Requirement capped = Requirement.builder("REQ-1", "The discount must never exceed 1000.")
                .title("Discount cap")
                .source("requirements.md", 12)
                .property(RequirementProperty.of("bound", "max", 1000))
                .attribute("owner", "finance")
                .obligations(List.of(boundary))
                .build();

        VerificationObligation nullHandling = new VerificationObligation(
                "OB-2",
                "REQ-2",
                "the API must reject null input",
                ObligationKind.NULL_HANDLING,
                ObligationOrigin.LLM_DERIVED,
                null,
                Map.of(),
                true);
        Requirement rejectsNull = Requirement.builder("REQ-2", "The API must reject null input.")
                .title("Null handling")
                .obligations(List.of(nullHandling))
                .build();

        return new RequirementSet(List.of(capped, rejectsNull));
    }

    static List<RequirementVerdict> verdicts() {
        ObligationOutcome boundaryOutcome = new ObligationOutcome(
                "OB-1",
                RequirementStatus.FAILED,
                "cap exceeded by 0.01",
                List.of("EV-1"),
                List.of("CE-1"),
                3);
        ObligationOutcome nullOutcome = ObligationOutcome.unverified("OB-2", "no execution was scheduled");
        return List.of(
                new RequirementVerdict(
                        "REQ-1",
                        RequirementStatus.FAILED,
                        "a counterexample was reproduced",
                        List.of("EV-1", "EV-2"),
                        List.of(boundaryOutcome),
                        List.of("behaviour above 1e9 remains unknown"),
                        List.of("CE-1"),
                        true),
                new RequirementVerdict(
                        "REQ-2",
                        RequirementStatus.UNVERIFIED,
                        "only advisory analysis was available",
                        List.of(),
                        List.of(nullOutcome),
                        List.of("null handling path"),
                        List.of(),
                        false));
    }

    static List<Evidence> evidence(String runId, int count) {
        List<Evidence> evidence = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            evidence.add(Evidence.builder("EV-" + index, EvidenceType.TEST)
                    .runId(runId)
                    .status(EvidenceStatus.REFUTES)
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .description("executed test case " + index)
                    .source("maven-surefire")
                    .timestamp(STARTED.plusSeconds(index))
                    .command(List.of("mvn", "-q", "-Dtest=CalcTest", "test"))
                    .input("case", "case-" + index)
                    .input("boundary", 1000)
                    .output("output line " + index)
                    .result("assertion failure")
                    .metadata("durationMs", 120 + index)
                    .artifact(Path.of("/tmp/codewitness/artifacts/report-" + index + ".xml"))
                    .requirement("REQ-1")
                    .obligation("OB-1")
                    .generatedByLlm(false)
                    .executedIndependently(true)
                    .reproducible(true)
                    .reproductionCommand("mvn -Dtest=CalcTest#discount test")
                    .build());
        }
        return evidence;
    }

    static List<Counterexample> counterexamples() {
        return List.of(new Counterexample(
                "CE-1",
                "REQ-1",
                "OB-1",
                "discount exceeded the cap",
                Map.of("amount", 1000.01, "currency", "EUR"),
                "discount <= 1000",
                "1000.01",
                "discount never exceeds the cap",
                "CalcTest#discount",
                "EV-1",
                42L,
                new Counterexample.ReproductionRecipe(
                        "run the failing test",
                        List.of("mvn", "-Dtest=CalcTest#discount", "test"),
                        Path.of("/tmp/codewitness/ce/CalcTest.java"),
                        42L,
                        Map.of("db", "memory")),
                Counterexample.DiscoveryMethod.EXISTING_TEST));
    }

    static List<PhaseRecord> phases() {
        return List.of(
                new PhaseRecord("build", true, true, Duration.ofMillis(4200), "build succeeded",
                        List.of("EV-1"), null),
                new PhaseRecord("static-analysis", false, false, Duration.ZERO, "skipped",
                        List.of(), "no analyzer available"));
    }

    static EnvironmentSnapshot environment(Path root) {
        return new EnvironmentSnapshot(
                "0.1.0",
                "17.0.10",
                "Eclipse Adoptium",
                "macOS",
                "maven",
                "3.9.9",
                "abc123",
                "requests-hash",
                "config-hash",
                "deepseek-chat",
                "PROCESS",
                "local process sandbox",
                root.toString(),
                STARTED,
                FINISHED);
    }

    static TestResult tests(Path root) {
        List<TestCaseResult> cases = List.of(
                TestCaseResult.passed("com.example.CalcTest", "adds", Duration.ofMillis(12)),
                TestCaseResult.failed("com.example.CalcTest", "discount", Duration.ofMillis(20),
                        "java.lang.AssertionError", "expected 1000 but was 1000.01",
                        "at com.example.CalcTest.discount(CalcTest.java:42)"));
        return TestResult.from(cases, Duration.ofMillis(32), List.of(root.resolve("target/surefire-reports")),
                "2 tests executed");
    }

    static List<Finding> findings() {
        return List.of(
                new Finding("spotbugs", "DM_DEFAULT_ENCODING", Severity.MEDIUM, FindingCategory.CORRECTNESS,
                        "Reliance on default encoding", "src/main/java/com/example/Calc.java", 17, "demo",
                        Map.of("rank", "18")),
                Finding.of("pmd", Severity.LOW, FindingCategory.STYLE, "unused import"));
    }

    static CoverageReport coverage(Path root) {
        return new CoverageReport(true, "jacoco", null, 120, 150, 30, 44,
                Map.of("com.example.Calc", 80.0), List.of(root.resolve("target/jacoco.xml")));
    }

    static MutationReport mutation(Path root) {
        SurvivingMutant survivor = new SurvivingMutant("M-1", MutantStatus.SURVIVED,
                "ConditionalsBoundaryMutator", "com.example.Calc", "discount", 42,
                "changed <= to <", null);
        return new MutationReport(true, "pitest", null, 10, 7, 3, 0, 0,
                List.of(survivor), List.of(root.resolve("target/mutations.xml")));
    }

    static DependencyReport dependencies() {
        DependencyVulnerability vulnerability = new DependencyVulnerability(
                "com.example:lib", "1.0", "CVE-2026-0001", Severity.HIGH, "prototype pollution", "NVD");
        return new DependencyReport(true, "dependency-check", null, 3,
                List.of("com.example:lib:1.0"), List.of(vulnerability));
    }

    static VerificationReport report(String runId, Path root, Instant startedAt, int evidenceCount) {
        return report(runId, project(root), startedAt, evidenceCount);
    }

    /**
     * Builds a complete report for an explicit project description.
     *
     * @param runId         run identifier
     * @param project       project description; its root also anchors coverage and test report paths
     * @param startedAt     run start
     * @param evidenceCount number of evidence records to produce
     * @return the report
     */
    static VerificationReport report(String runId, ProjectInfo project, Instant startedAt, int evidenceCount) {
        Path root = project.project().root();
        VerificationRun run = new VerificationRun(
                runId,
                project.project(),
                RunStatus.COMPLETED,
                phases(),
                environment(root),
                Budget.defaults(),
                new BudgetUsage(Duration.ofSeconds(45), 2, 12, 3, false, null),
                root.resolve(".codewitness/workspace"),
                root.resolve("report.json"),
                root.resolve("report.html"),
                startedAt,
                startedAt.plusSeconds(270),
                List.of("mutation testing was limited to one module"));
        VerificationReport.BuildSummary build = new VerificationReport.BuildSummary(
                true, true, List.of("mvn", "-q", "test"), 4200L, 0, 2, "compiled");
        return VerificationReport.builder(runId, project, requirements())
                .verdicts(verdicts())
                .evidence(evidence(runId, evidenceCount))
                .counterexamples(counterexamples())
                .build(build)
                .tests(tests(root))
                .coverage(coverage(root))
                .mutation(mutation(root))
                .findings(findings())
                .dependencies(dependencies())
                .run(run)
                .limitation("no security scanner was available")
                .build();
    }
}

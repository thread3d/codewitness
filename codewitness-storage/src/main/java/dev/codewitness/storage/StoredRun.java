package dev.codewitness.storage;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.result.VerificationRun;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A verification run read back from the database.
 *
 * <p>Everything the report renderer needs is reconstructed into core domain objects, so a stored run
 * can be re-rendered long after the original process exited and long after the project directory was
 * deleted. {@link #toReport()} rebuilds the original {@link VerificationReport}.
 *
 * <p>All collections are non-null; absent optional parts of the original report are represented by
 * their core "not measured" / "not attempted" values.
 *
 * @param runId          unique run identifier
 * @param project        reference to the verified project
 * @param projectInfo    project description used for the run; synthesised from {@code project} when
 *                       the run was stored without one
 * @param run            durable run record (status, phases, environment, budget); {@code null} when
 *                       the stored report carried no {@link VerificationRun}
 * @param overallStatus  overall requirement status derived from the stored verdicts
 * @param requirements   the requirement set that was verified
 * @param verdicts       per-requirement verdicts, in requirement order
 * @param evidence       every piece of evidence produced by the run, in production order
 * @param counterexamples every observed counterexample, in production order
 * @param tests          aggregated test result, including individual test cases
 * @param findings       static analysis findings
 * @param build          build summary
 * @param coverage       coverage result
 * @param mutation       mutation result
 * @param dependencies   dependency analysis result
 * @param limitations    known limitations of the run
 * @param reportJson     canonical JSON report supplied by the caller, or {@code null} when the run
 *                       was stored without one
 */
public record StoredRun(
        String runId,
        ProjectRef project,
        ProjectInfo projectInfo,
        VerificationRun run,
        RequirementStatus overallStatus,
        RequirementSet requirements,
        List<RequirementVerdict> verdicts,
        List<Evidence> evidence,
        List<Counterexample> counterexamples,
        TestResult tests,
        List<Finding> findings,
        VerificationReport.BuildSummary build,
        CoverageReport coverage,
        MutationReport mutation,
        DependencyReport dependencies,
        List<String> limitations,
        String reportJson) {

    public StoredRun {
        verdicts = List.copyOf(verdicts == null ? List.of() : verdicts);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        counterexamples = List.copyOf(counterexamples == null ? List.of() : counterexamples);
        findings = List.copyOf(findings == null ? List.of() : findings);
        limitations = List.copyOf(limitations == null ? List.of() : limitations);
    }

    /** @return {@code true} when a canonical JSON report was stored with this run. */
    public boolean hasReportJson() {
        return reportJson != null && !reportJson.isBlank();
    }

    /** @return the verdict for a requirement, when one was stored. */
    public Optional<RequirementVerdict> verdict(String requirementId) {
        if (requirementId == null) {
            return Optional.empty();
        }
        return verdicts.stream().filter(v -> requirementId.equals(v.requirementId())).findFirst();
    }

    /** @return the evidence with the given id, when it was stored. */
    public Optional<Evidence> evidenceById(String evidenceId) {
        if (evidenceId == null) {
            return Optional.empty();
        }
        return evidence.stream().filter(e -> evidenceId.equals(e.id())).findFirst();
    }

    /** @return every stored counterexample attached to a requirement, in stored order. */
    public List<Counterexample> counterexamplesFor(String requirementId) {
        if (requirementId == null) {
            return List.of();
        }
        return counterexamples.stream()
                .filter(c -> requirementId.equals(c.requirementId()))
                .collect(Collectors.toList());
    }

    /**
     * Rebuilds the report that was stored, including raw phase results, so that the renderer can
     * produce the same document it produced when the run finished.
     *
     * @return a fresh {@link VerificationReport}
     */
    public VerificationReport toReport() {
        VerificationReport.Builder builder =
                VerificationReport.builder(runId, projectInfo, requirements)
                        .verdicts(verdicts)
                        .evidence(evidence)
                        .counterexamples(counterexamples)
                        .tests(tests)
                        .findings(findings)
                        .build(build)
                        .coverage(coverage)
                        .mutation(mutation)
                        .dependencies(dependencies)
                        .limitations(limitations);
        if (run != null) {
            builder.run(run);
        }
        return builder.build();
    }
}

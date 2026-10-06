package dev.codewitness.core.result;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The complete result of a verification run: the input claim set, the evidence produced, and the
 * verdicts derived from it.
 *
 * <p>This object is what gets serialised to JSON, rendered to HTML and persisted to SQLite. It holds
 * raw phase results rather than pre-computed percentages so that reports can always show the
 * underlying evidence instead of a single misleading score.
 */
public final class VerificationReport {

    private final String runId;
    private final ProjectInfo project;
    private final RequirementSet requirements;
    private final List<RequirementVerdict> verdicts;
    private final List<Evidence> evidence;
    private final List<Counterexample> counterexamples;
    private final BuildSummary build;
    private final TestResult tests;
    private final CoverageReport coverage;
    private final MutationReport mutation;
    private final List<Finding> findings;
    private final DependencyReport dependencies;
    private final VerificationRun run;
    private final List<String> limitations;

    private VerificationReport(Builder builder) {
        this.runId = builder.runId;
        this.project = builder.project;
        this.requirements = builder.requirements;
        this.verdicts = List.copyOf(builder.verdicts);
        this.evidence = List.copyOf(builder.evidence);
        this.counterexamples = List.copyOf(builder.counterexamples);
        this.build = builder.build;
        this.tests = builder.tests;
        this.coverage = builder.coverage;
        this.mutation = builder.mutation;
        this.findings = List.copyOf(builder.findings);
        this.dependencies = builder.dependencies;
        this.run = builder.run;
        this.limitations = List.copyOf(builder.limitations);
    }

    public static Builder builder(String runId, ProjectInfo project, RequirementSet requirements) {
        return new Builder(runId, project, requirements);
    }

    public String runId() {
        return runId;
    }

    public ProjectInfo project() {
        return project;
    }

    public RequirementSet requirements() {
        return requirements;
    }

    public List<RequirementVerdict> verdicts() {
        return verdicts;
    }

    public List<Evidence> evidence() {
        return evidence;
    }

    public List<Counterexample> counterexamples() {
        return counterexamples;
    }

    public BuildSummary build() {
        return build;
    }

    public TestResult tests() {
        return tests;
    }

    public CoverageReport coverage() {
        return coverage;
    }

    public MutationReport mutation() {
        return mutation;
    }

    public List<Finding> findings() {
        return findings;
    }

    public DependencyReport dependencies() {
        return dependencies;
    }

    public VerificationRun run() {
        return run;
    }

    /** Known limitations of this run: things CodeWitness could not verify. */
    public List<String> limitations() {
        return limitations;
    }

    public Optional<RequirementVerdict> verdict(String requirementId) {
        return verdicts.stream().filter(v -> v.requirementId().equals(requirementId)).findFirst();
    }

    public Optional<Evidence> evidenceById(String id) {
        return evidence.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    public Map<RequirementStatus, Long> verdictCounts() {
        Map<RequirementStatus, Long> counts = new EnumMap<>(RequirementStatus.class);
        for (RequirementStatus status : RequirementStatus.values()) {
            counts.put(status, 0L);
        }
        for (RequirementVerdict verdict : verdicts) {
            counts.merge(verdict.status(), 1L, Long::sum);
        }
        return counts;
    }

    public Map<Severity, Long> findingsBySeverity() {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0L);
        }
        for (Finding finding : findings) {
            counts.merge(finding.severity(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * Overall status of the run, derived from the individual verdicts.
     *
     * <p>Deliberately conservative: any counterexample makes the whole run {@code FAILED}; blocked
     * verification never becomes a pass; and a run where nothing could be verified stays
     * {@code UNVERIFIED}.
     */
    public RequirementStatus overallStatus() {
        if (verdicts.isEmpty()) {
            return RequirementStatus.UNVERIFIED;
        }
        Map<RequirementStatus, Long> counts = verdictCounts();
        if (counts.get(RequirementStatus.FAILED) > 0) {
            return RequirementStatus.FAILED;
        }
        if (counts.get(RequirementStatus.VERIFIED) == verdicts.size()) {
            return RequirementStatus.VERIFIED;
        }
        if (counts.get(RequirementStatus.BLOCKED) == verdicts.size()) {
            return RequirementStatus.BLOCKED;
        }
        if (counts.get(RequirementStatus.VERIFIED) == 0
                && counts.get(RequirementStatus.PARTIALLY_VERIFIED) == 0) {
            return RequirementStatus.UNVERIFIED;
        }
        return RequirementStatus.PARTIALLY_VERIFIED;
    }

    public String describe() {
        Map<RequirementStatus, Long> counts = verdictCounts();
        return overallStatus() + " (" + counts.get(RequirementStatus.VERIFIED) + " verified, "
                + counts.get(RequirementStatus.PARTIALLY_VERIFIED) + " partial, "
                + counts.get(RequirementStatus.FAILED) + " failed, "
                + counts.get(RequirementStatus.UNVERIFIED) + " unverified, "
                + counts.get(RequirementStatus.BLOCKED) + " blocked)";
    }

    public static final class Builder {
        private final String runId;
        private final ProjectInfo project;
        private final RequirementSet requirements;
        private final List<RequirementVerdict> verdicts = new ArrayList<>();
        private final List<Evidence> evidence = new ArrayList<>();
        private final List<Counterexample> counterexamples = new ArrayList<>();
        private BuildSummary build = BuildSummary.notAttempted("not run");
        private TestResult tests = TestResult.notExecuted("not run");
        private CoverageReport coverage = CoverageReport.notMeasured("none", "not run");
        private MutationReport mutation = MutationReport.notMeasured("none", "not run");
        private final List<Finding> findings = new ArrayList<>();
        private DependencyReport dependencies = DependencyReport.notAnalysed("none", "not run");
        private VerificationRun run;
        private final List<String> limitations = new ArrayList<>();

        private Builder(String runId, ProjectInfo project, RequirementSet requirements) {
            this.runId = runId;
            this.project = project;
            this.requirements = requirements;
        }

        public Builder verdicts(List<RequirementVerdict> value) {
            this.verdicts.clear();
            this.verdicts.addAll(value);
            return this;
        }

        public Builder evidence(List<Evidence> value) {
            this.evidence.clear();
            this.evidence.addAll(value);
            return this;
        }

        public Builder counterexamples(List<Counterexample> value) {
            this.counterexamples.clear();
            this.counterexamples.addAll(value);
            return this;
        }

        public Builder build(BuildSummary value) {
            this.build = value;
            return this;
        }

        public Builder tests(TestResult value) {
            this.tests = value;
            return this;
        }

        public Builder coverage(CoverageReport value) {
            this.coverage = value;
            return this;
        }

        public Builder mutation(MutationReport value) {
            this.mutation = value;
            return this;
        }

        public Builder findings(List<Finding> value) {
            this.findings.clear();
            this.findings.addAll(value);
            return this;
        }

        public Builder dependencies(DependencyReport value) {
            this.dependencies = value;
            return this;
        }

        public Builder run(VerificationRun value) {
            this.run = value;
            return this;
        }

        public Builder limitation(String value) {
            this.limitations.add(value);
            return this;
        }

        public Builder limitations(List<String> value) {
            this.limitations.clear();
            this.limitations.addAll(value);
            return this;
        }

        public VerificationReport build() {
            return new VerificationReport(this);
        }
    }

    /**
     * Compact view of the build phase for reports.
     *
     * @param attempted whether a build was attempted
     * @param success   whether it succeeded
     * @param command   command that was executed
     * @param durationMillis duration in milliseconds
     * @param errorCount number of compiler errors
     * @param warningCount number of compiler warnings
     * @param note      additional context
     */
    public record BuildSummary(
            boolean attempted,
            boolean success,
            List<String> command,
            long durationMillis,
            int errorCount,
            int warningCount,
            String note) {

        public static BuildSummary notAttempted(String note) {
            return new BuildSummary(false, false, List.of(), 0, 0, 0, note);
        }

        public String displayCommand() {
            return String.join(" ", command);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("attempted", attempted);
            map.put("success", success);
            map.put("command", command);
            map.put("durationMillis", durationMillis);
            map.put("errors", errorCount);
            map.put("warnings", warningCount);
            map.put("note", note);
            return map;
        }
    }
}

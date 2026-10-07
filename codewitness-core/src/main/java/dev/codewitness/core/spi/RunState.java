package dev.codewitness.core.spi;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.TestResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Results of the deterministic phases, accumulated while a run proceeds.
 *
 * <p>The requirement strategies run after the deterministic phases and need to see what those phases
 * observed - a passing test suite means something quite different when mutation testing says the
 * tests are weak. Rather than threading half a dozen parameters through every strategy, the engine
 * publishes the phase results here.
 */
public final class RunState {

    private BuildResult build = BuildResult.notAttempted("build phase has not run");
    private TestResult existingTests = TestResult.notExecuted("test phase has not run");
    private CoverageReport coverage = CoverageReport.notMeasured("none", "coverage phase has not run");
    private MutationReport mutation = MutationReport.notMeasured("none", "mutation phase has not run");
    private DependencyReport dependencies = DependencyReport.notAnalysed("none", "dependency phase has not run");
    private final List<Finding> findings = new ArrayList<>();
    private boolean staticAnalysisRan;
    private final List<String> infrastructureNotes = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger counterexampleCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    /** @return the next counterexample identifier of the form {@code CE-001}. */
    public String nextCounterexampleId() {
        return String.format(java.util.Locale.ROOT, "CE-%03d", counterexampleCounter.incrementAndGet());
    }

    public BuildResult build() {
        return build;
    }

    public void build(BuildResult value) {
        this.build = value;
    }

    public TestResult existingTests() {
        return existingTests;
    }

    public void existingTests(TestResult value) {
        this.existingTests = value;
    }

    public CoverageReport coverage() {
        return coverage;
    }

    public void coverage(CoverageReport value) {
        this.coverage = value;
    }

    public MutationReport mutation() {
        return mutation;
    }

    public void mutation(MutationReport value) {
        this.mutation = value;
    }

    public DependencyReport dependencies() {
        return dependencies;
    }

    public void dependencies(DependencyReport value) {
        this.dependencies = value;
    }

    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    public void addFindings(List<Finding> value) {
        this.findings.addAll(value);
    }

    /**
     * @return {@code true} when the static-analysis phase actually ran.
     *
     * <p>This is deliberately separate from {@link #findings()}: a clean run produces no findings, and
     * "the analyzers found nothing" must never be confused with "no analyzer ran". The two lead to
     * opposite verdicts, so the fact is recorded rather than inferred.
     */
    public boolean staticAnalysisRan() {
        return staticAnalysisRan;
    }

    /** Records that the static-analysis phase completed, whether or not it reported any finding. */
    public void staticAnalysisRan(boolean value) {
        this.staticAnalysisRan = value;
    }

    /** Notes about infrastructure problems that affected the run; surfaced in the report. */
    public List<String> infrastructureNotes() {
        return List.copyOf(infrastructureNotes);
    }

    public void note(String message) {
        if (message != null && !message.isBlank()) {
            infrastructureNotes.add(message);
        }
    }

    public boolean buildFailed() {
        return build.attempted() && !build.success();
    }

    public boolean buildSucceeded() {
        return build.attempted() && build.success();
    }
}

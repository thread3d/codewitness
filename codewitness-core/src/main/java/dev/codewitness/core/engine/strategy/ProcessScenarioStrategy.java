package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.core.spi.VerificationStrategy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a declared command and asserts on what it printed and returned.
 *
 * <p>Every other way of executing a requirement calls code from *inside* the project's own test framework. That
 * observes methods, and it cannot observe a program's own interface: the exit code a caller sees, the diagnostics it
 * prints, whether the CLI refuses a bad argument. An exit-code contract is not a property of a method, so before
 * this strategy no scenario could express one — which is why CodeWitness's own exit-code contract sat `UNVERIFIED`
 * in its self-check while every other ground rule was verified by execution.
 *
 * <p>The command runs through the same sandbox as every other process: an allow-listed environment, a hard timeout
 * and capped output. It runs in the isolated workspace, so a command that writes files writes into the copy and
 * never into the user's tree.
 *
 * <p>A mismatch produces a {@link Counterexample} carrying the argv, the observed exit code and the tails of both
 * streams — the values needed to see what happened, not a stack trace. A match produces deterministic evidence and
 * discharges the obligation the scenario planned. It cannot do more than that: the evidence says this command
 * behaved this way, and the verdict logic decides what that is worth.
 */
public final class ProcessScenarioStrategy implements VerificationStrategy {

    /**
     * Placeholder for the original project tree, so a scenario can name an artefact the copy excludes.
     *
     * <p>Without it, a process scenario that runs a built jar would have to hardcode an absolute path — which works on
     * one machine and fails everywhere else, including CI.
     */
    static final String PROJECT_ROOT_PLACEHOLDER = "${project_root}";

    /** How much of each stream is kept in a counterexample, and in the evidence. */
    private static final int STREAM_TAIL = 2_000;

    @Override
    public String id() {
        return "process-scenarios";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return true;
    }

    @Override
    public StrategyOutcome verify(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        List<Scenario> processScenarios = context.config().scenarios().stream()
                .filter(scenario -> scenario.mode() == Scenario.Mode.PROCESS)
                .filter(scenario -> scenario.requirement().equals(requirement.id()))
                .toList();
        List<Scenario> declared = processScenarios.stream()
                // A scenario the project asked to have as a native test is executed by that test, in the project's
                // own runner. Running the command here as well would execute the same assertion twice and could
                // produce two counterexamples for one violation.
                // This only takes effect when an adapter actually generated the test; `native_test` on a language
                // whose generator cannot write one is reported by the generated-tests phase, which then finds no
                // scenario to run and says so, rather than by silence here.
                .filter(scenario -> !scenario.nativeTest())
                .toList();
        if (declared.isEmpty()) {
            // "No process scenario was declared" is only true when there is none. A scenario that asks for a
            // native test is declared, and saying it is absent denies a statement of the project's own
            // configuration - the same mistake the generated-tests strategy made in the other direction. The
            // outcome still has to be reported, because a requirement with no outcome is treated as
            // unattempted; UNVERIFIED is the honest answer either way, and the reason says which case it is.
            boolean allAskForNativeTests = !processScenarios.isEmpty();
            String reason = allAskForNativeTests
                    ? "The process scenario declared for this obligation asks for a native test, so the command "
                            + "is executed by that test rather than by the process strategy."
                    : "No process scenario was declared for this obligation, so no command was run.";
            String note;
            if (allAskForNativeTests) {
                note = "The process scenario(s) declared for " + requirement.id() + " ask for a native test, so "
                        + "the process strategy did not run their commands: whether the command ran at all "
                        + "depends on the generated-test phase, which writes the native test or reports why it "
                        + "could not.";
            } else {
                note = "No process scenario was declared for " + requirement.id()
                        + ", so nothing about a command's exit code or output was executed.";
            }
            List<ObligationOutcome> unattempted = obligations.stream()
                    .map(obligation -> new ObligationOutcome(obligation.id(), RequirementStatus.UNVERIFIED,
                            reason, List.of(), List.of(), 0))
                    .toList();
            return new StrategyOutcome(id(), requirement.id(), List.of(), List.of(), unattempted,
                    List.of(note));
        }

        List<Evidence> evidence = new ArrayList<>();
        List<Counterexample> counterexamples = new ArrayList<>();
        List<ObligationOutcome> outcomes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Scenario> byId = new LinkedHashMap<>();
        for (Scenario scenario : declared) {
            byId.put(scenario.id(), scenario);
        }

        for (Scenario scenario : declared) {
            Optional<ObligationOutcome> outcome = run(scenario, context, obligationFor(obligations, scenario),
                    evidence, counterexamples, notes);
            outcome.ifPresent(outcomes::add);
        }
        // An obligation whose scenario was not run by this strategy still needs an outcome, or the engine treats it
        // as unattempted; the engine's own default is UNVERIFIED, which is the honest answer here.
        for (VerificationObligation obligation : obligations) {
            if (outcomes.stream().noneMatch(o -> o.obligationId().equals(obligation.id()))) {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.UNVERIFIED,
                        "No process scenario named by this obligation was executed.", List.of(), List.of(), 0));
            }
        }
        return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
    }

    private Optional<ObligationOutcome> run(
            Scenario scenario,
            VerificationContext context,
            Optional<VerificationObligation> obligation,
            List<Evidence> evidence,
            List<Counterexample> counterexamples,
            List<String> notes) {
        Scenario.ProcessExpectation expect = scenario.expect();
        // `${project_root}` is substituted with the original tree. A process scenario that runs an artefact needs it:
        // the isolated copy excludes build output, so a jar the run itself just built is not in the copy, and the
        // alternative would be an absolute path that only works on the machine that wrote it. The command still runs
        // with the sandbox's environment and timeout and with the copy's directory as its working directory; naming
        // the original tree is a deliberate, per-scenario choice, and it is recorded.
        boolean reachesOutside = scenario.command().stream().anyMatch(part -> part.contains(PROJECT_ROOT_PLACEHOLDER));
        List<String> command = scenario.command().stream()
                .map(part -> part.replace(PROJECT_ROOT_PLACEHOLDER,
                        context.projectRef().root().toString()))
                .toList();
        if (reachesOutside) {
            notes.add("Scenario " + scenario.id() + " names " + PROJECT_ROOT_PLACEHOLDER
                    + ", so it ran against the original project tree rather than the isolated copy: the copy excludes "
                    + "build output, and the artefact it asserts on is build output.");
        }
        ProcessSpec spec = ProcessSpec.builder(command, context.projectRoot())
                .timeout(Duration.ofSeconds(scenario.timeoutSeconds()))
                .maxOutputBytes(1_000_000)
                .build();
        ProcessResult result;
        try {
            result = context.runner().run(spec);
        } catch (RuntimeException e) {
            String note = "Scenario " + scenario.id() + " could not start " + String.join(" ", command) + ": "
                    + e.getMessage();
            notes.add(note);
            Evidence couldNotStart = context.evidence().add(context.evidence().newEvidence(EvidenceType.PROCESS_EXECUTION)
                    .description("Process scenario " + scenario.id() + ": could not start")
                    .requirement(scenario.requirement())
                    .command(command)
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.INCONCLUSIVE)
                    .result("not executed: " + e.getMessage())
                    .build());
            evidence.add(couldNotStart);
            return Optional.of(new ObligationOutcome(obligation.map(VerificationObligation::id).orElse("-"),
                    RequirementStatus.UNVERIFIED, note,
                    List.of(couldNotStart.id()), List.of(), 0));
        }

        if (!result.started()) {
            // A command that could not be started says nothing about the requirement: it is the same class of event
            // as a build tool that is not installed, and reporting it as a violated expectation would turn a missing
            // binary into a defect in someone's code.
            String note = "Scenario " + scenario.id() + " could not start " + String.join(" ", command) + ": "
                    + (result.failureReason() == null ? "the sandbox refused to run it" : result.failureReason());
            notes.add(note);
            Evidence notRun = context.evidence().add(context.evidence().newEvidence(EvidenceType.PROCESS_EXECUTION)
                    .description("Process scenario " + scenario.id() + ": not started")
                    .requirement(scenario.requirement())
                    .command(command)
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.INCONCLUSIVE)
                    .result("not executed: " + note)
                    .build());
            evidence.add(notRun);
            return Optional.of(new ObligationOutcome(obligation.map(VerificationObligation::id).orElse("-"),
                    RequirementStatus.UNVERIFIED, note, List.of(notRun.id()), List.of(), 0));
        }

        String stdout = result.stdout();
        String stderr = result.stderr();
        int exitCode = result.exitCode();
        Optional<String> violation = violation(expect, exitCode, stdout, stderr, result);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("command", String.join(" ", command));
        inputs.put("exitCode", String.valueOf(exitCode));
        inputs.put("timedOut", String.valueOf(result.timedOut()));

        if (violation.isPresent()) {
            String reason = violation.get();
            Evidence failed = evidence(context, scenario, command, EvidenceStatus.REFUTES,
                    "the expectation was violated: " + reason, stdout, stderr);
            evidence.add(failed);
            Counterexample counterexample = new Counterexample(
                    context.state().nextCounterexampleId(),
                    scenario.requirement(),
                    obligation.map(VerificationObligation::id).orElse(scenario.id()),
                    "Process scenario " + scenario.id() + " violated its expectation",
                    inputs,
                    describeExpectation(expect),
                    reason,
                    "the declared process expectation",
                    "process scenario " + scenario.id(),
                    failed.id(),
                    null,
                    null,
                    Counterexample.DiscoveryMethod.GENERATED_TEST);
            counterexamples.add(counterexample);
            return Optional.of(new ObligationOutcome(obligation.map(VerificationObligation::id).orElse("-"),
                    RequirementStatus.FAILED, reason, List.of(failed.id()), List.of(counterexample.id()), 1));
        }

        Evidence passed = evidence(context, scenario, command, EvidenceStatus.SUPPORTS,
                "the command behaved as declared: " + describeObserved(expect, exitCode), stdout, stderr);
        evidence.add(passed);
        return Optional.of(new ObligationOutcome(obligation.map(VerificationObligation::id).orElse("-"),
                RequirementStatus.VERIFIED, "The declared command behaved as required: "
                        + describeObserved(expect, exitCode),
                List.of(passed.id()), List.of(), 1));
    }

    /** Builds one evidence record for a process run, shared by the passing and failing paths. */
    private Evidence evidence(
            VerificationContext context,
            Scenario scenario,
            List<String> command,
            EvidenceStatus status,
            String result,
            String stdout,
            String stderr) {
        return context.evidence().add(context.evidence().newEvidence(EvidenceType.PROCESS_EXECUTION)
                .description("Process scenario " + scenario.id() + ": " + String.join(" ", command))
                // Without this the evidence exists but belongs to no requirement, so the verdict cannot see the
                // experiment that discharged the obligation: the requirement came out PARTIALLY_VERIFIED with
                // "0 of 1 obligations remain unverified", which is a true statement in a thoroughly useless way.
                .requirement(scenario.requirement())
                .command(command)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(status)
                .result(result)
                .output("stdout: " + tail(stdout) + System.lineSeparator() + "stderr: " + tail(stderr))
                .metadata("scenario", scenario.id())
                .metadata("exitCode", describeObservedExit(scenario.expect()))
                .build());
    }

    private static String describeObservedExit(Scenario.ProcessExpectation expect) {
        return expect.exitCode() == null ? "not asserted" : "required " + expect.exitCode();
    }

    /**
     * @return why the run violated the expectation, or empty when it held
     */
    private static Optional<String> violation(
            Scenario.ProcessExpectation expect,
            int exitCode,
            String stdout,
            String stderr,
            ProcessResult result) {
        if (result.timedOut()) {
            return Optional.of("the command did not finish within its timeout, so nothing it printed can be trusted");
        }
        if (expect.exitCode() != null && exitCode != expect.exitCode()) {
            return Optional.of("exit code was " + exitCode + ", required " + expect.exitCode());
        }
        for (String required : expect.stdoutContains()) {
            if (!stdout.contains(required)) {
                return Optional.of("standard output did not contain \"" + required + "\"");
            }
        }
        for (String required : expect.stderrContains()) {
            if (!stderr.contains(required)) {
                return Optional.of("standard error did not contain \"" + required + "\"");
            }
        }
        return Optional.empty();
    }

    private static String describeExpectation(Scenario.ProcessExpectation expect) {
        List<String> parts = new ArrayList<>();
        if (expect.exitCode() != null) {
            parts.add("exit code " + expect.exitCode());
        }
        for (String required : expect.stdoutContains()) {
            parts.add("standard output containing \"" + required + "\"");
        }
        for (String required : expect.stderrContains()) {
            parts.add("standard error containing \"" + required + "\"");
        }
        return String.join("; ", parts);
    }

    private static String describeObserved(Scenario.ProcessExpectation expect, int exitCode) {
        return expect.exitCode() == null ? "exit code " + exitCode : "exit code " + exitCode
                + " as required";
    }

    private static Optional<VerificationObligation> obligationFor(
            List<VerificationObligation> obligations, Scenario scenario) {
        return obligations.stream()
                .filter(obligation -> scenario.id().equals(obligation.target()))
                .findFirst();
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        String stripped = text.strip();
        return stripped.length() <= STREAM_TAIL ? stripped : "..." + stripped.substring(stripped.length() - STREAM_TAIL);
    }
}

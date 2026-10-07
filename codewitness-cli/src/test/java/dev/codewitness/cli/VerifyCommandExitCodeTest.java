package dev.codewitness.cli;

import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.RunStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exit codes are part of the interface, so they are pinned here directly.
 *
 * <p>The subtle one is {@code 0}. It means "no requirement produced a counterexample" - not "everything
 * was verified". A run in which nothing could be executed also exits {@code 0} by default, which is
 * exactly the kind of green that gets wired into a pipeline and read as a pass. {@code --fail-on-unverified}
 * is the gate for callers who need evidence rather than the absence of failure.
 */
class VerifyCommandExitCodeTest {

    @Test
    @DisplayName("a counterexample is exit 1")
    void failedIsOne() {
        assertEquals(1, VerifyCommand.exitCode(
                List.of(RequirementStatus.FAILED, RequirementStatus.VERIFIED), RunStatus.COMPLETED, false));
    }

    @Test
    @DisplayName("a blocked verification is exit 2, never 0")
    void blockedIsTwo() {
        assertEquals(2, VerifyCommand.exitCode(
                List.of(RequirementStatus.BLOCKED), RunStatus.BLOCKED, false));
        // The requirement verdicts can look innocuous while the run itself was blocked.
        assertEquals(2, VerifyCommand.exitCode(
                List.of(RequirementStatus.UNVERIFIED), RunStatus.BLOCKED, false));
    }

    @Test
    @DisplayName("all verified is exit 0")
    void allVerifiedIsZero() {
        assertEquals(0, VerifyCommand.exitCode(
                List.of(RequirementStatus.VERIFIED, RequirementStatus.VERIFIED), RunStatus.COMPLETED, false));
    }

    @Test
    @DisplayName("without the flag, an unexecuted requirement still exits 0")
    void unverifiedIsZeroByDefault() {
        assertEquals(0, VerifyCommand.exitCode(
                List.of(RequirementStatus.UNVERIFIED), RunStatus.COMPLETED, false));
        assertEquals(0, VerifyCommand.exitCode(
                List.of(RequirementStatus.PARTIALLY_VERIFIED), RunStatus.COMPLETED, false));
    }

    @Test
    @DisplayName("with --fail-on-unverified, anything short of verified is exit 1")
    void failOnUnverifiedGatesOnEvidence() {
        assertEquals(1, VerifyCommand.exitCode(
                List.of(RequirementStatus.UNVERIFIED), RunStatus.COMPLETED, true));
        assertEquals(1, VerifyCommand.exitCode(
                List.of(RequirementStatus.PARTIALLY_VERIFIED, RequirementStatus.VERIFIED),
                RunStatus.COMPLETED, true));
        assertEquals(1, VerifyCommand.exitCode(List.of(), RunStatus.COMPLETED, true),
                "a run with no requirements verified nothing, so it fails the gate");
        assertEquals(0, VerifyCommand.exitCode(
                List.of(RequirementStatus.VERIFIED), RunStatus.COMPLETED, true));
    }

    @Test
    @DisplayName("a counterexample outranks the flag, and blocked outranks unverified")
    void precedenceIsStable() {
        assertEquals(1, VerifyCommand.exitCode(List.of(RequirementStatus.FAILED), RunStatus.COMPLETED, true));
        assertEquals(2, VerifyCommand.exitCode(
                List.of(RequirementStatus.UNVERIFIED), RunStatus.BLOCKED, true));
    }
}

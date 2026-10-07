package dev.codewitness.java.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Maven goals CodeWitness uses to run a project's tests.
 *
 * <p>Both rules here are regressions found by pointing CodeWitness at its own source tree. The test
 * phase used to invoke the {@code surefire:test} plugin goal, which resolves inter-module
 * dependencies from the local repository rather than the reactor, so a multi-module project was
 * tested against whatever stale artifacts happened to be installed there - in that self-check,
 * against a {@code codewitness-core} jar predating the language it was testing. And it let Maven stop
 * at the first failing module, so every later module was skipped and produced no reports at all.
 */
class MavenTestGoalsTest {

    private final MavenBuildSystem maven = new MavenBuildSystem();

    @Test
    void runsTheTestLifecyclePhaseSoReactorModulesAreUsed() {
        List<String> goals = maven.testGoals();

        assertEquals("test", goals.getLast(), goals.toString());
        assertFalse(goals.contains("surefire:test"),
                "a plugin goal resolves sibling modules from the local repository, not the reactor: " + goals);
    }

    @Test
    void keepsGoingAfterAModuleWhoseTestsFail() {
        List<String> goals = maven.testGoals();

        assertTrue(goals.contains("-Dmaven.test.failure.ignore=true"),
                "without this Maven skips every module after the first failing one: " + goals);
    }

    @Test
    void neverSkipsTheTestsItExistsToRun() {
        List<String> goals = maven.testGoals();

        assertFalse(goals.contains("-DskipTests"), goals.toString());
        assertFalse(goals.contains("-Dmaven.test.skip=true"), goals.toString());
    }

    @Test
    void theBuildPhaseStillCompilesTestsWithoutRunningThem() {
        List<String> goals = maven.compileGoals();

        assertEquals("test-compile", goals.getLast(), goals.toString());
        assertTrue(goals.contains("-DskipTests"), goals.toString());
    }
}

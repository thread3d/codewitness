package dev.codewitness.kotlin;

import dev.codewitness.core.model.ProjectInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The build tool operations the Kotlin adapter needs.
 *
 * <p>Kotlin on the JVM is built by the project's own Gradle or Maven build, with the Kotlin Gradle
 * plugin or {@code kotlin-maven-plugin} doing the compiling. Everything that touches a build tool goes
 * through this interface, so that the verification providers never name a build tool themselves.
 *
 * <p>The interface is deliberately a separate copy of the Java adapter's rather than a shared one. What
 * differs is not the shape but every value in it: Kotlin compiles through {@code compileKotlin} and the
 * {@code kotlin-maven-plugin}'s {@code test-compile} execution, reports diagnostics in the Kotlin
 * compiler's own format, and a Gradle goal that is right for Java ({@code compileJava}) is wrong here.
 * Sharing the interface would only hide that.
 */
public interface KotlinBuildSystem {

    /** @return {@code maven} or {@code gradle}. */
    String id();

    /** @return the directory the build must be invoked from (the build root). */
    Path buildRoot(ProjectInfo project);

    /** @return goal arguments that compile the Kotlin main and test sources. */
    List<String> compileGoals();

    /** @return arguments that run the project's existing test suite. */
    List<String> testGoals();

    /**
     * @return goal arguments that run the test suite and <em>fail</em> when a test fails.
     *
     * <p>Deliberately not {@link #testGoals()}. The test phase wants the build tool to carry on after a
     * failing test so that every module still writes its report, because CodeWitness reads failures from the
     * reports. A mutation run has only the exit status to decide whether a mutant was killed, so for that
     * one run a failing test has to fail the command.
     */
    List<String> mutationGoals();

    /**
     * @param testClassNames fully qualified names of the test classes to run
     * @param properties     system properties to pass to the test JVM where the build tool supports it
     * @return goal arguments that compile and run exactly those test classes
     */
    List<String> targetedTestGoals(List<String> testClassNames, Map<String, String> properties);

    /** @return arguments that compile with warnings enabled, for the Kotlin compiler diagnostics. */
    List<String> lintGoals();

    /** @return the directories (relative to a module) where this build tool writes test reports. */
    List<String> testReportDirectories();
}

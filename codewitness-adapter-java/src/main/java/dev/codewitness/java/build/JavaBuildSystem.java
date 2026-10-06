package dev.codewitness.java.build;

import dev.codewitness.core.model.ProjectInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The build tool operations the Java adapter needs.
 *
 * <p>Everything that touches a build tool goes through this interface, so that adding another JVM
 * build tool (or another language's) is a new implementation rather than a change to the
 * verification providers.
 */
public interface JavaBuildSystem {

    /** @return {@code maven} or {@code gradle}. */
    String id();

    /** @return the directory the build must be invoked from (the build root). */
    Path buildRoot(ProjectInfo project);

    /** @return goal arguments that compile main and test sources. */
    List<String> compileGoals();

    /** @return goal arguments that run the existing test suite. */
    List<String> testGoals();

    /**
     * @param testClassNames fully qualified names of the test classes to run
     * @param properties     system properties to pass to the test JVM where the build tool supports it
     * @return goal arguments that compile and run exactly those test classes
     */
    List<String> targetedTestGoals(List<String> testClassNames, Map<String, String> properties);

    /** @return arguments that make the compiler emit all warnings, for the javac analyzer. */
    List<String> lintGoals();

    /** @return the directories (relative to a module) where this build tool writes test reports. */
    List<String> testReportDirectories();
}

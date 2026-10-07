package dev.codewitness.java;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the registered Java providers say produced their results.
 *
 * <p>The Maven providers are one object registered under several interfaces, so the commands it
 * exposes are easy to conflate. They were: a single {@code command} override satisfied both the test
 * runner and the dependency analyzer, and the dependency evidence came out claiming the test command.
 * These tests go through the registry rather than the concrete providers, because the conflation
 * happened in the wiring.
 */
class JavaProviderCommandTest {

    @TempDir
    Path tmp;

    @Test
    void theDependencyAnalyzerNamesItsOwnCommandNotTheTestCommand() throws IOException {
        Registry registry = registry();

        List<String> testCommand = registry.testCommand();
        List<String> dependencyCommand = registry.dependencyCommand();

        assertTrue(testCommand.contains(registry.wrapper().toString()), testCommand.toString());
        assertEquals("test", testCommand.getLast(), testCommand.toString());
        assertTrue(dependencyCommand.contains("dependency:list"), dependencyCommand.toString());
        assertNotEquals(testCommand, dependencyCommand,
                "the dependency evidence must not claim the test command produced it");
    }

    @Test
    void theDependencyCommandRunsALifecyclePhaseBeforeTheGoal() throws IOException {
        List<String> command = registry().dependencyCommand();

        // A goal-only invocation resolves a reactor module's siblings from the local repository, where
        // they do not exist until someone has installed them, so `dependency:list` on its own fails on
        // a multi-module project. One lifecycle phase ahead of it makes the reactor resolvable.
        int phase = command.indexOf("compile");
        assertTrue(phase >= 0, "no lifecycle phase precedes the goal: " + command);
        assertTrue(phase < command.indexOf("dependency:list"), command.toString());
    }

    /** A temp project with a wrapper, its registered providers, and its resolved commands. */
    private record Registry(
            VerificationContext context,
            VerificationProviderRegistry providers,
            ProjectInfo project,
            Path wrapper) {

        List<String> testCommand() {
            return providers.testRunners(project).getFirst().testCommand(context);
        }

        List<String> dependencyCommand() {
            return providers.dependencyAnalyzers(project).getFirst().dependencyCommand(context);
        }
    }

    private Registry registry() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path wrapper = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .addModule(new ModuleInfo("sample", root, "jar", List.of(), List.of(), "17"))
                .build();
        VerificationProviderRegistry providers = new VerificationProviderRegistry();
        JavaAdapterBundle.register(new LanguageAdapterRegistry(), providers, false);
        return new Registry(TestContexts.context(project, tmp.resolve("run"), List.of()),
                providers, project, wrapper);
    }
}

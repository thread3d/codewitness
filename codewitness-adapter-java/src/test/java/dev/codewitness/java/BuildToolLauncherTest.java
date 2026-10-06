package dev.codewitness.java;

import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build tool resolution order: the project's own wrapper first, then the installed tool, then
 * {@code PATH}. Every test passes a directory that contains only what the test puts there, so the
 * result never depends on the machine running the suite.
 */
class BuildToolLauncherTest {

    @TempDir
    Path tmp;

    /** A runner that refuses to execute: resolution must be decidable without running anything. */
    private final BuildToolLauncher launcher =
            new BuildToolLauncher(ProcessRunner.denying("build tools must not run in unit tests"));

    @Test
    void theProjectWrapperInTheWorkingDirectoryWins() throws IOException {
        Path directory = Files.createDirectories(tmp.resolve("work"));
        Path wrapper = Files.writeString(directory.resolve("mvnw"), "#!/bin/sh\n");

        List<String> resolved = launcher.resolve(project(BuildSystemId.MAVEN), directory);

        assertNotNull(resolved);
        assertTrue(resolved.get(resolved.size() - 1).endsWith("mvnw"), resolved.toString());
        assertEquals(wrapper.getFileName().toString(), Path.of(resolved.get(resolved.size() - 1)).getFileName().toString());
    }

    @Test
    void aWrapperDeclaredInTheProjectInfoIsUsedFromTheWorkingDirectory() throws IOException {
        Path directory = Files.createDirectories(tmp.resolve("work"));
        Path wrapper = Files.writeString(directory.resolve("mvnw"), "#!/bin/sh\n");
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(directory))
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .buildWrapper(wrapper)
                .build();

        List<String> resolved = launcher.resolve(project, directory);

        assertNotNull(resolved);
        assertEquals(wrapper, Path.of(resolved.get(resolved.size() - 1)));
    }

    @Test
    void aGradleProjectLooksForGradlew() throws IOException {
        Path directory = Files.createDirectories(tmp.resolve("gradle-work"));
        Files.writeString(directory.resolve("gradlew"), "#!/bin/sh\n");

        List<String> resolved = launcher.resolve(project(BuildSystemId.GRADLE), directory);

        assertNotNull(resolved);
        assertTrue(resolved.get(resolved.size() - 1).endsWith("gradlew"), resolved.toString());
    }

    @Test
    void noWrapperAndNoInstalledToolResolvesToNullOrAPathMaven() throws IOException {
        Path directory = Files.createDirectories(tmp.resolve("empty-work"));

        List<String> resolved = launcher.resolve(project(BuildSystemId.MAVEN), directory);

        // Maven may be installed on PATH in the environment running the tests; both outcomes are
        // documented behaviour. What must never happen is a failure or a guess at a missing file.
        if (resolved != null) {
            assertTrue(resolved.get(0).endsWith("mvn") || resolved.get(0).endsWith("mvn.cmd"), resolved.toString());
            assertTrue(Files.isExecutable(Path.of(resolved.get(0))), resolved.toString());
        }
    }

    @Test
    void versionOfAnAbsentExecutableIsUnknown() {
        assertEquals("unknown", launcher.version(null, tmp, Duration.ofSeconds(1)));
    }

    @Test
    void commandKeepsTheExecutableFirst() {
        assertEquals(List.of("mvn", "-B", "test"),
                BuildToolLauncher.command(List.of("mvn"), List.of("-B", "test")));
        assertEquals(List.of("mvn"), BuildToolLauncher.command(List.of("mvn"), List.of()));
    }

    @Test
    void mavenIsOnlyAvailableForMavenProjects() throws IOException {
        Path directory = Files.createDirectories(tmp.resolve("mvn-work"));
        Files.writeString(directory.resolve("mvnw"), "#!/bin/sh\n");

        assertTrue(launcher.mavenAvailable(project(BuildSystemId.MAVEN), directory));
        assertFalse(launcher.mavenAvailable(project(BuildSystemId.GRADLE), directory),
                "a Gradle project is not a Maven project even when mvnw is present");
    }

    private ProjectInfo project(BuildSystemId buildSystem) {
        Path root = tmp.resolve("project-" + buildSystem.id());
        return ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .buildSystem(buildSystem)
                .addModule(new ModuleInfo("sample", root, "jar", List.of(), List.of(), "17"))
                .build();
    }
}

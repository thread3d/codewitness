package dev.codewitness.kotlin;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.VerificationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a real {@link VerificationContext} around a temporary Kotlin project.
 *
 * <p>The detector, the parsers, the analyzers and the generator under test only read source files and
 * configuration, so the context is complete as soon as it has a project, a workspace, an artefact store, a
 * ledger, a budget and a sandbox. Nothing executes project code in these tests; the sandbox is present
 * because the context requires one, and it is the real one so that a test which does need to run a command
 * runs it the way a verification run would.
 */
public final class KotlinTestContexts {

    /** A Maven build that compiles Kotlin, for tests that care about the detected build system. */
    public static final String MAVEN_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>kotlin-example</artifactId>
              <version>1.0.0</version>
              <properties>
                <kotlin.version>2.2.20</kotlin.version>
              </properties>
              <build>
                <sourceDirectory>src/main/kotlin</sourceDirectory>
                <plugins>
                  <plugin>
                    <groupId>org.jetbrains.kotlin</groupId>
                    <artifactId>kotlin-maven-plugin</artifactId>
                    <version>${kotlin.version}</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    /** A Gradle build that applies the Kotlin JVM plugin, for tests that care about Gradle. */
    public static final String GRADLE_BUILD = """
            plugins {
                kotlin("jvm") version "2.2.20"
            }
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
            }
            """;

    private KotlinTestContexts() {
    }

    /** @return a single-element list when the directory exists, an empty list otherwise. */
    public static List<Path> existing(Path directory) {
        return Files.isDirectory(directory) ? List.of(directory) : List.of();
    }

    /** Writes a UTF-8 file below {@code base}, creating parent directories as needed. */
    public static Path write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** Creates a tree with the conventional Kotlin source and test directories. */
    public static Path project(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/kotlin"));
        Files.createDirectories(root.resolve("src/test/kotlin"));
        return root;
    }

    /** @return project information detected from a real directory. */
    public static ProjectInfo projectInfo(Path root) {
        return KotlinProjectDetector.inspect(ProjectRef.of(root));
    }

    /** A context with the default configuration and no scenarios. */
    public static VerificationContext context(Path root, Path runDirectory) {
        return context(root, runDirectory, List.of());
    }

    /** A context with the given scenarios declared in code. */
    public static VerificationContext context(Path root, Path runDirectory, List<Scenario> scenarios) {
        return context(projectInfo(root), runDirectory, scenarios, config(scenarios),
                new LocalProcessSandbox(List.of(root, runDirectory)));
    }

    /** A context whose project info, configuration and sandbox the caller supplies. */
    public static VerificationContext context(
            ProjectInfo info, Path runDirectory, List<Scenario> scenarios, VerificationConfig config,
            ExecutionSandbox sandbox) {
        ProjectRef projectRef = info.project();
        WorkspaceManager.Workspace workspace =
                WorkspaceManager.prepare(projectRef, runDirectory, WorkspaceManager.Mode.IN_PLACE);
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(projectRef)
                .projectInfo(info)
                .config(config)
                .workspace(workspace)
                .artifacts(new ArtifactStore(runDirectory))
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .sandbox(sandbox)
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }

    /** @return the default configuration with the given scenarios declared in code. */
    public static VerificationConfig config(List<Scenario> scenarios) {
        VerificationConfig defaults = VerificationConfig.defaults();
        return new VerificationConfig(
                defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                defaults.mutation(), defaults.propertyTesting(), defaults.staticAnalysis(),
                defaults.security(), defaults.llm(), defaults.report(), defaults.sandbox(), scenarios);
    }
}

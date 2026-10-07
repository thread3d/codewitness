package dev.codewitness.csharp;

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
 * Builds a real {@link VerificationContext} around a temporary .NET project.
 *
 * <p>The detector, the parsers, the analyzers and the generator under test only read source files and
 * configuration, so the context is complete as soon as it has a project, a workspace, an artefact store,
 * a ledger, a budget and a sandbox. Nothing executes project code in these tests; the sandbox is present
 * because the context requires one, and it is the real one so that a test that does need to run a command
 * runs it the way a verification run would.
 */
public final class CSharpTestContexts {

    /** A minimal library project file, for tests that only need a project to exist. */
    public static final String LIBRARY_PROJECT = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <TargetFramework>net8.0</TargetFramework>
                <Nullable>enable</Nullable>
              </PropertyGroup>
            </Project>
            """;

    /** A test project file that references the xUnit test host, for tests that need one. */
    public static final String TEST_PROJECT = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <TargetFramework>net8.0</TargetFramework>
              </PropertyGroup>
              <ItemGroup>
                <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.11.1" />
                <PackageReference Include="xunit" Version="2.9.2" />
              </ItemGroup>
            </Project>
            """;

    private CSharpTestContexts() {
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

    /** @return project information detected from a real directory. */
    public static ProjectInfo projectInfo(Path root) {
        return CSharpProjectDetector.inspect(ProjectRef.of(root));
    }

    /** A context with the default configuration and no scenarios. */
    public static VerificationContext context(Path root, Path runDirectory) {
        return context(root, runDirectory, List.of());
    }

    /** A context with the given scenarios declared in code. */
    public static VerificationContext context(Path root, Path runDirectory, List<Scenario> scenarios) {
        ProjectRef projectRef = ProjectRef.of(root);
        WorkspaceManager.Workspace workspace =
                WorkspaceManager.prepare(projectRef, runDirectory, WorkspaceManager.Mode.IN_PLACE);
        ExecutionSandbox sandbox = new LocalProcessSandbox(List.of(root, runDirectory));
        VerificationConfig defaults = VerificationConfig.defaults();
        VerificationConfig config = new VerificationConfig(
                defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                defaults.mutation(), defaults.propertyTesting(), defaults.staticAnalysis(),
                defaults.security(), defaults.llm(), defaults.report(), defaults.sandbox(), scenarios);
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(projectRef)
                .projectInfo(projectInfo(root))
                .config(config)
                .workspace(workspace)
                .artifacts(new ArtifactStore(runDirectory))
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .sandbox(sandbox)
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }
}

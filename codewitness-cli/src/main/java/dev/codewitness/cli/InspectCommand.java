package dev.codewitness.cli;

import dev.codewitness.core.config.ConfigLoader;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.RunId;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementParser;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.LanguageDetectorRegistry;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.cpp.CppAdapterBundle;
import dev.codewitness.csharp.CSharpAdapterBundle;
import dev.codewitness.go.GoAdapterBundle;
import dev.codewitness.java.JavaAdapterBundle;
import dev.codewitness.kotlin.KotlinAdapterBundle;
import dev.codewitness.javascript.JavaScriptAdapterBundle;
import dev.codewitness.php.PhpAdapterBundle;
import dev.codewitness.python.PythonAdapterBundle;
import dev.codewitness.ruby.RubyAdapterBundle;
import dev.codewitness.swift.SwiftAdapterBundle;
import dev.codewitness.rust.RustAdapterBundle;
import dev.codewitness.typescript.TypeScriptAdapterBundle;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code codewitness inspect <project>} - detection only.
 *
 * <p>Answers "what did CodeWitness see?" without executing anything, which makes it the first thing
 * to run when a verification result looks surprising.
 */
@Command(name = "inspect", description = "Detect language, build system, modules, sources and tests.")
public final class InspectCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project directory to inspect.")
    private String project;

    @Option(names = "--config", paramLabel = "<file>", description = "Configuration file.")
    private Path configFile;

    @Option(names = "--requirements", paramLabel = "<file>", description = "Requirements file to summarise.")
    private Path requirementsFile;

    @Override
    public Integer call() {
        ProjectRef projectRef = CliSupport.projectRef(project);
        ConfigLoader.Loaded loaded = CliSupport.loadConfig(projectRef.root(), configFile);
        VerificationConfig config = loaded.config();

        LanguageAdapterRegistry languages = new LanguageAdapterRegistry();
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        JavaAdapterBundle.register(languages, providers, true);
        PythonAdapterBundle.register(languages, providers, true);
        RustAdapterBundle.register(languages, providers, true);
        GoAdapterBundle.register(languages, providers, true);
        CSharpAdapterBundle.register(languages, providers, true);
        KotlinAdapterBundle.register(languages, providers, true);
        CppAdapterBundle.register(languages, providers, true);
        PhpAdapterBundle.register(languages, providers, true);
        RubyAdapterBundle.register(languages, providers, true);
        SwiftAdapterBundle.register(languages, providers, true);
        JavaScriptAdapterBundle.register(languages, providers, true);
        TypeScriptAdapterBundle.register(languages, providers, true);

        List<LanguageAdapter> adapters = languages.selectAll(projectRef, config.language().adapter());
        if (adapters.isEmpty()) {
            System.err.println("No language adapter supports " + projectRef.root());
            System.err.println("Registered adapters: " + languages.ids());
            return 2;
        }

        Path runDirectory = projectRef.root().resolve(".codewitness/inspect-" + RunId.generate());
        ArtifactStore artifacts = new ArtifactStore(runDirectory);
        WorkspaceManager.Workspace workspace = WorkspaceManager.prepare(projectRef, runDirectory,
                WorkspaceManager.Mode.COPY);
        ExecutionSandbox sandbox = new LocalProcessSandbox(List.of(workspace.root(), runDirectory));
        var context = dev.codewitness.core.spi.VerificationContext.builder()
                .runId("inspect")
                .projectRef(projectRef)
                .projectInfo(ProjectInfo.builder(projectRef).build())
                .config(config)
                .workspace(workspace)
                .artifacts(artifacts)
                .budget(new dev.codewitness.core.result.BudgetUsage.Tracker(config.toBudget()))
                .sandbox(sandbox)
                .evidenceLedger(new dev.codewitness.core.evidence.EvidenceLedger("inspect"))
                .build();

        // Inspection is per language, exactly as verification is: a project with a Java service and a
        // TypeScript frontend has two descriptions, and printing one of them would hide the other.
        System.out.println("CODEWITNESS  inspect");
        System.out.println("project          " + projectRef.name() + " (" + projectRef.identifier() + ")");
        System.out.println("path             " + projectRef.root());
        System.out.println("adapters         " + adapters.stream().map(LanguageAdapter::id).toList());
        // Languages present that nothing here can verify: printed for the same reason they are reported, so
        // that an inspection cannot be read as a complete picture of a tree it only partly covers.
        var uncovered = new LanguageDetectorRegistry().discover().detect(projectRef.root(),
                adapters.stream().map(adapter -> adapter.inspect(projectRef, context).language())
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        System.out.println("unverified       " + (uncovered.isEmpty() ? "none"
                : uncovered.stream()
                        .map(detection -> detection.displayName() + " (" + detection.evidence() + ")")
                        .toList()));
        for (LanguageAdapter adapter : adapters) {
            ProjectInfo info = adapter.inspect(projectRef, context);
            if (adapters.size() > 1) {
                System.out.println();
            }
            print(info, adapter, providers, config);
        }

        if (requirementsFile != null) {
            RequirementSet requirements = RequirementParser.parseFile(requirementsFile);
            System.out.println();
            System.out.println("Requirements (" + requirements.size() + ")");
            for (Requirement requirement : requirements.requirements()) {
                System.out.printf("  %-8s %s%n", requirement.id(), requirement.title());
                if (!requirement.properties().isEmpty()) {
                    System.out.println("           interpretation: "
                            + dev.codewitness.core.requirements.HeuristicPropertyExtractor
                                    .describe(requirement.properties()));
                }
                System.out.println("           obligations: " + requirement.obligations().size());
            }
            System.out.println();
            System.out.println("Note: the natural language text of each requirement is authoritative; the");
            System.out.println("      structured properties above are CodeWitness interpretations.");
        }

        System.out.println();
        System.out.println("Workspace used for inspection: " + workspace.root());
        // Inspection only reads the copy; leaving it behind would accumulate for no benefit.
        deleteRecursively(runDirectory);
        return 0;
    }

    private void print(
            ProjectInfo info,
            LanguageAdapter adapter,
            VerificationProviderRegistry providers,
            VerificationConfig config) {
        System.out.println("adapter          " + adapter.id());
        System.out.println("language         " + info.language());
        System.out.println("build system     " + info.buildSystem());
        System.out.println("build root       " + info.buildRoot());
        System.out.println("java version     " + info.javaVersion().orElse("undeclared"));
        System.out.println("build wrapper    " + info.buildWrapper().map(Path::toString).orElse("none"));
        System.out.println("test frameworks  "
                + (info.testFrameworks().isEmpty() ? "none detected" : String.join(", ", info.testFrameworks())));
        System.out.println("capabilities     " + describeCapabilities(adapter.capabilities()));
        System.out.println("modules          " + info.modules().size());
        for (var module : info.modules()) {
            System.out.println("  - " + module.name() + " (" + module.packaging() + ") at " + module.path());
            module.sourceDirs().forEach(dir -> System.out.println("      main: " + dir));
            module.testSourceDirs().forEach(dir -> System.out.println("      test: " + dir));
        }
        System.out.println("scenarios        " + config.scenarios().size());
        System.out.println("providers        " + providers.providerIds());
        if (info.attributes().containsKey("detectionWarning")) {
            System.out.println("WARNING          " + info.attributes().get("detectionWarning"));
        }
        if (!Files.isRegularFile(info.project().root().resolve("requirements.md"))) {
            System.out.println("note             no requirements.md found at the project root");
        }
    }

    /** Removes the temporary inspection workspace. Best effort: inspection must not fail on cleanup. */
    private static void deleteRecursively(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception _) {
            // an inspector that cannot tidy up is still a useful inspector
        }
    }

    private static String describeCapabilities(java.util.Set<Capability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().toList().toString();
    }
}

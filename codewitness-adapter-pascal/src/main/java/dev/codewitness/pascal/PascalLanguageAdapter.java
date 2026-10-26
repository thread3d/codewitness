package dev.codewitness.pascal;

import dev.codewitness.pascal.analysis.PascalHeuristicAnalyzer;
import dev.codewitness.pascal.analysis.PascalSecurityAnalyzer;
import dev.codewitness.pascal.generate.PascalGeneratedTestRunner;
import dev.codewitness.pascal.generate.PascalScenarioTestGenerator;
import dev.codewitness.pascal.mutation.PascalMutationTester;
import dev.codewitness.support.adapter.AbstractLanguageAdapter;
import dev.codewitness.support.adapter.AdapterProviders;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.pascal.analysis.PascalHeuristicAnalyzer;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Verifies Pascal projects with Free Pascal.
 *
 * <p>Everything the engine does with a Pascal tree goes through this adapter: it recognises the tree by
 * its sources, describes it without compiling anything, compiles it through the program it declares (or
 * through each unit when it declares none), runs the fpcunit suites it finds, and analyses it. The
 * engine holds no knowledge of Pascal, so adding the language meant adding this module and a provider
 * file rather than editing the pipeline.
 *
 * <p>The compiler is resolved and probed during inspection, and the result is recorded as a project
 * attribute. A missing compiler is recorded rather than raised: the adapter has no evidence about code
 * it could not build, and the engine reports that as {@code BLOCKED}, not as a defect.
 */
public final class PascalLanguageAdapter extends AbstractLanguageAdapter {


    /**
     * This language's tools, declared once, for both the adapter and the bundle that registers it.
     *
     * <p>One list, so the phases this adapter runs and the capabilities it advertises cannot drift apart.
     */
    public static final AdapterProviders PROVIDERS = AdapterProviders.builder()
            .compiler(new PascalBuildProvider())
            .testRunner(new PascalTestProvider())
            .mutation(new PascalMutationTester())
            .executor(new PascalGeneratedTestRunner())
            .generator(new PascalScenarioTestGenerator())
            .staticAnalyzer(new PascalHeuristicAnalyzer())
            .securityAnalyzer(new PascalSecurityAnalyzer())
            .build();

    /** Creates the adapter over its declared provider set. */
    public PascalLanguageAdapter() {
        super(PROVIDERS);
    }

    @Override
    public String id() {
        return "pascal";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return PascalProjectDetector.looksLikePascalProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, because that is the tree that will be built and
        // tested; the reported project info keeps the original reference.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = PascalProjectDetector.inspect(workspaceRef);
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(detected.language())
                .buildSystem(detected.buildSystem())
                .buildRoot(context.projectRoot())
                .sourceExtensions(detected.sourceExtensions())
                .attributes(detected.attributes());
        for (ModuleInfo module : detected.modules()) {
            builder.addModule(module);
        }
        for (String framework : detected.testFrameworks()) {
            builder.addTestFramework(framework);
        }
        for (Path descriptor : detected.detectedDescriptors()) {
            builder.addDetectedDescriptor(descriptor);
        }
        detected.buildWrapper().ifPresent(builder::buildWrapper);

        String fpc = PascalTool.resolve("fpc", context.projectRoot(), System.getenv());
        builder.attribute(PascalTool.ATTR_FPC, fpc);
        Path configDirectory = PascalTool.configDirectory(fpc);
        if (configDirectory != null) {
            builder.attribute(PascalTool.ATTR_CONFIG_PATH, configDirectory.toString());
        }
        PascalTool.Probe probe = PascalTool.probe(context, fpc);
        if (probe.available()) {
            builder.attribute(PascalTool.ATTR_VERSION, probe.version());
            builder.attribute("runtimeVersion", "Free Pascal " + probe.version());
        } else {
            builder.attribute(PascalTool.ATTR_PROBE_FAILURE, probe.failureReason());
        }
        return builder.build();
    }
}

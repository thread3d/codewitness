package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.engine.RequirementCodeMapper;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DependencyVulnerability;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationStrategy;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fails a requirement whose code ships with a dependency that has a high severity advisory.
 *
 * <p>This is the contract the dependency scan exists for: finding an advisory is not enough, it has to
 * bear on a verdict. The attribution is deliberately narrow. An advisory is attributed to a requirement
 * only when the module that <em>resolves</em> the vulnerable artifact is the module the requirement's code
 * lives in - because "your project depends on something vulnerable" is a property of the artifact that
 * ships the code, not of every claim anyone ever wrote down. A requirement in {@code module-a} is not
 * refuted by a dependency that only {@code module-b} pulls in.
 *
 * <p>Two things never fail a requirement here. An advisory whose severity the source did not state
 * ({@link Severity#UNKNOWN}) refutes nothing, and an advisory on a requirement whose code could not be
 * attributed to a module is reported but not charged to it. Both are stated in the report rather than
 * silently resolved.
 *
 * <p>The reproduction recipe is the one place an advisory touches a language's toolchain, and this class
 * does not know any of them. It asks the registered {@link VerificationTools.DependencyAnalyzer} that
 * claims the advisory's ecosystem, so a new language supplies its own dependency-path explanation by
 * implementing two methods on its analyzer rather than by editing the core.
 */
public final class DependencyAdvisoryStrategy implements VerificationStrategy {

    private final RequirementCodeMapper mapper;
    private final VerificationProviderRegistry providers;

    public DependencyAdvisoryStrategy(RequirementCodeMapper mapper) {
        this(mapper, VerificationProviderRegistry.empty());
    }

    public DependencyAdvisoryStrategy(RequirementCodeMapper mapper, VerificationProviderRegistry providers) {
        this.mapper = mapper;
        this.providers = providers == null ? VerificationProviderRegistry.empty() : providers;
    }

    @Override
    public String id() {
        return "dependency-advisories";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return true;
    }

    @Override
    public StrategyOutcome verify(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        List<Evidence> evidence = new ArrayList<>();
        List<Counterexample> counterexamples = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        DependencyReport dependencies = context.state().dependencies();
        if (dependencies == null || dependencies.vulnerabilities().isEmpty()) {
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, List.of(), notes);
        }

        Severity threshold = context.config().security().vulnerabilities().failOn();
        List<String> modules = modulesOf(requirement, context.projectInfo());
        if (modules.isEmpty()) {
            notes.add("Dependency advisories were not attributed to " + requirement.id()
                    + ": no file covered by this requirement could be traced to a module, so CodeWitness "
                    + "cannot say which module would deliver it.");
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, List.of(), notes);
        }

        List<DependencyVulnerability> relevant = new ArrayList<>();
        List<String> relevantModules = new ArrayList<>();
        for (DependencyVulnerability vulnerability : dependencies.vulnerabilities()) {
            String module = moduleShipping(dependencies, vulnerability, modules,
                    context.projectInfo().language());
            if (module != null) {
                relevant.add(vulnerability);
                relevantModules.add(module);
            }
        }
        if (relevant.isEmpty()) {
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, List.of(), notes);
        }

        List<DependencyVulnerability> failing = new ArrayList<>();
        for (DependencyVulnerability vulnerability : relevant) {
            if (threshold != null && vulnerability.severity().atLeastAsSevereAs(threshold)
                    && vulnerability.severity() != Severity.UNKNOWN) {
                failing.add(vulnerability);
            }
        }

        Evidence analysisEvidence = context.evidence().newEvidence(EvidenceType.DEPENDENCY_SCAN)
                .description("Dependency advisories affecting the module(s) this requirement ships in: "
                        + relevant.size() + " (" + failing.size() + " at "
                        + (threshold == null ? "no threshold" : threshold + " or above") + ")")
                .source(id())
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(failing.isEmpty() ? EvidenceStatus.INCONCLUSIVE : EvidenceStatus.REFUTES)
                .result(render(relevant, relevantModules))
                .requirement(requirement.id())
                .metadata("threshold", threshold == null ? "none" : threshold.name())
                .metadata("modules", modules)
                .build();
        context.evidence().add(analysisEvidence);
        evidence.add(analysisEvidence);

        for (int i = 0; i < relevant.size(); i++) {
            DependencyVulnerability vulnerability = relevant.get(i);
            if (!failing.contains(vulnerability)) {
                continue;
            }
            String module = relevantModules.get(i);
            Counterexample counterexample = counterexample(
                    context, requirement, vulnerability, module, threshold, analysisEvidence.id());
            counterexamples.add(counterexample);
            Evidence ceEvidence = context.evidence().newEvidence(EvidenceType.COUNTEREXAMPLE)
                    .description("Counterexample " + counterexample.id() + " (" + requirement.id()
                            + ") demonstrated by a dependency advisory")
                    .source(id())
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.REFUTES)
                    .result(vulnerability.severity() + " " + vulnerability.vulnerabilityId() + " in "
                            + vulnerability.dependency() + " " + vulnerability.version()
                            + ", resolved by module " + module)
                    .requirement(requirement.id())
                    .metadata("advisory", vulnerability.vulnerabilityId())
                    .metadata("module", module)
                    .build();
            context.evidence().add(ceEvidence);
            evidence.add(ceEvidence);
        }

        if (threshold == null) {
            notes.add(relevant.size() + " advisory(ies) affect " + requirement.id()
                    + ", but the configured threshold is 'none', so none of them fail it.");
        } else {
            long below = relevant.size() - failing.size();
            if (below > 0) {
                notes.add(below + " advisory(ies) affect " + requirement.id() + " below " + threshold
                        + " and did not fail it; they are listed in the report.");
            }
        }
        return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, List.of(), notes);
    }

    /** @return the module that resolves the vulnerable artifact, when the requirement ships in one. */
    private static String moduleShipping(
            DependencyReport dependencies, DependencyVulnerability vulnerability, List<String> requirementModules,
            LanguageId language) {
        for (Map.Entry<String, List<String>> entry : dependencies.dependenciesByModule().entrySet()) {
            if (!requirementModules.contains(entry.getKey())) {
                continue;
            }
            for (String display : entry.getValue()) {
                for (PackageCoordinate coordinate : dependencies.coordinates()) {
                    // The module breakdown is keyed by how the inventory displays a dependency, so the
                    // typed coordinate is matched back through that same string. Comparing the parsed
                    // name and version rather than the display string is what keeps this correct for
                    // ecosystems whose names do not survive being split apart again.
                    if (coordinate.display().equals(display)
                            && coordinate.name().equals(vulnerability.dependency())
                            && coordinate.version().equals(vulnerability.version())) {
                        // The key carries the language so that same-named modules in different languages
                        // cannot be confused; what a reader is shown is the module's own name.
                        return DependencyReport.moduleName(language, entry.getKey());
                    }
                }
            }
        }
        return null;
    }

    /** @return the modules that own the files this requirement covers, most specific first. */
    private List<String> modulesOf(Requirement requirement, ProjectInfo project) {
        List<String> modules = new ArrayList<>();
        for (String file : mapper.filesFor(requirement.id())) {
            String module = moduleOf(file, project);
            // The inventory keys each module by the language it belongs to, so the requirement's module
            // has to be looked up under the same key. Without the prefix a dependency resolved by a
            // same-named module in another language would be charged to this requirement.
            String key = module == null ? null : DependencyReport.moduleKey(project.language(), module);
            if (key != null && !modules.contains(key)) {
                modules.add(key);
            }
        }
        return modules;
    }

    /**
     * @return the module a file belongs to, or {@code null} when it cannot be placed.
     *
     * <p>Matching is on the module's own directory or name rather than on a path relative to the project
     * root. A requirement's declared path is written the way a human writes it ({@code module-a/src/...}),
     * while the module paths come from inspection and - in isolated mode - point into the run's copy of
     * the project, so comparing the two through a common root is not reliable. A module that <em>is</em>
     * the project root owns anything a nested module does not claim.
     *
     * <p>When nothing places the file, a language with exactly one module owns it: every line of that
     * language's code ships in that one module, so its resolved dependencies are the dependencies that code
     * ships with. That is a fact rather than a guess, and it is what makes an advisory attributable for the
     * single-module projects npm, PyPI, crates.io and Go always produce. A language with several modules
     * still answers {@code null}, because charging a dependency of one module to code in another is the
     * mis-attribution this method exists to prevent.
     */
    static String moduleOf(String file, ProjectInfo project) {
        if (file == null || file.isBlank()) {
            return null;
        }
        String normalized = file.strip().replace('\\', '/');
        Path projectRoot = project.project().root().toAbsolutePath().normalize();
        String bestName = null;
        int bestScore = -1;
        for (ModuleInfo module : project.modules()) {
            boolean rootModule = module.path().toAbsolutePath().normalize().equals(projectRoot);
            boolean under = rootModule || names(normalized, module);
            int score = rootModule ? 0 : Math.max(module.name().length(), directoryName(module).length());
            if (under && score > bestScore) {
                bestScore = score;
                bestName = module.name();
            }
        }
        if (bestName == null && project.modules().size() == 1) {
            return project.modules().getFirst().name();
        }
        return bestName;
    }

    /** @return whether the path names the module by its directory or by its artifact name. */
    private static boolean names(String normalizedPath, ModuleInfo module) {
        for (String name : List.of(module.name(), directoryName(module))) {
            if (!name.isEmpty() && (normalizedPath.equals(name) || normalizedPath.startsWith(name + "/")
                    || normalizedPath.contains("/" + name + "/"))) {
                return true;
            }
        }
        return false;
    }

    private static String directoryName(ModuleInfo module) {
        Path fileName = module.path().getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private Counterexample counterexample(
            VerificationContext context,
            Requirement requirement,
            DependencyVulnerability vulnerability,
            String module,
            Severity threshold,
            String evidenceId) {

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("dependency", vulnerability.dependency());
        inputs.put("version", vulnerability.version());
        inputs.put("advisory", vulnerability.vulnerabilityId());
        inputs.put("severity", vulnerability.severity().name());
        inputs.put("module", module);
        inputs.put("source", vulnerability.source());

        return new Counterexample(
                context.state().nextCounterexampleId(),
                requirement.id(),
                null,
                "Module " + module + ", which delivers " + requirement.id() + ", resolves "
                        + vulnerability.dependency() + " " + vulnerability.version()
                        + ", which has a " + vulnerability.severity() + " advisory ("
                        + vulnerability.vulnerabilityId() + ").",
                inputs,
                "Code that ships with a dependency carrying an advisory at " + threshold
                        + " or above must not be reported as satisfying its requirements.",
                vulnerability.severity() + " advisory " + vulnerability.vulnerabilityId() + ": "
                        + vulnerability.description(),
                vulnerability.severity() + " advisory " + vulnerability.vulnerabilityId(),
                vulnerability.source() + " advisory " + vulnerability.vulnerabilityId(),
                evidenceId,
                null,
                new Counterexample.ReproductionRecipe(
                        "Show the dependency path that pulls the advisory into module " + module
                                + ", then read the advisory"
                                + dependencyPathHint(vulnerability),
                        dependencyPathCommand(context, vulnerability),
                        null,
                        null,
                        Map.of("dependency", vulnerability.dependency(),
                                "version", vulnerability.version(),
                                "advisory", vulnerability.vulnerabilityId(),
                                "module", module)),
                Counterexample.DiscoveryMethod.DEPENDENCY_ADVISORY);
    }

    /**
     * @return the analyzer that claims the advisory's ecosystem, or empty when none is registered.
     *
     * <p>An advisory's ecosystem is the only thing that identifies which language's toolchain can explain
     * it, so the lookup is by ecosystem rather than by the project the strategy is running over. The core
     * therefore knows no ecosystem and no tool: a new adapter answers for its own.
     */
    private Optional<VerificationTools.DependencyAnalyzer> pathAdvisor(
            DependencyVulnerability vulnerability) {
        return providers.dependencyAnalyzerFor(vulnerability.ecosystem());
    }

    /** @return the vulnerable dependency as the typed coordinate the hook takes. */
    private static PackageCoordinate coordinateOf(DependencyVulnerability vulnerability) {
        return PackageCoordinate.of(vulnerability.ecosystem(), vulnerability.dependency(),
                vulnerability.version());
    }

    /**
     * @return a command that shows why the vulnerable artifact is on the module's graph, as the analyzer
     *     that owns the advisory's ecosystem built it.
     *
     * <p>The analyzer decides, because only it knows whether a command can be built for the project's own
     * toolchain - and whether the project is even the one the command would work in. When no analyzer
     * claims the ecosystem, or the analyzer declines to build a command, the recipe carries none rather
     * than inventing one, and {@link #dependencyPathHint} still names the tool a human should reach for.
     */
    private List<String> dependencyPathCommand(
            VerificationContext context, DependencyVulnerability vulnerability) {
        return pathAdvisor(vulnerability)
                .map(analyzer -> analyzer.dependencyPathCommand(context, coordinateOf(vulnerability)))
                .orElse(List.of());
    }

    /**
     * @return prose naming the ecosystem's own tool for showing a dependency path, as the analyzer that
     *     owns the ecosystem phrased it, or the empty string when none is registered or the ecosystem has
     *     no such tool.
     */
    private String dependencyPathHint(DependencyVulnerability vulnerability) {
        return pathAdvisor(vulnerability)
                .map(analyzer -> analyzer.dependencyPathHint(coordinateOf(vulnerability)))
                .orElse("");
    }

    private static String render(List<DependencyVulnerability> vulnerabilities, List<String> modules) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < vulnerabilities.size(); i++) {
            DependencyVulnerability vulnerability = vulnerabilities.get(i);
            text.append(vulnerability.severity()).append(' ').append(vulnerability.vulnerabilityId())
                    .append(" in ").append(vulnerability.dependency()).append(' ').append(vulnerability.version())
                    .append(" (module ").append(modules.get(i)).append("): ")
                    .append(vulnerability.description()).append('\n');
        }
        return text.toString().strip();
    }
}

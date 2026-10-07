package dev.codewitness.core.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The result of inspecting a project: what language it is written in, which build system drives it,
 * where its sources and tests live and which test frameworks are configured.
 *
 * <p>This type is intentionally language-agnostic. Language specific detail is carried in
 * {@link #attributes()}.
 */
public final class ProjectInfo {

    private final ProjectRef project;
    private final LanguageId language;
    private final BuildSystemId buildSystem;
    private final Path buildRoot;
    private final List<ModuleInfo> modules;
    private final Set<String> testFrameworks;
    private final Path buildWrapper;
    private final String javaVersion;
    private final Set<String> sourceExtensions;
    private final Map<String, String> attributes;
    private final List<Path> detectedDescriptors;

    private ProjectInfo(Builder builder) {
        this.project = builder.project;
        this.language = builder.language;
        this.buildSystem = builder.buildSystem;
        this.buildRoot = builder.buildRoot;
        this.modules = List.copyOf(builder.modules);
        this.testFrameworks = Collections.unmodifiableSet(new LinkedHashSet<>(builder.testFrameworks));
        this.buildWrapper = builder.buildWrapper;
        this.javaVersion = builder.javaVersion;
        this.sourceExtensions = Collections.unmodifiableSet(new LinkedHashSet<>(builder.sourceExtensions));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
        this.detectedDescriptors = List.copyOf(builder.detectedDescriptors);
    }

    public static Builder builder(ProjectRef project) {
        return new Builder(project);
    }

    public ProjectRef project() {
        return project;
    }

    public LanguageId language() {
        return language;
    }

    public BuildSystemId buildSystem() {
        return buildSystem;
    }

    /** Directory the build system is invoked from. */
    public Path buildRoot() {
        return buildRoot;
    }

    public List<ModuleInfo> modules() {
        return modules;
    }

    public Set<String> testFrameworks() {
        return testFrameworks;
    }

    /** The project provided build wrapper ({@code mvnw} / {@code gradlew}), if one exists. */
    public Optional<Path> buildWrapper() {
        return Optional.ofNullable(buildWrapper);
    }

    public Optional<String> javaVersion() {
        return Optional.ofNullable(javaVersion);
    }

    /**
     * File extensions, without the leading dot, of the files this project's adapter treats as source.
     *
     * <p>The core uses this to find source and test files without hard coding a language: a mapping,
     * a mutation location or an advisory prompt reads the same rule for every language. The default
     * is {@code java} so that existing adapters keep working unchanged.
     */
    public Set<String> sourceExtensions() {
        return sourceExtensions;
    }

    /** @return {@code true} when the file name has one of {@link #sourceExtensions()}. */
    public boolean isSourceFile(Path file) {
        return isSourceFileName(file == null ? null : file.getFileName().toString());
    }

    /** @return {@code true} when the file name has one of {@link #sourceExtensions()}. */
    public boolean isSourceFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return sourceExtensions.contains(fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    /** Build descriptors that were used to detect this project, for example {@code pom.xml}. */
    public List<Path> detectedDescriptors() {
        return detectedDescriptors;
    }

    public boolean multiModule() {
        return modules.size() > 1;
    }

    /**
     * Combines the descriptions of every adapter that claimed part of a project.
     *
     * <p>A polyglot tree is not one project to the core: each adapter knows only its own language's
     * sources, tests and modules. The core, however, needs one description to scan for requirement
     * mappings and one to report, and both have to cover the whole tree or the languages left out become
     * invisible. The union is that description.
     *
     * <p>What is <em>not</em> merged is any claim about a single language: the language and build system
     * become {@link LanguageId#POLYGLOT} and {@link BuildSystemId#POLYGLOT} rather than naming whichever
     * adapter happened to be selected first. Only fields that describe the tree as a whole - source
     * extensions, source and test directories, modules, descriptors, attributes - are combined.
     *
     * @param project the project all the descriptions belong to
     * @param infos   one description per adapter; must not be empty
     * @return the combined description
     * @throws IllegalArgumentException when no description is given
     */
    public static ProjectInfo union(ProjectRef project, List<ProjectInfo> infos) {
        if (infos == null || infos.isEmpty()) {
            throw new IllegalArgumentException("at least one ProjectInfo is needed to describe a project");
        }
        if (infos.size() == 1) {
            return infos.getFirst();
        }
        ProjectInfo.Builder builder = builder(project)
                .language(LanguageId.POLYGLOT)
                .buildSystem(BuildSystemId.POLYGLOT)
                .buildRoot(project.root());
        Set<String> extensions = new LinkedHashSet<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        Set<String> descriptors = new LinkedHashSet<>();
        Map<String, ModuleInfo> modules = new LinkedHashMap<>();
        for (ProjectInfo info : infos) {
            extensions.addAll(info.sourceExtensions());
            info.testFrameworks().forEach(builder::addTestFramework);
            info.detectedDescriptors().forEach(descriptor -> {
                if (descriptors.add(descriptor.toString())) {
                    builder.addDetectedDescriptor(descriptor);
                }
            });
            info.attributes().forEach((key, value) -> attributes.putIfAbsent(key, value));
            // Modules are keyed by everything that identifies one, not just path and name: two adapters
            // legitimately describe the same directory as a module of their own language - the Node pair
            // always do - and collapsing those would drop one language's source and test directories from
            // the map the requirement mapper scans.
            info.modules().forEach(module -> modules.putIfAbsent(
                    module.path() + "|" + module.name() + "|" + module.sourceDirs() + "|" + module.testSourceDirs(),
                    module));
        }
        builder.sourceExtensions(extensions);
        builder.attributes(attributes);
        builder.modules(List.copyOf(modules.values()));
        return builder.build();
    }

    /** All main source directories across all modules. */
    public List<Path> sourceDirs() {
        List<Path> result = new ArrayList<>();
        for (ModuleInfo module : modules) {
            result.addAll(module.sourceDirs());
        }
        return List.copyOf(result);
    }

    /** All test source directories across all modules. */
    public List<Path> testSourceDirs() {
        List<Path> result = new ArrayList<>();
        for (ModuleInfo module : modules) {
            result.addAll(module.testSourceDirs());
        }
        return List.copyOf(result);
    }

    public boolean hasTests() {
        return modules.stream().anyMatch(ModuleInfo::hasTests);
    }

    public String describe() {
        String runtime = javaVersion != null ? "Java " + javaVersion
                : attributes.getOrDefault("runtimeVersion", null);
        return language + " project using " + buildSystem
                + (modules.size() > 1 ? " (" + modules.size() + " modules)" : "")
                + (runtime == null ? "" : ", " + runtime);
    }

    public static final class Builder {
        private final ProjectRef project;
        private LanguageId language = LanguageId.UNKNOWN;
        private BuildSystemId buildSystem = BuildSystemId.NONE;
        private Path buildRoot;
        private final List<ModuleInfo> modules = new ArrayList<>();
        private final Set<String> testFrameworks = new LinkedHashSet<>();
        private Path buildWrapper;
        private String javaVersion;
        private Set<String> sourceExtensions = Set.of("java");
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<Path> detectedDescriptors = new ArrayList<>();

        private Builder(ProjectRef project) {
            this.project = Objects.requireNonNull(project, "project");
            this.buildRoot = project.root();
        }

        public Builder language(LanguageId language) {
            this.language = Objects.requireNonNull(language);
            return this;
        }

        public Builder buildSystem(BuildSystemId buildSystem) {
            this.buildSystem = Objects.requireNonNull(buildSystem);
            return this;
        }

        public Builder buildRoot(Path buildRoot) {
            this.buildRoot = buildRoot;
            return this;
        }

        public Builder addModule(ModuleInfo module) {
            this.modules.add(Objects.requireNonNull(module));
            return this;
        }

        public Builder modules(List<ModuleInfo> modules) {
            this.modules.clear();
            this.modules.addAll(modules);
            return this;
        }

        public Builder addTestFramework(String framework) {
            this.testFrameworks.add(framework);
            return this;
        }

        public Builder buildWrapper(Path wrapper) {
            this.buildWrapper = wrapper;
            return this;
        }

        public Builder javaVersion(String javaVersion) {
            this.javaVersion = javaVersion;
            return this;
        }

        /** Source file extensions (without dot) used by core mapping and advisory phases. */
        public Builder sourceExtensions(Set<String> sourceExtensions) {
            this.sourceExtensions = sourceExtensions == null || sourceExtensions.isEmpty()
                    ? Set.of("java") : sourceExtensions;
            return this;
        }

        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder attributes(Map<String, String> values) {
            this.attributes.putAll(values);
            return this;
        }

        public Builder addDetectedDescriptor(Path descriptor) {
            this.detectedDescriptors.add(descriptor);
            return this;
        }

        public ProjectInfo build() {
            return new ProjectInfo(this);
        }
    }
}

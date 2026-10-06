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
        return language + " project using " + buildSystem
                + (modules.size() > 1 ? " (" + modules.size() + " modules)" : "")
                + (javaVersion == null ? "" : ", Java " + javaVersion);
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

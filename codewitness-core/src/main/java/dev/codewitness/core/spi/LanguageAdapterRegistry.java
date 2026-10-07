package dev.codewitness.core.spi;

import dev.codewitness.core.model.ProjectRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Registry of language adapters.
 *
 * <p>Selection is data driven: the engine asks every registered adapter whether it supports the
 * project and never contains a branch such as {@code if (language.equals("java"))}.
 */
public final class LanguageAdapterRegistry {

    private final Map<String, LanguageAdapter> adapters = new LinkedHashMap<>();

    public LanguageAdapterRegistry register(LanguageAdapter adapter) {
        adapters.put(adapter.id(), adapter);
        return this;
    }

    public List<LanguageAdapter> all() {
        return List.copyOf(adapters.values());
    }

    public Optional<LanguageAdapter> byId(String id) {
        return Optional.ofNullable(adapters.get(id));
    }

    /**
     * Selects an adapter for a project.
     *
     * @param project  project under verification
     * @param preferred adapter id requested by configuration, or {@code auto}/{@code null}
     * @return the first adapter that claims support, honouring the preference when it is usable
     */
    public Optional<LanguageAdapter> select(ProjectRef project, String preferred) {
        List<LanguageAdapter> selected = selectAll(project, preferred);
        return selected.isEmpty() ? Optional.empty() : Optional.of(selected.getFirst());
    }

    /**
     * Selects every adapter that claims part of a project.
     *
     * <p>A repository is not guaranteed to be written in one language, and a Java service with a
     * TypeScript frontend is two verifiable projects living in one tree. Selecting one adapter for the
     * whole tree would verify one side and leave the other silently absent from the report, which is a
     * claim about coverage that the run has not earned. Selecting all of them is what makes the report's
     * statement about a project's languages true.
     *
     * <p>A configured preference narrows the selection to that one adapter rather than being merged with
     * the automatic choice: the setting exists so a user can insist on how their tree is read.
     *
     * @param project   project under verification
     * @param preferred adapter id requested by configuration, or {@code auto}/{@code null}
     * @return every adapter that claims support, in registration order; empty when none does
     */
    public List<LanguageAdapter> selectAll(ProjectRef project, String preferred) {
        if (preferred != null && !preferred.isBlank() && !"auto".equalsIgnoreCase(preferred)) {
            LanguageAdapter requested = adapters.get(preferred);
            return requested != null && requested.supports(project) ? List.of(requested) : List.of();
        }
        List<LanguageAdapter> claimed = new ArrayList<>();
        for (LanguageAdapter adapter : adapters.values()) {
            if (adapter.supports(project)) {
                claimed.add(adapter);
            }
        }
        return List.copyOf(claimed);
    }

    /** Discovers adapters on the classpath through {@link ServiceLoader}. */
    public LanguageAdapterRegistry discover() {
        ServiceLoader<LanguageAdapter> loader = ServiceLoader.load(LanguageAdapter.class);
        for (LanguageAdapter adapter : loader) {
            register(adapter);
        }
        return this;
    }

    /** @return a registry containing only the explicitly registered adapters. */
    public static LanguageAdapterRegistry of(LanguageAdapter... adapters) {
        LanguageAdapterRegistry registry = new LanguageAdapterRegistry();
        for (LanguageAdapter adapter : adapters) {
            registry.register(adapter);
        }
        return registry;
    }

    public List<String> ids() {
        return new ArrayList<>(adapters.keySet());
    }
}

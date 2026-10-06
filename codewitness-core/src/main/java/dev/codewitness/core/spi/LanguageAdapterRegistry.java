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
        if (preferred != null && !preferred.isBlank() && !"auto".equalsIgnoreCase(preferred)) {
            LanguageAdapter requested = adapters.get(preferred);
            if (requested != null && requested.supports(project)) {
                return Optional.of(requested);
            }
        }
        for (LanguageAdapter adapter : adapters.values()) {
            if (adapter.supports(project)) {
                return Optional.of(adapter);
            }
        }
        return Optional.empty();
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

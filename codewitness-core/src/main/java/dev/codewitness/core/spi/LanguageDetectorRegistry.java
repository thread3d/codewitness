package dev.codewitness.core.spi;

import dev.codewitness.core.model.LanguageId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Registry of language detectors, the extension point for languages CodeWitness cannot verify.
 *
 * <p>Selection is data driven, exactly like {@link LanguageAdapterRegistry}: the engine asks every
 * registered detector what it can see and never contains a branch such as {@code if (language.equals("ruby"))}.
 */
public final class LanguageDetectorRegistry {

    private final Map<String, LanguageDetector> detectors = new LinkedHashMap<>();

    public LanguageDetectorRegistry register(LanguageDetector detector) {
        detectors.put(detector.id(), detector);
        return this;
    }

    public List<LanguageDetector> all() {
        return List.copyOf(detectors.values());
    }

    public List<String> ids() {
        return new ArrayList<>(detectors.keySet());
    }

    /** Discovers detectors on the classpath through {@link ServiceLoader}. */
    public LanguageDetectorRegistry discover() {
        ServiceLoader<LanguageDetector> loader = ServiceLoader.load(LanguageDetector.class);
        for (LanguageDetector detector : loader) {
            register(detector);
        }
        return this;
    }

    /** @return a registry containing only the explicitly registered detectors. */
    public static LanguageDetectorRegistry of(LanguageDetector... detectors) {
        LanguageDetectorRegistry registry = new LanguageDetectorRegistry();
        for (LanguageDetector detector : detectors) {
            registry.register(detector);
        }
        return registry;
    }

    /**
     * @param root    the project root that was verified
     * @param covered the languages the run's adapters reported
     * @return one detection per registered language that is present in the tree and was not verified, in
     *     registration order
     */
    public List<Detection> detect(Path root, Set<LanguageId> covered) {
        List<Detection> detections = new ArrayList<>();
        if (root == null) {
            return detections;
        }
        Set<LanguageId> languages = covered == null ? Set.of() : new LinkedHashSet<>(covered);
        for (LanguageDetector detector : detectors.values()) {
            if (detector.verifiedAsAdapterLanguage().isPresent()
                    && languages.contains(LanguageId.of(detector.verifiedAsAdapterLanguage().get()))) {
                // A language an adapter claimed is verified or explicitly blocked; reporting it again as
                // "present but unverifiable" would contradict the rest of the report.
                continue;
            }
            detector.evidence(root).ifPresent(evidence ->
                    detections.add(new Detection(detector, evidence)));
        }
        return List.copyOf(detections);
    }

    /**
     * One language found in a project that no adapter verified.
     *
     * @param detector the detector that recognised it
     * @param evidence the file that gives it away, relative to the project root
     */
    public record Detection(LanguageDetector detector, String evidence) {

        /** @return the language's id, usable as a {@link LanguageId}. */
        public LanguageId language() {
            return LanguageId.of(detector.id());
        }

        /** @return how the language is named in a report. */
        public String displayName() {
            return detector.language();
        }
    }
}

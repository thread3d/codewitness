package dev.codewitness.core.engine;

import dev.codewitness.core.spi.LanguageDetectorRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the languages a run could not verify into sentences for the report.
 *
 * <p>This is the second half of supporting a polyglot tree. Running every adapter that claims the project
 * fixes the case where CodeWitness <em>can</em> verify both sides; it does nothing for a tree that contains
 * a language no adapter covers at all. A Java service with a Ruby build script would still have every
 * verdict computed as though the tree were Java, and the report would say nothing about the Ruby.
 *
 * <p>Recognition itself belongs to {@link dev.codewitness.core.spi.LanguageDetector} implementations, which
 * are discovered like adapters are; this class only decides what to say about what they found. That split
 * is deliberate: the sentence below is the part that has to stay in step with the report's vocabulary,
 * while knowing that a {@code Gemfile} means Ruby is a fact about Ruby and belongs in a module that can be
 * replaced by a real adapter later.
 */
public final class UncoveredLanguages {

    private UncoveredLanguages() {
    }

    /**
     * @param detectors the registered detectors
     * @param root      the root of the tree that was actually verified
     * @param verified  the language ids the run's adapters reported
     * @return one sentence per language present in the tree that no adapter verified
     */
    public static List<String> limitations(
            LanguageDetectorRegistry detectors, Path root, Set<dev.codewitness.core.model.LanguageId> verified,
            List<String> verifiedAdapters) {
        List<LanguageDetectorRegistry.Detection> detections = detectors == null
                ? List.of() : detectors.detect(root, verified);
        return limitations(detections, verifiedAdapters);
    }

    /**
     * @param detections languages the run could not verify
     * @param verified   the adapters that did run, for the sentence that says what the report does cover
     * @return one sentence per uncovered language
     */
    public static List<String> limitations(
            List<LanguageDetectorRegistry.Detection> detections, List<String> verified) {
        List<String> limitations = new ArrayList<>();
        String covers = verified == null || verified.isEmpty()
                ? "no language" : String.join(", ", verified);
        for (LanguageDetectorRegistry.Detection detection : new LinkedHashSet<>(detections)) {
            limitations.add("This project also contains " + detection.displayName() + " code ("
                    + detection.evidence() + "), and no language adapter in this run understands it. Nothing "
                    + "written in " + detection.displayName() + " was built, tested or analysed, and the "
                    + "verdicts below cover only " + covers + ".");
        }
        return List.copyOf(limitations);
    }
}

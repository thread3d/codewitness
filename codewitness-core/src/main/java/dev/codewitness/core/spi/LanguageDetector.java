package dev.codewitness.core.spi;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Recognises a language in a project without being able to verify it.
 *
 * <p>{@link LanguageAdapter} is all-or-nothing: a language either has an adapter and is built, tested and
 * analysed, or it has none and the run cannot represent it at all. That second case is what this interface
 * exists for. A repository is often written mostly in a language CodeWitness supports and partly in one it
 * does not - a Ruby build script, a PHP admin page, a C# service - and a report that is silent about those
 * is making a claim about the whole tree that it has not earned.
 *
 * <p>A detector answers one question: is this language here, and what file gives it away? It never
 * schedules a phase, produces evidence or touches a verdict. Its only effect is that the language appears
 * in the report as present and not verified.
 *
 * <p>Detectors are discovered through {@link java.util.ServiceLoader}, exactly like adapters, so
 * recognising a new language is a module and a provider file rather than an edit to the core. When a
 * language later gains a real adapter, its detector becomes redundant: a language an adapter verified is
 * never reported through this path.
 */
public interface LanguageDetector {

    /** @return unique identifier for the language, for example {@code ruby}. */
    String id();

    /** @return how the language is named in a report, for example {@code Ruby}. */
    String language();

    /**
     * @return the id an adapter would report for this language, when one exists, so that a language already
     *     verified is not reported twice. Empty for a language nothing can verify, which is the usual case.
     */
    default Optional<String> verifiedAsAdapterLanguage() {
        return Optional.empty();
    }

    /**
     * @param root the project root to look in
     * @return the file that gives this language away, relative to {@code root}, or empty when the project
     *     does not contain it. The evidence is reported, so it should be the file a reader would open to
     *     check the claim.
     */
    Optional<String> evidence(Path root);
}

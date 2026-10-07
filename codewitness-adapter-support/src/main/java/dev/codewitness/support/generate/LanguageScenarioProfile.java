package dev.codewitness.support.generate;

import dev.codewitness.core.model.LanguageId;

/**
 * Everything {@link NodeScenarioTestGenerator} needs to know about one Node language.
 *
 * <p>Turning a declared scenario into an executable {@code node:test} module is the same job for
 * JavaScript and TypeScript: enumerate boundaries, search randomly under a seed, run the declared
 * steps concurrently when asked, and report a violation in the counterexample protocol. What differs
 * is the adapter's naming, the Node release its runtime resolution demands, and the dialect of the
 * generated source — TypeScript needs the type annotations its stripper expects, JavaScript must not
 * have any.
 *
 * <p>This is a profile of the adapter's naming and of the emitted dialect, not a strategy: an
 * implementation answers questions and holds no generation logic of its own.
 */
public interface LanguageScenarioProfile {

    /**
     * The stable id the adapter publishes for this generator.
     *
     * @return the SPI id, e.g. {@code "javascript-scenario-generator"}
     */
    String generatorId();

    /**
     * The language whose projects this generator writes tests for.
     *
     * @return the language id, e.g. {@link LanguageId#JAVASCRIPT}
     */
    LanguageId languageId();

    /**
     * The first Node release the emitted module is documented to run on.
     *
     * <p>It is written into the generated file's header, so it must be the version the adapter's own
     * runtime resolution requires rather than a copy of another adapter's.
     *
     * @return the version as the adapter reports it, e.g. {@code "18.17.0"}
     */
    String minimumNodeVersion();

    /**
     * Whether the emitted module declares the types of its inputs and helpers.
     *
     * <p>A JavaScript module must not: a type annotation there is a syntax error. A TypeScript module
     * must, because the file is compiled in the project's own strict configuration, where an
     * unannotated declaration would not type-check.
     *
     * @return {@code true} when the generated source is TypeScript
     */
    boolean typedAnnotations();
}

package dev.codewitness.support.adapter;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A language adapter whose phases are the providers it declares.
 *
 * <p>In fourteen adapters, {@code build}, {@code runTests} and {@code analyze} were one-line delegations to providers
 * that were also registered on the provider registry, and {@code capabilities()} was a second, hand-maintained list
 * of what those providers did. A subclass now supplies detection and its provider set, and gets three behaviours and a
 * capability list that cannot disagree with it.
 *
 * <p>Detection stays abstract because it is the only part that is genuinely per-language: how a tree is recognised,
 * which build system it uses and how a toolchain is probed differ in every adapter.
 *
 * <p>{@link #capabilities()} is derived from the providers, so {@code includeAnalysis=false} really does withdraw the
 * analysis capabilities rather than leaving an adapter claiming a phase that has nothing to run.
 */
public abstract class AbstractLanguageAdapter implements LanguageAdapter {

    private final AdapterProviders providers;

    /**
     * @param providers the tools this language uses, declared once
     */
    protected AbstractLanguageAdapter(AdapterProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    /** @return the providers this adapter declared. */
    protected final AdapterProviders providers() {
        return providers;
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return providers.build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return providers.runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return providers.analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        return providers.capabilities();
    }
}

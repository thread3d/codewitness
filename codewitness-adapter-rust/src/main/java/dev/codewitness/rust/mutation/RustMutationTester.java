package dev.codewitness.rust.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.rust.Cargo;
import dev.codewitness.rust.RustProjectDetector;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;
import dev.codewitness.support.mutation.TextMutator;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mutation testing for Rust using a bounded source mutator.
 *
 * <p>{@code cargo-mutants} is an optional tool, and a project should not lose mutation evidence just
 * because it is not installed. The adapter therefore mutates the source text itself through
 * {@link TextMutator}, recompiles and re-runs the project's own test suite for each mutant, and
 * reports the mutants the suite could not detect as survivors.
 *
 * <p>The run is bounded three ways: a fixed operator set, a mutant cap and a wall-clock budget. Every
 * file is restored in a {@code finally} block, so a crash cannot leave a mutated source tree behind.
 * A mutant that fails to compile is non-viable and is excluded from the score rather than counted as
 * killed. Surviving mutants never change a verdict: they are prominent evidence and a run
 * limitation, exactly like the Java adapter's PIT results.
 *
 * <p>The loop, its bounds and its restore guarantee live once in {@link CommandMutationTester}; this
 * class supplies only the Rust half through a nested profile - the manifest to look for, the sources,
 * the cargo invocation and the lexis.
 */
public final class RustMutationTester implements VerificationTools.MutationTester {

    private final CommandMutationTester delegate = new CommandMutationTester(new RustMutationProfile());

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        return delegate.analyse(context);
    }

    /** The Rust half of the shared mutation loop: its manifest, its sources, its cargo and its lexis. */
    private static final class RustMutationProfile implements CommandMutationProfile {

        @Override
        public String languageName() {
            return "Rust";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.RUST;
        }

        @Override
        public String mutationTesterId() {
            return "rust-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "rust-text";
        }

        @Override
        public LanguageLexis lexis() {
            return RustLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            return Cargo.hasManifest(context)
                    ? null : "no Cargo.toml was found, so mutants cannot be compiled";
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return RustProjectDetector.rustFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
            String name = normalized.contains("/")
                    ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            return normalized.startsWith("tests/") || normalized.contains("/tests/") || name.endsWith("_test.rs");
        }

        @Override
        public List<String> testCommand(ProjectInfo project, VerificationContext context) {
            return List.of(Cargo.cargo(project, context), "test", "--no-fail-fast", "--quiet");
        }

        @Override
        public Map<String, String> environment(VerificationContext context) {
            return Cargo.environment(context);
        }

        @Override
        public String nonViableReason(String output) {
            // cargo prints one "test result:" line per test binary it ran; without one, the mutant never
            // got as far as being tested.
            return output.contains("test result:") ? null : "cargo reported no test result";
        }

        @Override
        public String operatorSetDescription() {
            return "The operator set is deliberately small (arithmetic, comparison boundaries, boolean "
                    + "operators and boolean constants), and only operators surrounded by whitespace "
                    + "are considered.";
        }
    }
}

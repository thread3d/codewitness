package dev.codewitness.support.mutation;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared build-and-test mutation loop's contract at its edges.
 *
 * <p>{@link CommandMutationTester} is the one place where a mutation run decides whether it produced a
 * measurement at all. The rule it has to keep is the project's rule for every unmeasured phase: say
 * "not measured" and why, never report zero survivors as though the tests had been shown to be strong.
 * A project whose manifest is missing, or which offers no source to mutate, has measured nothing - and
 * a silent {@code 0 surviving mutants} in that situation would read as the strongest possible result.
 *
 * <p>These cases deliberately stop before any process is started: they pin the decisions the loop makes
 * before it touches the project, which need no toolchain.
 */
class CommandMutationTesterTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the tester reports the profile's id and claims the profile's language")
    void delegatesIdentityToTheProfile() {
        CommandMutationTester tester = new CommandMutationTester(profile("Rust", LanguageId.RUST, null));

        assertEquals("test-command-mutation", tester.id(),
                "the provider id is the adapter's published identifier, not a shared constant");
        assertTrue(tester.supports(ProjectInfo.builder(ProjectRef.of(tmp))
                .language(LanguageId.RUST).build()));
        assertFalse(tester.supports(ProjectInfo.builder(ProjectRef.of(tmp))
                .language(LanguageId.GO).build()),
                "a profile for one language must not claim the other");
    }

    @Test
    @DisplayName("a missing manifest means not measured, and the reason names the file that was missing")
    void missingManifestIsNotMeasured() {
        CommandMutationTester tester = new CommandMutationTester(profile("Rust", LanguageId.RUST,
                "no Cargo.toml was found, so mutants cannot be compiled"));

        MutationReport report = tester.analyse(context(LanguageId.RUST));

        assertFalse(report.measured(), "nothing was mutated, so nothing was measured");
        assertTrue(report.note().contains("Cargo.toml"),
                "the reason must name what was missing: " + report.note());
    }

    @Test
    @DisplayName("no source to mutate means not measured, and the reason names the language")
    void noSourceFilesIsNotMeasured() {
        // The profile finds the manifest but no sources, which is the shape of a project whose sources
        // the adapter cannot see even though it can build it.
        CommandMutationTester tester = new CommandMutationTester(profile("Go", LanguageId.GO, null));

        MutationReport report = tester.analyse(context(LanguageId.GO));

        assertFalse(report.measured());
        assertTrue(report.note().contains("Go"),
                "the message reads as the adapter's own, so it must name that language: " + report.note());
        assertTrue(report.note().contains("source file"), report.note());
    }

    // ------------------------------------------------------------------ fixture

    private VerificationContext context(LanguageId language) {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(tmp)).language(language).build();
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(ProjectRef.of(tmp))
                .projectInfo(project)
                .config(VerificationConfig.defaults())
                .workspace(new WorkspaceManager.Workspace(tmp, WorkspaceManager.Mode.IN_PLACE, "test"))
                .artifacts(new ArtifactStore(tmp.resolve("artifacts")))
                .budget(new BudgetUsage.Tracker(Budget.defaults()))
                .sandbox(new LocalProcessSandbox(List.of(tmp)))
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }

    /**
     * @param manifestProblem what the profile reports about the project's manifest, or {@code null} when
     *     it found one
     */
    private CommandMutationProfile profile(String languageName, LanguageId languageId, String manifestProblem) {
        return new CommandMutationProfile() {
            @Override
            public String languageName() {
                return languageName;
            }

            @Override
            public LanguageId languageId() {
                return languageId;
            }

            @Override
            public String mutationTesterId() {
                return "test-command-mutation";
            }

            @Override
            public String mutationReportToolId() {
                return "test-text";
            }

            @Override
            public LanguageLexis lexis() {
                return ScriptLexis.INSTANCE;
            }

            @Override
            public String manifestProblem(VerificationContext context) {
                return manifestProblem;
            }

            @Override
            public List<Path> sourceFiles(Path sourceRoot) {
                return List.of();
            }

            @Override
            public boolean isTestPath(String relative) {
                return false;
            }

            @Override
            public List<String> testCommand(ProjectInfo project, VerificationContext context) {
                return List.of("true");
            }

            @Override
            public Map<String, String> environment(VerificationContext context) {
                return Map.of();
            }

            @Override
            public String nonViableReason(String output) {
                return null;
            }

            @Override
            public String operatorSetDescription() {
                return "The operator set is deliberately small.";
            }
        };
    }
}

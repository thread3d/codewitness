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
 * The shared mutation loop's contract at its edges.
 *
 * <p>{@link TextMutationTester} is the one place where a mutation run decides whether it produced a
 * measurement at all. The rule it has to keep is the project's rule for every unmeasured phase: say
 * "not measured" and why, never report zero survivors as though the tests had been shown to be strong.
 * A run that cannot find the tests, or cannot find any source to mutate, has measured nothing - and a
 * silent {@code 0 surviving mutants} in that situation would read as the strongest possible result.
 *
 * <p>These cases deliberately stop before any process is started: they pin the decisions the loop makes
 * before it touches the project, which need no sandbox and no toolchain.
 */
class TextMutationTesterTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the tester reports the profile's id and claims the profile's language")
    void delegatesIdentityToTheProfile() {
        TextMutationTester tester = new TextMutationTester(profile("JavaScript", LanguageId.JAVASCRIPT));

        assertEquals("test-mutation", tester.id(),
                "the provider id is the adapter's published identifier, not a shared constant");
        assertTrue(tester.supports(ProjectInfo.builder(ProjectRef.of(tmp))
                .language(LanguageId.JAVASCRIPT).build()));
        assertFalse(tester.supports(ProjectInfo.builder(ProjectRef.of(tmp))
                .language(LanguageId.TYPESCRIPT).build()),
                "a profile for one language must not claim the other");
    }

    @Test
    @DisplayName("no runnable test file means not measured, not zero surviving mutants")
    void noTestFilesIsNotMeasured() {
        MutationReport report = new TextMutationTester(profile("JavaScript", LanguageId.JAVASCRIPT))
                .analyse(context());

        assertFalse(report.measured(), "nothing was mutated, so nothing was measured");
        assertTrue(report.note().toLowerCase(java.util.Locale.ROOT).contains("test file"),
                "the reason must name what was missing: " + report.note());
    }

    @Test
    @DisplayName("no source to mutate means not measured, and the reason names the language")
    void noSourceFilesIsNotMeasured() {
        // The profile offers tests but no sources, which is the shape of a project whose sources the
        // adapter cannot see even though its tests are runnable.
        TextMutationTester tester = new TextMutationTester(profileWithTests(
                "TypeScript", LanguageId.TYPESCRIPT));

        MutationReport report = tester.analyse(context());

        assertFalse(report.measured());
        assertTrue(report.note().contains("TypeScript"),
                "the message reads as the adapter's own, so it must name that language: " + report.note());
        assertTrue(report.note().contains("source file"), report.note());
    }

    // ------------------------------------------------------------------ fixture

    private VerificationContext context() {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(tmp)).language(LanguageId.JAVASCRIPT).build();
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(ProjectRef.of(tmp))
                .projectInfo(project)
                .config(VerificationConfig.defaults())
                .workspace(new WorkspaceManager.Workspace(tmp, WorkspaceManager.Mode.IN_PLACE, "test"))
                .artifacts(new ArtifactStore(tmp.resolve("artifacts")))
                .budget(new dev.codewitness.core.result.BudgetUsage.Tracker(
                        dev.codewitness.core.result.Budget.defaults()))
                .sandbox(new LocalProcessSandbox(List.of(tmp)))
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }

    private LanguageMutationProfile profile(String languageName, LanguageId languageId) {
        return profile(languageName, languageId, false);
    }

    private LanguageMutationProfile profileWithTests(String languageName, LanguageId languageId) {
        return profile(languageName, languageId, true);
    }

    /**
     * @param withTests when {@code true} the profile offers a runnable test file, so the tester gets past
     *     its first check and reports the missing source instead
     */
    private LanguageMutationProfile profile(String languageName, LanguageId languageId, boolean withTests) {
        return new LanguageMutationProfile() {
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
                return "test-mutation";
            }

            @Override
            public String mutationReportToolId() {
                return "test-text";
            }

            @Override
            public String excludedSourceSuffix() {
                return ".jsx";
            }

            @Override
            public List<Path> testFiles(VerificationContext context) {
                return withTests ? List.of(tmp.resolve("ledger.test.js")) : List.of();
            }

            @Override
            public List<Path> sourceFiles(Path sourceRoot) {
                return List.of();
            }

            @Override
            public String nodeExecutable(ProjectInfo project, VerificationContext context) {
                return "node";
            }

            @Override
            public Map<String, String> nodeEnvironment(VerificationContext context) {
                return Map.of();
            }

            @Override
            public Path buildCheckScript(VerificationContext context) {
                return tmp.resolve("check.mjs");
            }
        };
    }
}

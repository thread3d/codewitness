package dev.codewitness.support;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The security phase of an adapter whose rules live in its heuristic analyzer. Fourteen adapters had the same
 * three methods, so this pins what they meant: the id it reports, the one language it claims, and that it hands
 * back exactly what the analysis produced.
 */
class DelegatingSecurityAnalyzerTest {

    private static final Finding FINDING = new Finding("heuristic", "CW-TEST-001", Severity.HIGH,
            FindingCategory.SECURITY, "a finding", "src/A.kt", 1, "app", java.util.Map.of());

    @Test
    @DisplayName("reports the id it was given, and claims only its own language")
    void reportsItsIdAndLanguage(@TempDir Path tmp) {
        DelegatingSecurityAnalyzer analyzer =
                new DelegatingSecurityAnalyzer("swift-security", LanguageId.SWIFT, context -> List.of());

        assertEquals("swift-security", analyzer.id());
        assertTrue(analyzer.supports(project(tmp, LanguageId.SWIFT)));
        assertFalse(analyzer.supports(project(tmp, LanguageId.KOTLIN)), "a provider claims one language");
        assertFalse(analyzer.supports(null), "a missing project is not a project it supports");
    }

    @Test
    @DisplayName("returns exactly what the analysis returned")
    void returnsWhatTheAnalysisReturned(@TempDir Path tmp) {
        DelegatingSecurityAnalyzer analyzer =
                new DelegatingSecurityAnalyzer("test-security", LanguageId.RUST, context -> List.of(FINDING));

        assertEquals(List.of(FINDING), analyzer.analyze(null));
    }

    @Test
    @DisplayName("an analysis that returns null is an empty list, not a null in the report")
    void aNullResultBecomesEmpty() {
        // A provider that forgets to return a list must not put a null where the engine expects findings: the
        // engine's own contract is that it never sees one.
        DelegatingSecurityAnalyzer analyzer =
                new DelegatingSecurityAnalyzer("test-security", LanguageId.GO, context -> null);

        assertEquals(List.of(), analyzer.analyze(null));
    }

    private static ProjectInfo project(Path root, LanguageId language) {
        return ProjectInfo.builder(new ProjectRef(root, "example", "example@1"))
                .language(language)
                .build();
    }
}

package dev.codewitness.swift.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.swift.SwiftTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic source heuristics for Swift.
 *
 * <p>Every rule reads a projection of the source produced by {@link SwiftSourceScan}, so the tests are
 * about two things: the rule table fires on the real Swift shapes it describes, and it stays silent on
 * code that carries none of those shapes. The security analyzer must return the security subset and
 * nothing else, because a rule counted under two phases would be reported twice.
 */
class SwiftHeuristicAnalyzerTest {

    /** The rule ids the hazards in {@link #hazardousContext} are written to trigger. */
    private static final Set<String> EXPECTED_RULES = Set.of(
            "CW-SWIFT-SEC-001", "CW-SWIFT-SEC-002", "CW-SWIFT-SEC-003", "CW-SWIFT-SEC-004",
            "CW-SWIFT-SEC-005", "CW-SWIFT-SEC-006",
            "CW-SWIFT-CORRECT-001", "CW-SWIFT-REL-001", "CW-SWIFT-REL-002", "CW-SWIFT-STYLE-001",
            "CW-SWIFT-BUG-001", "CW-SWIFT-BUG-002", "CW-SWIFT-MAIN-001", "CW-SWIFT-TEST-001");

    @Test
    @DisplayName("at least six rules fire on realistic Swift, and each is reported at its own line")
    void rulesFireOnRealisticSwift(@TempDir Path root, @TempDir Path run) throws IOException {
        VerificationContext context = hazardousContext(root, run);

        List<Finding> findings = new SwiftHeuristicAnalyzer().findAll(context);
        Set<String> ids = ruleIds(findings);

        assertTrue(ids.size() >= 6, "at least six rules must be exercised: " + ids);
        assertTrue(ids.containsAll(EXPECTED_RULES),
                "the hazards are written to trigger every rule; missing: " + missing(ids));
        assertTrue(findings.stream().allMatch(finding -> finding.line() > 0),
                "every finding must carry the line it was observed on: " + findings);
        assertTrue(findings.stream().anyMatch(finding -> finding.file().endsWith("main.swift")
                        && "CW-SWIFT-MAIN-001".equals(finding.ruleId())),
                "a main.swift in a library package is the file the entry-point rule is about: " + findings);
    }

    @Test
    @DisplayName("clean Swift produces no finding at all")
    void cleanCodeProducesNothing(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.write(root, "Package.swift", """
                // swift-tools-version:5.9
                import PackageDescription
                let package = Package(
                    name: "Ledger",
                    targets: [.target(name: "Ledger"), .testTarget(name: "LedgerTests")]
                )
                """);
        SwiftTestContexts.write(root, "Sources/Ledger/Ledger.swift", """
                public struct Ledger {
                    public func total(_ values: [Int]) -> Int {
                        var sum = 0
                        for value in values {
                            sum = sum + value
                        }
                        return sum
                    }
                }
                """);
        SwiftTestContexts.write(root, "Tests/LedgerTests/LedgerTests.swift", """
                import XCTest
                final class LedgerTests: XCTestCase {
                    func testTotal() {
                        XCTAssertEqual(Ledger().total([1, 2]), 3)
                    }
                }
                """);
        VerificationContext context = SwiftTestContexts.context(root, run);

        List<Finding> findings = new SwiftHeuristicAnalyzer().findAll(context);

        assertTrue(findings.isEmpty(), "nothing here matches any rule: " + findings);
    }

    @Test
    @DisplayName("the static analyzer reports the non-security rules only; the security phase reports the rest")
    void theTwoPhasesSplitTheRuleTable(@TempDir Path root, @TempDir Path run) throws IOException {
        VerificationContext context = hazardousContext(root, run);
        SwiftHeuristicAnalyzer heuristics = new SwiftHeuristicAnalyzer();

        List<Finding> analysis = heuristics.analyze(context);
        List<Finding> security = heuristics.analyzeSecurity(context);

        assertFalse(analysis.isEmpty());
        assertTrue(analysis.stream().noneMatch(finding -> finding.ruleId().startsWith("CW-SWIFT-SEC")),
                "a security rule counted under static analysis would be reported twice: " + analysis);
        assertFalse(security.isEmpty());
        assertTrue(security.stream().allMatch(finding -> finding.ruleId().startsWith("CW-SWIFT-SEC-")),
                "the security phase is the security rules and nothing else: " + security);
        assertTrue(severityOfAll(security), security.toString());
    }

    @Test
    @DisplayName("the security analyzer returns only CW-SWIFT-SEC-* findings and names itself")
    void theSecurityAnalyzerReturnsOnlySecurityRules(@TempDir Path root, @TempDir Path run)
            throws IOException {
        VerificationContext context = hazardousContext(root, run);
        SwiftSecurityAnalyzer analyzer = new SwiftSecurityAnalyzer();

        assertEquals("swift-security", analyzer.id());
        assertTrue(analyzer.supports(context.projectInfo()));

        List<Finding> findings = analyzer.analyze(context);

        assertFalse(findings.isEmpty(), "the hazards include a force unwrap, try! and a credential");
        assertTrue(findings.stream().allMatch(finding -> finding.ruleId().startsWith("CW-SWIFT-SEC-")),
                findings.toString());

        ProjectInfo javaProject = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .build();
        assertFalse(analyzer.supports(javaProject),
                "a Java project's security scan belongs to the Java adapter");
    }

    @Test
    @DisplayName("the rule table is documented through ruleIds and severityOf")
    void theRuleTableIsDocumented() {
        List<String> ids = SwiftHeuristicAnalyzer.ruleIds();

        assertEquals(14, ids.size(), "the fourteen documented rules: " + ids);
        assertTrue(ids.containsAll(EXPECTED_RULES), ids.toString());
        assertEquals(Severity.HIGH, SwiftHeuristicAnalyzer.severityOf("CW-SWIFT-SEC-001"));
        assertEquals(Severity.MEDIUM, SwiftHeuristicAnalyzer.severityOf("CW-SWIFT-BUG-001"));
        assertEquals(Severity.MEDIUM, SwiftHeuristicAnalyzer.severityOf("CW-SWIFT-NOT-A-RULE"),
                "an unknown rule still has to answer with a severity rather than throw");
        assertTrue(SwiftHeuristicAnalyzer.isSecurityRule("CW-SWIFT-SEC-002"));
        assertFalse(SwiftHeuristicAnalyzer.isSecurityRule("CW-SWIFT-CORRECT-001"));
        assertFalse(SwiftHeuristicAnalyzer.isSecurityRule(null));
    }

    @Test
    @DisplayName("a commented-out hazard is invisible, and a TODO inside a string is not unfinished work")
    void commentsAndStringsAreNotMistakenForCode(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithoutTests(root);
        SwiftTestContexts.write(root, "Sources/Ledger/Ledger.swift", """
                // let name = user!
                // try! load()
                // let password = "hunter2secret"
                let note = "TODO: this is text, not a marker"
                let other = "try! load()"
                """);
        VerificationContext context = SwiftTestContexts.context(root, run);

        List<Finding> findings = new SwiftHeuristicAnalyzer().findAll(context);

        assertTrue(findings.isEmpty(),
                "a commented-out hazard and a string that merely spells one are not code: " + findings);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @return a context whose package carries one hazard per rule
     *
     * <p>The manifest declares no executable target, so the library-only rules apply, and the test target
     * holds a method that asserts nothing.
     */
    private static VerificationContext hazardousContext(Path root, Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        SwiftTestContexts.write(root, "Sources/Ledger/Ledger.swift", """
                import Foundation

                public struct Ledger {
                    public func report(user: User?, amount: Double) -> String {
                        let name = user!.name
                        let data = try! load(name)
                        let text = data as! String
                        let now = Date()
                        let password = "hunter2secret"
                        UserDefaults.standard.set("s3cr3t", forKey: "api_key")
                        if amount == 0.1 { }
                        try? save()
                        do { try save() } catch {}
                        fatalError("boom")
                        print(text)
                        return text
                    }
                }
                // TODO: finish this
                """);
        SwiftTestContexts.write(root, "Sources/Ledger/main.swift", "Ledger().run()\n");
        SwiftTestContexts.write(root, "Tests/LedgerTests/LedgerTests.swift", """
                import XCTest
                final class LedgerTests: XCTestCase {
                    func testNothing() {
                        let value = 1
                        _ = value
                    }
                }
                """);
        return SwiftTestContexts.context(root, run);
    }

    private static Set<String> ruleIds(List<Finding> findings) {
        Set<String> ids = new LinkedHashSet<>();
        for (Finding finding : findings) {
            ids.add(finding.ruleId());
        }
        return ids;
    }

    private static Set<String> missing(Set<String> found) {
        Set<String> missing = new LinkedHashSet<>(EXPECTED_RULES);
        missing.removeAll(found);
        return missing;
    }

    private static boolean severityOfAll(List<Finding> findings) {
        return findings.stream().allMatch(finding ->
                finding.severity() == SwiftHeuristicAnalyzer.severityOf(finding.ruleId()));
    }
}

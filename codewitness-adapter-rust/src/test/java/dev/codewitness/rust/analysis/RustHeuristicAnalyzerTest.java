package dev.codewitness.rust.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.rust.RustTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deterministic rule table: what it fires on, and what it deliberately ignores. */
class RustHeuristicAnalyzerTest {

    private static List<String> ruleIds(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).toList();
    }

    @Test
    @DisplayName("correctness heuristics fire on their patterns and not on comments")
    void reportsCorrectnessRules(@TempDir Path root, @TempDir Path run) throws IOException {
        RustTestContexts.write(root, "src/lib.rs", """
                // This comment mentions .unwrap() and panic! but is not code.
                pub fn value(input: Option<i64>) -> i64 {
                    let total: f64 = 1.5;
                    if input.is_none() {
                        todo!("finish this");
                    }
                    input.unwrap()
                }

                pub fn loud(input: i64) -> i64 {
                    if input < 0 {
                        panic!("negative");
                    }
                    input.expect_call()
                }
                """);
        VerificationContext context = RustTestContexts.context(root, run);

        List<String> ids = ruleIds(new RustHeuristicAnalyzer().analyze(context));

        assertTrue(ids.contains("CW-RS-REL-001"), "unwrap must be reported: " + ids);
        assertTrue(ids.contains("CW-RS-REL-003"), "panic! must be reported: " + ids);
        assertTrue(ids.contains("CW-RS-REL-004"), "todo! must be reported: " + ids);
        assertTrue(ids.contains("CW-RS-NUM-001"), "a monetary f64 must be reported: " + ids);
        // The comment is the only source of a second unwrap/panic!, and must not produce findings.
        assertEquals(1, ids.stream().filter("CW-RS-REL-001"::equals).count(), ids.toString());
        assertEquals(1, ids.stream().filter("CW-RS-REL-003"::equals).count(), ids.toString());
    }

    @Test
    @DisplayName("security rules are separated from the static analysis phase")
    void separatesSecurityRules(@TempDir Path root, @TempDir Path run) throws IOException {
        RustTestContexts.write(root, "src/lib.rs", """
                pub fn run() {
                    let password = "hunter2";
                    unsafe {
                        std::ptr::null::<u8>();
                    }
                    let _ = password;
                }
                """);
        VerificationContext context = RustTestContexts.context(root, run);
        RustHeuristicAnalyzer analyzer = new RustHeuristicAnalyzer();

        List<String> correctness = ruleIds(analyzer.analyze(context));
        List<String> security = ruleIds(analyzer.analyzeSecurity(context));

        assertTrue(security.contains("CW-RS-SEC-001"), security.toString());
        assertTrue(security.contains("CW-RS-SEC-002"), security.toString());
        assertFalse(correctness.contains("CW-RS-SEC-001"), correctness.toString());
        assertFalse(correctness.contains("CW-RS-SEC-002"), correctness.toString());
    }

    @Test
    @DisplayName("a test with no assertion is reported as a test that cannot fail")
    void reportsTestsWithoutAssertions(@TempDir Path root, @TempDir Path run) throws IOException {
        RustTestContexts.write(root, "src/lib.rs", "pub fn value() -> i64 { 1 }\n");
        RustTestContexts.write(root, "tests/ledger.rs", """
                #[test]
                fn does_nothing() {
                    let value = 1 + 1;
                }

                #[test]
                fn checks_something() {
                    assert_eq!(2, 1 + 1);
                }
                """);
        VerificationContext context = RustTestContexts.context(root, run);

        List<Finding> findings = new RustHeuristicAnalyzer().analyzeTests(context);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals("CW-RS-TEST-001", findings.getFirst().ruleId());
        assertTrue(findings.getFirst().message().contains("does_nothing"));
    }

    @Test
    @DisplayName("a block comment is stripped across lines so its contents cannot fire a rule")
    void stripsBlockComments() {
        boolean[] inBlock = {false};
        String stripped = RustHeuristicAnalyzer.codeOnly("let x = 1; /* .unwrap() */", inBlock);
        assertTrue(stripped.startsWith("let x = 1;"), stripped);
        assertFalse(stripped.contains("unwrap"), stripped);
        assertFalse(inBlock[0]);
        boolean[] open = {false};
        RustHeuristicAnalyzer.codeOnly("/* start", open);
        assertTrue(open[0]);
        assertFalse(RustHeuristicAnalyzer.codeOnly("   .unwrap() */ code", open).contains("unwrap"));
    }

    @Test
    @DisplayName("the rule table is self-describing")
    void exposesRuleIds() {
        List<String> ids = RustHeuristicAnalyzer.ruleIds();

        assertTrue(ids.contains("CW-RS-SEC-002"));
        assertTrue(ids.contains("CW-RS-TEST-001"));
        assertEquals(ids.size(), ids.stream().distinct().count(), "rule ids must be unique");
    }
}

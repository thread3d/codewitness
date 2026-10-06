package dev.codewitness.go.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.go.GoTestContexts;
import dev.codewitness.go.GoTool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deterministic rule table: what it fires on, and what it deliberately ignores. */
class GoHeuristicAnalyzerTest {

    private static List<String> ruleIds(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).toList();
    }

    @Test
    @DisplayName("the rule table is self-describing")
    void exposesRuleIds() {
        List<String> ids = GoHeuristicAnalyzer.ruleIds();

        assertTrue(ids.contains("CW-GO-SEC-001"), ids.toString());
        assertTrue(ids.contains("CW-GO-SEC-009"), ids.toString());
        assertTrue(ids.contains("CW-GO-REL-002"), ids.toString());
        assertTrue(ids.contains("CW-GO-BUG-001"), ids.toString());
        assertTrue(ids.contains("CW-GO-TEST-001"), ids.toString());
        assertTrue(ids.contains("CW-GO-VET-001"), ids.toString());
        assertEquals(ids.size(), ids.stream().distinct().count(), "rule ids must be unique");
    }

    @Test
    @DisplayName("each rule keeps its own severity, with MEDIUM as the documented default")
    void exposesSeverities() {
        assertEquals(Severity.CRITICAL, GoHeuristicAnalyzer.severityOf("CW-GO-SEC-001"));
        assertEquals(Severity.HIGH, GoHeuristicAnalyzer.severityOf("CW-GO-REL-003"));
        assertEquals(Severity.LOW, GoHeuristicAnalyzer.severityOf("CW-GO-REL-005"));
        assertEquals(Severity.MEDIUM, GoHeuristicAnalyzer.severityOf("CW-GO-VET-001"));
        // An unknown rule is not silently reported as the most severe one.
        assertEquals(Severity.MEDIUM, GoHeuristicAnalyzer.severityOf("CW-GO-UNKNOWN-999"));
    }

    @Test
    @DisplayName("correctness heuristics fire on their patterns and the static phase excludes security")
    void reportsCorrectnessRules(@TempDir Path root, @TempDir Path run) throws IOException {
        GoTestContexts.write(root, "ledger/ledger.go", """
                package ledger

                import (
                	"errors"
                	"io"
                	"log"
                	"time"
                )

                // This comment mentions panic( and exec.Command("ls") but is not code.
                func post(amount float64, err error) int64 {
                	if err == io.EOF {
                		log.Fatal("cannot continue")
                	}
                	if amount == 1.5 {
                		panic("impossible amount")
                	}
                	failed := errors.New("ledger is closed")
                	_ = validate(failed)
                	atMillis := time.Now().UnixMilli()
                	days := atMillis / 86_400_000
                	return int(float64(days))
                }
                """);
        VerificationContext context = GoTestContexts.context(root, run);

        GoHeuristicAnalyzer analyzer = new GoHeuristicAnalyzer();
        List<String> ids = ruleIds(analyzer.analyze(context));

        assertTrue(ids.contains("CW-GO-REL-001"), "a discarded result must be reported: " + ids);
        assertTrue(ids.contains("CW-GO-REL-002"), "panic must be reported: " + ids);
        assertTrue(ids.contains("CW-GO-REL-003"), "log.Fatal must be reported: " + ids);
        assertTrue(ids.contains("CW-GO-REL-004"), "err == x must be reported: " + ids);
        assertTrue(ids.contains("CW-GO-ERR-001"), "errors.New must be reported: " + ids);
        assertTrue(ids.contains("CW-GO-DATE-001"), "a rolling-day constant must be reported: " + ids);
        assertTrue(ids.contains("CW-GO-NUM-001"), "a monetary float64 must be reported: " + ids);
        assertTrue(ids.contains("CW-GO-NUM-002"), "float equality must be reported: " + ids);
        assertTrue(ids.contains("CW-GO-BUG-001"), "a float-to-int cast must be reported: " + ids);
        assertTrue(ids.stream().noneMatch(GoHeuristicAnalyzer::isSecurityRule),
                "the static analysis phase must not report security rules: " + ids);

        // The comment is the only source of a second panic(, and it must not produce a finding.
        assertEquals(1, ids.stream().filter("CW-GO-REL-002"::equals).count(), ids.toString());
        Finding panicFinding = analyzer.analyze(context).stream()
                .filter(finding -> "CW-GO-REL-002".equals(finding.ruleId())).findFirst().orElseThrow();
        assertEquals("ledger/ledger.go", panicFinding.file());
        assertEquals(16, panicFinding.line());
        assertTrue(panicFinding.attributes().get("snippet").contains("panic(\"impossible amount\")"),
                panicFinding.attributes().toString());
    }

    @Test
    @DisplayName("security rules are separated from the static analysis phase")
    void separatesSecurityRules(@TempDir Path root, @TempDir Path run) throws IOException {
        GoTestContexts.write(root, "svc/danger.go", """
                package svc

                import (
                	"crypto/md5"
                	"fmt"
                	"net/http"
                	"os"
                	"os/exec"
                )

                func danger(user string) string {
                	cmd := exec.Command("ls")
                	digest := md5.New()
                	os.Chmod("/tmp/report", 0777)
                	query := fmt.Sprintf("SELECT * FROM users WHERE name = '%s'", user)
                	_ = &tls.Config{InsecureSkipVerify: true}
                	http.ListenAndServe(":8080", nil)
                	return query + cmd.Path + digest.Size()
                }
                """);
        VerificationContext context = GoTestContexts.context(root, run);

        GoHeuristicAnalyzer analyzer = new GoHeuristicAnalyzer();
        List<String> correctness = ruleIds(analyzer.analyze(context));
        List<String> security = ruleIds(analyzer.analyzeSecurity(context));

        assertTrue(security.contains("CW-GO-SEC-002"), "exec.Command must be reported: " + security);
        assertTrue(security.contains("CW-GO-SEC-004"), "md5.New must be reported: " + security);
        assertTrue(security.contains("CW-GO-SEC-005"), "InsecureSkipVerify must be reported: " + security);
        assertTrue(security.contains("CW-GO-SEC-006"), "formatted SQL must be reported: " + security);
        assertTrue(security.contains("CW-GO-SEC-008"), "0777 must be reported: " + security);
        assertTrue(security.contains("CW-GO-SEC-009"), "ListenAndServe must be reported: " + security);
        assertTrue(correctness.stream().noneMatch(GoHeuristicAnalyzer::isSecurityRule),
                "the static phase must not report security rules: " + correctness);
        assertTrue(correctness.isEmpty(), "this file holds no correctness hazard: " + correctness);
    }

    @Test
    @DisplayName("a rule does not fire inside a comment or inside a string literal")
    void ignoresCommentsAndStringLiterals(@TempDir Path root, @TempDir Path run) throws IOException {
        GoTestContexts.write(root, "svc/quiet.go", """
                package svc

                // panic("in a comment") and exec.Command("ls") must never fire.

                func quiet() string {
                	first := "call panic(1) now"
                	second := `exec.Command("ls") must not fire`
                	return first + second
                }
                """);
        VerificationContext context = GoTestContexts.context(root, run);

        GoHeuristicAnalyzer analyzer = new GoHeuristicAnalyzer();

        List<String> correctness = ruleIds(analyzer.analyze(context));
        List<String> security = ruleIds(analyzer.analyzeSecurity(context));

        assertFalse(correctness.contains("CW-GO-REL-002"), correctness.toString());
        assertTrue(security.isEmpty(), "a hazard inside a string is text, not code: " + security);
        assertTrue(correctness.isEmpty(), correctness.toString());
    }

    @Test
    @DisplayName("a suppression directive is the one rule that reports a comment")
    void reportsSuppressionDirectives(@TempDir Path root, @TempDir Path run) throws IOException {
        GoTestContexts.write(root, "svc/directive.go", """
                package svc

                func value() int {
                	return 1 //nolint:staticcheck
                }
                """);
        VerificationContext context = GoTestContexts.context(root, run);

        List<Finding> findings = new GoHeuristicAnalyzer().analyze(context);

        assertEquals(List.of("CW-GO-REL-005"), ruleIds(findings), findings.toString());
    }

    @Test
    @DisplayName("a test with no assertion is reported as a test that cannot fail")
    void reportsTestsWithoutAssertions(@TempDir Path root, @TempDir Path run) throws IOException {
        GoTestContexts.write(root, "ledger/ledger.go", "package ledger\n\nfunc value() int { return 1 }\n");
        GoTestContexts.write(root, "ledger/ledger_test.go", """
                package ledger

                import "testing"

                func TestNothing(t *testing.T) {
                	value := 1 + 1
                }

                func TestSomething(t *testing.T) {
                	if 1+1 != 2 {
                		t.Errorf("math is broken")
                	}
                }
                """);
        VerificationContext context = GoTestContexts.context(root, run);

        List<Finding> findings = new GoHeuristicAnalyzer().analyzeTests(context);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals("CW-GO-TEST-001", findings.getFirst().ruleId());
        assertTrue(findings.getFirst().message().contains("TestNothing"), findings.getFirst().message());
        assertEquals("ledger/ledger_test.go", findings.getFirst().file());
    }

    @Test
    @DisplayName("a block comment and a raw string are stripped across lines")
    void stripsBlockCommentsAndRawStrings() {
        boolean[] inBlock = {false};
        String stripped = GoHeuristicAnalyzer.codeOnly("x := 1 /* panic(1) */", inBlock);
        assertTrue(stripped.startsWith("x := 1"), stripped);
        assertFalse(stripped.contains("panic"), stripped);
        assertFalse(inBlock[0]);
        boolean[] open = {false};
        GoHeuristicAnalyzer.codeOnly("/* start", open);
        assertTrue(open[0]);
        assertFalse(GoHeuristicAnalyzer.codeOnly("   panic(1) */ code", open).contains("panic"));

        // A backtick raw string spans lines, so the state is carried in the second slot.
        boolean[] raw = {false, false};
        String first = GoHeuristicAnalyzer.codeOnly("query := `SELECT *", raw);
        assertTrue(raw[1], "an unterminated raw string must be carried to the next line");
        assertTrue(first.contains("SELECT"));
        GoHeuristicAnalyzer.codeOnly("FROM users`", raw);
        assertFalse(raw[1]);
    }

    @Test
    @DisplayName("a vet message that names a compile error is promoted over an ordinary concern")
    void vetSeverityDistinguishesCompileErrors() {
        // Pure mapping, so this test never needs a Go toolchain.
        assertEquals(Severity.HIGH, GoHeuristicAnalyzer.vetSeverity("undefined: balance"));
        assertEquals(Severity.HIGH, GoHeuristicAnalyzer.vetSeverity("syntax error: unexpected EOF"));
        assertEquals(Severity.HIGH, GoHeuristicAnalyzer.vetSeverity("declared and not used: recorded"));
        assertEquals(Severity.MEDIUM,
                GoHeuristicAnalyzer.vetSeverity("fmt.Printf format %d has arg s of wrong type string"));
        assertEquals(Severity.MEDIUM, GoHeuristicAnalyzer.vetSeverity(null));
    }

    @Test
    @DisplayName("go vet is skipped when there is no module to vet")
    void goVetIsSkippedWithoutAManifest(@TempDir Path root, @TempDir Path run) throws IOException {
        GoTestContexts.write(root, "svc/value.go", "package svc\n\nfunc value() int { return 1 }\n");
        VerificationContext context = GoTestContexts.context(root, run);

        // No go.mod means no process is started and no finding is invented.
        assertTrue(new GoHeuristicAnalyzer().analyzeGoVet(context).isEmpty());
    }

    @Test
    @DisplayName("go vet findings are folded into static analysis when the toolchain exists")
    void foldsVetFindingsIntoStaticAnalysis(@TempDir Path root, @TempDir Path run) throws IOException {
        GoTestContexts.write(root, "go.mod", "module example.com/clean\n\ngo 1.22\n");
        GoTestContexts.write(root, "clean.go", "package clean\n\nfunc Value() int { return 1 }\n");
        VerificationContext context = GoTestContexts.context(root, run);

        // A clean module is the valid precondition for vet; without the toolchain the integration
        // assertion is skipped rather than failing, because the test is about wiring, not installing Go.
        assumeTrue(GoTool.probe(context, GoTool.go(context.projectInfo(), context)).available(),
                "go is not installed, so go vet cannot be exercised");

        assertTrue(new GoHeuristicAnalyzer().analyzeGoVet(context).isEmpty(),
                "a clean module must produce no vet findings");
    }
}

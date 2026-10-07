package dev.codewitness.csharp.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.csharp.CSharpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static dev.codewitness.csharp.CSharpTestContexts.LIBRARY_PROJECT;
import static dev.codewitness.csharp.CSharpTestContexts.TEST_PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the C# rule table.
 *
 * <p>Every rule is exercised twice where it matters: once on code that has the hazard and once on the
 * shape that looks like it but is not - a hazard named in a comment, an operator inside a string, a
 * handler that rethrows. A rule table whose false positives are not tested is a rule table nobody can
 * use.
 */
class CSharpHeuristicAnalyzerTest {

    private final CSharpHeuristicAnalyzer analyzer = new CSharpHeuristicAnalyzer();

    private VerificationContext contextFor(Path project, Path run, String source) throws IOException {
        CSharpTestContexts.write(project, "Ledger.csproj", LIBRARY_PROJECT);
        CSharpTestContexts.write(project, "src/Ledger/Program.cs", source);
        return CSharpTestContexts.context(project, run);
    }

    private List<String> rules(Path project, Path run, String source) throws IOException {
        VerificationContext context = contextFor(project, run, source);
        return analyzer.findAll(context).stream().map(Finding::ruleId).distinct().sorted().toList();
    }

    @Test
    @DisplayName("a secret in a literal is reported; the same words in a comment or a message are not")
    void detectsCredentials(@TempDir Path project, @TempDir Path run) throws IOException {
        assertTrue(rules(project, run, "var password = \"hunter2\";\n").contains("CW-CS-SEC-001"));
        assertFalse(rules(project, run, "// password = \"hunter2\"\n").contains("CW-CS-SEC-001"),
                "a comment that describes a credential is not a credential");

        Path other = project.resolve("other");
        assertFalse(rules(other, run, "var message = \"set the password to something\";\n")
                .contains("CW-CS-SEC-001"));
    }

    @Test
    @DisplayName("the security rules fire on the hazards they name")
    void detectsSecurityHazards(@TempDir Path project, @TempDir Path run) throws IOException {
        assertTrue(rules(project, run, "Process.Start(\"sh\", \"-c whoami\");\n").contains("CW-CS-SEC-002"));
        assertTrue(rules(project, run, "var q = $\"SELECT * FROM t WHERE id = {id}\";\n")
                .contains("CW-CS-SEC-003"));
        assertTrue(rules(project, run, "var h = MD5.Create();\n").contains("CW-CS-SEC-005"));
        assertTrue(rules(project, run, "var f = new BinaryFormatter();\n").contains("CW-CS-SEC-006"));
        assertTrue(rules(project, run, "var r = new Random(1);\n").contains("CW-CS-SEC-007"));
        assertTrue(rules(project, run, "var s = new XmlReaderSettings { DtdProcessing = DtdProcessing.Parse };\n")
                .contains("CW-CS-SEC-009"));
        assertTrue(rules(project, run, "policy.AllowAnyOrigin();\n").contains("CW-CS-SEC-010"));
        assertTrue(rules(project, run, "handler.ServerCertificateCustomValidationCallback = (a, b, c, d) => true;\n")
                .contains("CW-CS-SEC-004"));
    }

    @Test
    @DisplayName("the correctness rules fire on the hazards they name")
    void detectsCorrectnessHazards(@TempDir Path project, @TempDir Path run) throws IOException {
        assertTrue(rules(project, run, "double totalAmount = 0;\n").contains("CW-CS-CORRECT-001"));
        assertTrue(rules(project, run, "if (value == 1.5)\n{\n}\n").contains("CW-CS-CORRECT-002"));
        assertTrue(rules(project, run, "var day = at / 86_400_000;\n").contains("CW-CS-CORRECT-003"));
        assertTrue(rules(project, run, "var now = DateTime.Now;\n").contains("CW-CS-CORRECT-004"));
        assertTrue(rules(project, run, "lock (this)\n{\n}\n").contains("CW-CS-REL-006"));
        assertTrue(rules(project, run, "async void Fire()\n{\n}\n").contains("CW-CS-REL-002"));
        assertTrue(rules(project, run, "var result = task.Result;\n").contains("CW-CS-REL-001"));
        assertTrue(rules(project, run, "GC.Collect();\n").contains("CW-CS-REL-004"));
        assertTrue(rules(project, run, "Thread.Sleep(1000);\n").contains("CW-CS-REL-005"));
        assertTrue(rules(project, run, "throw new Exception(\"nope\");\n").contains("CW-CS-REL-007"));
    }

    @Test
    @DisplayName("a suppressed warning is reported, and only the directive rule reads a comment")
    void reportsSuppressions(@TempDir Path project, @TempDir Path run) throws IOException {
        assertTrue(rules(project, run, "#pragma warning disable CS0219\n").contains("CW-CS-REL-003"));
        assertTrue(rules(project, run, "[SuppressMessage(\"Category\", \"Rule\")]\n").contains("CW-CS-REL-003"));
    }

    @Test
    @DisplayName("a swallowed exception is reported and a reported one is not")
    void detectsSwallowedCatches(@TempDir Path project, @TempDir Path run) throws IOException {
        assertTrue(rules(project, run, "try\n{\n    Risky();\n}\ncatch (Exception)\n{\n}\n")
                .contains("CW-CS-REL-008"));
        assertTrue(rules(project, run, "try\n{\n    Risky();\n}\ncatch (Exception)\n{\n    total = 0;\n}\n")
                .contains("CW-CS-REL-008"),
                "a handler that merely assigns is still silent about the failure");
        assertFalse(rules(project, run, "try\n{\n    Risky();\n}\ncatch (Exception e)\n{\n    throw;\n}\n")
                .contains("CW-CS-REL-008"));
        assertFalse(rules(project, run, "try\n{\n    Risky();\n}\ncatch (Exception e)\n{\n    _log.LogError(e, \"failed\");\n}\n")
                .contains("CW-CS-REL-008"));
    }

    @Test
    @DisplayName("a test with no assertion is reported and one that asserts is not")
    void detectsTestsThatCannotFail(@TempDir Path project, @TempDir Path run) throws IOException {
        CSharpTestContexts.write(project, "Ledger.csproj", LIBRARY_PROJECT);
        CSharpTestContexts.write(project, "tests/Ledger.Tests/Ledger.Tests.csproj", TEST_PROJECT);
        CSharpTestContexts.write(project, "tests/Ledger.Tests/WithdrawalLedgerTests.cs", """
                using Xunit;

                namespace Ledger.Tests;

                public class WithdrawalLedgerTests
                {
                    [Fact]
                    public void DoesNothing() { }

                    [Fact]
                    public void Asserts()
                    {
                        Assert.Equal(1, 1);
                    }
                }
                """);
        VerificationContext context = CSharpTestContexts.context(project, run);

        List<Finding> findings = analyzer.analyzeTests(context);
        assertEquals(1, findings.size(), "only the test that cannot fail is reported: " + findings);
        assertEquals("CW-CS-TEST-001", findings.getFirst().ruleId());
        assertEquals("tests/Ledger.Tests/WithdrawalLedgerTests.cs", findings.getFirst().file());
        assertTrue(findings.getFirst().message().contains("DoesNothing"));
    }

    @Test
    @DisplayName("the analyzer reports itself as heuristic, so its findings are advisory")
    void isAdvisory() {
        assertEquals("csharp-heuristics", analyzer.id());
        assertFalse(new Finding("csharp-heuristics", "CW-CS-SEC-001", Severity.CRITICAL,
                FindingCategory.SECURITY, "message", "file", 1, null, null).fromDeterministicTool(),
                "a pattern table with a documented false-positive profile must not refute a requirement");
    }

    @Test
    @DisplayName("the rule table is enumerable, which is what keeps it auditable")
    void exposesTheRuleTable() {
        List<String> ids = CSharpHeuristicAnalyzer.ruleIds();
        assertTrue(ids.contains("CW-CS-SEC-001"));
        assertTrue(ids.contains("CW-CS-TEST-001"));
        assertTrue(ids.contains("CW-CS-REL-008"));
        assertEquals(ids.size(), ids.stream().distinct().count(), "no rule is defined twice");
        assertEquals(Severity.CRITICAL, CSharpHeuristicAnalyzer.severityOf("CW-CS-SEC-001"));
        assertTrue(CSharpHeuristicAnalyzer.isSecurityRule("CW-CS-SEC-002"));
        assertFalse(CSharpHeuristicAnalyzer.isSecurityRule("CW-CS-REL-001"));
    }

    @Test
    @DisplayName("security analysis reports the security rules and the plain analysis the rest")
    void splitsSecurityFromCorrectness(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = contextFor(project, run, """
                var password = "hunter2";
                double totalAmount = 0;
                """);

        assertTrue(analyzer.analyzeSecurity(context).stream()
                .allMatch(finding -> CSharpHeuristicAnalyzer.isSecurityRule(finding.ruleId())));
        assertTrue(analyzer.analyze(context).stream()
                .noneMatch(finding -> CSharpHeuristicAnalyzer.isSecurityRule(finding.ruleId())));
        assertEquals(2, analyzer.findAll(context).size());
    }
}

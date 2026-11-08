package dev.codewitness.python.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.python.PythonTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Python heuristic and security rule table. */
class PythonHeuristicAnalyzerTest {

    private static boolean hasRule(List<Finding> findings, String ruleId) {
        return findings.stream().anyMatch(finding -> ruleId.equals(finding.ruleId()));
    }

    @Test
    @DisplayName("mutable default arguments, bare excepts and asserts in production code are reported")
    void reportsCorrectnessHeuristics(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "ledger.py", """
                def record(items=[], cache={}):
                    assert items is not None
                    try:
                        return items
                    except:
                        pass


                def total():
                    try:
                        return 1
                    except Exception:
                        return 0
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        List<Finding> findings = new PythonHeuristicAnalyzer().analyze(context);

        assertTrue(hasRule(findings, "CW-PY-MUT-001"), "mutable default must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PY-REL-001"), "bare except must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PY-REL-003"), "broad except must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PY-BUG-004"), "assert in production code must be reported: " + findings);
    }

    @Test
    @DisplayName("security rules cover eval/exec, shell=True, unsafe deserialisation and hardcoded secrets")
    void reportsSecurityRules(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "risky.py", """
                import os
                import pickle
                import subprocess

                API_KEY = "sk-live-abcdef123456"

                def run(command, blob):
                    eval(command)
                    os.system(command)
                    subprocess.run(command, shell=True)
                    return pickle.loads(blob)
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        List<Finding> all = new PythonHeuristicAnalyzer().findAll(context);

        assertTrue(hasRule(all, "CW-PY-SEC-001"), "hardcoded credential must be reported: " + all);
        assertTrue(hasRule(all, "CW-PY-SEC-002"), "eval must be reported: " + all);
        assertTrue(hasRule(all, "CW-PY-SEC-003"), "shell=True must be reported: " + all);
        assertTrue(hasRule(all, "CW-PY-SEC-004"), "os.system must be reported: " + all);
        assertTrue(hasRule(all, "CW-PY-SEC-005"), "pickle.loads must be reported: " + all);
    }

    @Test
    @DisplayName("the static-analysis view excludes security rules, which the security phase reports")
    void separatesSecurityFindings(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "risky.py", "API_KEY = \"sk-live-abcdef123456\"\n");
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        List<Finding> staticFindings = new PythonHeuristicAnalyzer().analyze(context);
        List<Finding> securityFindings = new PythonHeuristicAnalyzer().analyzeSecurity(context);

        assertTrue(staticFindings.stream().noneMatch(f -> f.category().name().equals("SECURITY")));
        assertTrue(hasRule(securityFindings, "CW-PY-SEC-001"));
    }

    @Test
    @DisplayName("a test with no assertion is reported against the test source")
    void reportsTestsThatCannotFail(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "ledger.py", "VALUE = 1\n");
        PythonTestContexts.write(root, "tests/test_ledger.py", """
                import unittest


                class LedgerTest(unittest.TestCase):
                    def test_nothing(self):
                        value = 1
                        print(value)

                    def test_something(self):
                        self.assertEqual(1, 1)
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        List<Finding> findings = new PythonHeuristicAnalyzer().analyzeTests(context);

        assertTrue(hasRule(findings, "CW-PY-TEST-001"), "a test without an assertion must be reported: " + findings);
        assertTrue(findings.stream().noneMatch(f -> f.line() != null && f.line() == 9),
                "the test with an assertion must not be reported: " + findings);
    }

    @Test
    @DisplayName("the documented severity of a rule is stable")
    void exposesRuleSeverity() {
        assertTrue(Severity.CRITICAL == PythonHeuristicAnalyzer.severityOf("CW-PY-SEC-001"));
        assertTrue(Severity.HIGH == PythonHeuristicAnalyzer.severityOf("CW-PY-SEC-002"));
        assertTrue(PythonHeuristicAnalyzer.ruleIds().contains("CW-PY-SEC-010"));
    }

    @Test
    @DisplayName("a method named eval is a definition, not a call to the eval builtin")
    void doesNotReportDefinitionsNamedEval(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        // Nine HIGH security findings on one real project, every one of them a `def eval(self, ...)` header and
        // not one of them a call: a definition of a method called eval reads exactly like the builtin call.
        PythonTestContexts.write(root, "kernel.py", """
                class Kernel:
                    def eval(self, text, steps=False):
                        return {"text": text}

                    async def exec(self, text):
                        return text


                def eval(text):
                    return text


                class eval:
                    pass


                def caller(expression):
                    return eval(expression)
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        List<Finding> security = new PythonHeuristicAnalyzer().analyzeSecurity(context);
        List<Finding> evalFindings = security.stream()
                .filter(finding -> "CW-PY-SEC-002".equals(finding.ruleId())).toList();

        assertEquals(1, evalFindings.size(),
                "only the real call to the builtin is a finding: " + evalFindings);
        assertEquals(18, evalFindings.getFirst().line(),
                "the finding must be the call, not a definition: " + evalFindings);
    }

    @Test
    @DisplayName("docstring prose and identity checks against True/False are not defects")
    void doesNotReportProseOrBooleanIdentity(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        // Five MEDIUM findings on one real project: three were English sentences in docstrings ("a decimal that
        // terminates is 1 and 2.5 is 5/2", "... is 0") and two were `x is False` identity checks, which PEP 8
        // recommends. Only `is` against a number or a string is the defect the rule is about.
        PythonTestContexts.write(root, "lean.py", """
                \"\"\"Exact rationals.

                `1.0` is 1 and `2.5` is 5/2 - a decimal that terminates is a rational.
                A root with an imaginary part is dropped: the sqrt of a negative is 0.
                eval() is never called from this module.
                \"\"\"


                def classify(width):
                    if width is False:
                        return None
                    return width


                MESSAGE = "a decimal that terminates is 1, and eval() is not called"


                class Checker:
                    def check(self, value):
                        self.assertIs(value, False)


                def literal_identity(value):
                    return value is 1
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        List<Finding> findings = new PythonHeuristicAnalyzer().analyze(context);
        List<Finding> identity = findings.stream()
                .filter(finding -> "CW-PY-BUG-002".equals(finding.ruleId())).toList();

        assertEquals(1, identity.size(),
                "only the comparison against a number is a finding: " + identity);
        assertEquals(24, identity.getFirst().line(),
                "the finding must be the numeric identity comparison, not prose: " + identity);
        assertTrue(findings.stream().noneMatch(finding -> "CW-PY-SEC-002".equals(finding.ruleId())),
                "eval() mentioned inside a docstring or a message is text, not a call: " + findings);
    }

    @Test
    @DisplayName("prose in a comment is not code either")
    void ignoresRulesInsideComments(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "notes.py", """
                VALUE = 1  # the documented example is 1 and eval() is not used here
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        List<Finding> findings = new PythonHeuristicAnalyzer().findAll(context);

        assertTrue(findings.stream().noneMatch(finding -> "CW-PY-BUG-002".equals(finding.ruleId())),
                "a trailing comment is not code: " + findings);
        assertTrue(findings.stream().noneMatch(finding -> "CW-PY-SEC-002".equals(finding.ruleId())),
                "a trailing comment is not a call: " + findings);
    }

    @Test
    @DisplayName("a real eval call, a real is-literal comparison and a real secret are still reported")
    void keepsTheTruePositives(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        // The guard for the guard: precision work must not turn the rule table off.
        PythonTestContexts.write(root, "risky.py", """
                API_KEY = "sk-live-abcdef123456"


                def run(command):
                    result = eval(command)
                    if result is "ok":
                        return True
                    return result
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        List<Finding> all = new PythonHeuristicAnalyzer().findAll(context);

        assertTrue(hasRule(all, "CW-PY-SEC-001"), "a credential literal is still a credential: " + all);
        assertTrue(hasRule(all, "CW-PY-SEC-002"), "a real eval() call is still a finding: " + all);
        assertTrue(hasRule(all, "CW-PY-BUG-002"), "identity against a string literal is still a finding: " + all);
    }
}

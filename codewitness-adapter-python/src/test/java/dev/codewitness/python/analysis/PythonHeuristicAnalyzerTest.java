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
}

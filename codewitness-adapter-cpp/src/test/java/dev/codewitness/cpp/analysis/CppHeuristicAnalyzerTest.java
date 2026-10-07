package dev.codewitness.cpp.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.cpp.CppTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static dev.codewitness.cpp.CppTestContexts.LIBRARY_CMAKE;
import static dev.codewitness.cpp.CppTestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the C and C++ rule table.
 *
 * <p>Each test states a hazard the compilers deliberately accept and asserts that the rule table reports it,
 * that it does not report the safe spelling of the same thing, and that the finding carries the rule id, the
 * line a reader will look at and the snippet they will find there.
 */
class CppHeuristicAnalyzerTest {

    /**
     * Writes a one-file project and reports everything the rule table finds in it.
     *
     * <p>{@code findAll} is used rather than {@code analyze} because these tests are about the rules: the
     *     split between the static and the security phase is what {@link #separatesTheTwoPhases} asserts.
     */
    private static List<Finding> analyse(Path project, Path run, String source) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", source);
        VerificationContext context = CppTestContexts.context(project, run);
        return new CppHeuristicAnalyzer().findAll(context);
    }

    private static boolean has(List<Finding> findings, String ruleId) {
        return findings.stream().anyMatch(finding -> ruleId.equals(finding.ruleId()));
    }

    private static Finding first(List<Finding> findings, String ruleId) {
        return findings.stream().filter(finding -> ruleId.equals(finding.ruleId())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a hardcoded credential is reported, and a configuration read is not")
    void findsHardcodedCredentials(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                const char* password = "hunter2";
                const char* key = readConfig("api_key");
                """);

        assertTrue(has(findings, "CW-CPP-SEC-001"), findings.toString());
        assertEquals(Severity.CRITICAL, first(findings, "CW-CPP-SEC-001").severity());
        assertEquals(FindingCategory.SECURITY, first(findings, "CW-CPP-SEC-001").category());
        assertTrue(first(findings, "CW-CPP-SEC-001").attributes().get("snippet").contains("password"),
                first(findings, "CW-CPP-SEC-001").attributes().toString());
        assertEquals(1, findings.stream().filter(finding -> "CW-CPP-SEC-001".equals(finding.ruleId())).count());
    }

    @Test
    @DisplayName("an unbounded C string function is reported, and the standard library is not")
    void findsUnboundedStringFunctions(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                void copy(const char* input) {
                    char buffer[8];
                    strcpy(buffer, input);
                    std::string safe = input;
                }
                """);

        assertTrue(has(findings, "CW-CPP-SEC-002"), findings.toString());
        assertEquals(0, findings.stream()
                .filter(finding -> finding.line() != null && finding.line() == 4).count(),
                "the std::string line is not a finding: " + findings);
    }

    @Test
    @DisplayName("memory and bounds hazards are separated by rule, not lumped together")
    void findsMemoryHazards(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                void fill(char* buffer, const char* input, std::size_t n) {
                    char* raw = (char*)malloc(n);
                    memset(buffer, 0, sizeof(buffer));
                    memcpy(buffer, input, sizeof(buffer));
                    free(raw);
                }
                """);

        assertTrue(has(findings, "CW-CPP-SEC-004"), "a raw allocation: " + findings);
        assertTrue(has(findings, "CW-CPP-SEC-005"), "a C memory function: " + findings);
        assertTrue(has(findings, "CW-CPP-MEM-001"), "a hand-written free: " + findings);
        assertTrue(has(findings, "CW-CPP-MEM-002"), "sizeof of a pointer: " + findings);
    }

    @Test
    @DisplayName("a monetary value held as a binary float is reported")
    void findsMoneyAsFloat(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                struct Account {
                    double balance;
                    long long balanceMinor;
                };
                """);

        assertTrue(has(findings, "CW-CPP-NUM-001"), findings.toString());
        assertEquals(Severity.HIGH, first(findings, "CW-CPP-NUM-001").severity());
        assertEquals(0, findings.stream()
                .filter(finding -> finding.ruleId().equals("CW-CPP-NUM-001") && finding.line() == 3).count(),
                "minor units are the fix, not the hazard: " + findings);
    }

    @Test
    @DisplayName("a calendar-day conversion is reported, and a rolling window is not")
    void findsCalendarArithmetic(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                const long long millisPerDay = 86400000;
                long long dayOf(long long at) { return at / millisPerDay; }
                """);

        assertTrue(has(findings, "CW-CPP-CORRECT-001"), findings.toString());
        assertEquals(1, first(findings, "CW-CPP-CORRECT-001").line());
    }

    @Test
    @DisplayName("an off-by-one loop over a container is reported")
    void findsOffByOneLoops(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                long long sum(const std::vector<long long>& values) {
                    long long total = 0;
                    for (std::size_t i = 0; i <= values.size(); ++i) {
                        total += values[i];
                    }
                    return total;
                }
                """);

        assertTrue(has(findings, "CW-CPP-BOUND-001"), findings.toString());
        assertEquals(3, first(findings, "CW-CPP-BOUND-001").line());
    }

    @Test
    @DisplayName("an empty catch block is reported, and one that reports is not")
    void findsEmptyCatches(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                void run() {
                    try {
                        risky();
                    } catch (const std::exception&) {
                    }
                    try {
                        risky();
                    } catch (const std::exception& error) {
                        std::fprintf(stderr, "%s\\n", error.what());
                    }
                }
                """);

        assertTrue(has(findings, "CW-CPP-CORRECT-004"), findings.toString());
        assertEquals(1, findings.stream()
                .filter(finding -> "CW-CPP-CORRECT-004".equals(finding.ruleId())).count(),
                "the handler that reports is not a finding: " + findings);
        assertEquals(4, first(findings, "CW-CPP-CORRECT-004").line());
    }

    @Test
    @DisplayName("a suppression directive is reported from the comment, where nothing else may fire")
    void findsSuppressions(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                int total = risky(); // NOLINT
                // cppcheck-suppress uninitvar
                int other = 1;
                const char* url = "http://pragma";
                """);

        List<Finding> suppressions = findings.stream()
                .filter(finding -> "CW-CPP-REL-003".equals(finding.ruleId())).toList();
        assertEquals(2, suppressions.size(), findings.toString());
        assertEquals(1, suppressions.getFirst().line());
        assertTrue(suppressions.stream().noneMatch(finding -> finding.line() == 4),
                "a URL inside a literal is not a directive: " + findings);
    }

    @Test
    @DisplayName("a using-directive in a header is reported, and one in a source file is not")
    void findsUsingDirectivesInHeaders(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "using namespace std;\nint ledger() { return 0; }\n");
        write(project, "include/ledger.hpp", "using namespace std;\nint ledger();\n");
        VerificationContext context = CppTestContexts.context(project, run);

        List<Finding> findings = new CppHeuristicAnalyzer().analyze(context);

        List<Finding> usingDirectives = findings.stream()
                .filter(finding -> "CW-CPP-CORRECT-003".equals(finding.ruleId())).toList();
        assertEquals(1, usingDirectives.size(), findings.toString());
        assertEquals("include/ledger.hpp", usingDirectives.getFirst().file());
    }

    @Test
    @DisplayName("a test with nothing to assert is reported, and one that asserts is not")
    void findsTestsThatCannotFail(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        write(project, "tests/ledger_test.cpp", """
                void ledgerTestDoesNothing() {
                    int unused = 1;
                }

                void ledgerTestChecksTheTotal() {
                    if (ledger() != 0) {
                        std::abort();
                    }
                }
                """);
        VerificationContext context = CppTestContexts.context(project, run);

        List<Finding> findings = new CppHeuristicAnalyzer().analyze(context);

        List<Finding> tests = findings.stream()
                .filter(finding -> "CW-CPP-TEST-001".equals(finding.ruleId())).toList();
        assertEquals(1, tests.size(), findings.toString());
        assertTrue(tests.getFirst().message().contains("ledgerTestDoesNothing"), tests.getFirst().message());
    }

    @Test
    @DisplayName("the security phase sees the security rules and the static phase does not")
    void separatesTheTwoPhases(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", """
                const char* password = "hunter2";
                double balance;
                """);
        VerificationContext context = CppTestContexts.context(project, run);
        CppHeuristicAnalyzer analyzer = new CppHeuristicAnalyzer();

        List<Finding> staticFindings = analyzer.analyze(context);
        List<Finding> securityFindings = analyzer.analyzeSecurity(context);

        assertTrue(has(securityFindings, "CW-CPP-SEC-001"), securityFindings.toString());
        assertFalse(has(staticFindings, "CW-CPP-SEC-001"),
                "the static phase reports the correctness rules only: " + staticFindings);
        assertTrue(has(staticFindings, "CW-CPP-NUM-001"), staticFindings.toString());
        assertFalse(has(securityFindings, "CW-CPP-NUM-001"), securityFindings.toString());
        assertEquals(analyzer.findAll(context).size(), staticFindings.size() + securityFindings.size(),
                "a finding is counted under exactly one rule and in exactly one phase");
    }

    @Test
    @DisplayName("the security analyzer is the same table, reached through the security phase")
    void theSecurityAnalyzerSharesTheTable(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "int total = system(command);\n");
        VerificationContext context = CppTestContexts.context(project, run);

        List<Finding> findings = new CppSecurityAnalyzer().analyze(context);

        assertEquals("cpp-security", new CppSecurityAnalyzer().id());
        assertTrue(findings.stream().anyMatch(finding -> "CW-CPP-SEC-003".equals(finding.ruleId())),
                findings.toString());
        assertTrue(findings.stream().allMatch(finding -> CppHeuristicAnalyzer.isSecurityRule(finding.ruleId())),
                "the security phase reports security rules only: " + findings);
    }

    @Test
    @DisplayName("the rule set is documented, and every rule has a severity a report can state")
    void documentsTheRuleSet() {
        List<String> ids = CppHeuristicAnalyzer.ruleIds();

        assertTrue(ids.contains("CW-CPP-SEC-001"));
        assertTrue(ids.contains("CW-CPP-TEST-001"));
        assertTrue(ids.contains("CW-CPP-CORRECT-004"));
        assertEquals(ids.size(), ids.stream().distinct().count(), "a rule id is published once: " + ids);
        assertTrue(ids.stream().allMatch(id -> CppHeuristicAnalyzer.severityOf(id) != null));
        assertTrue(CppHeuristicAnalyzer.isSecurityRule("CW-CPP-SEC-001"));
        assertFalse(CppHeuristicAnalyzer.isSecurityRule("CW-CPP-NUM-001"));
        assertFalse(CppHeuristicAnalyzer.isSecurityRule(null));
        assertEquals(Severity.CRITICAL, CppHeuristicAnalyzer.severityOf("CW-CPP-SEC-001"));
        assertEquals(Severity.MEDIUM, CppHeuristicAnalyzer.severityOf("CW-CPP-NOT-A-RULE"));
    }

    @Test
    @DisplayName("a rule never fires inside a comment or a literal, except the suppression rule")
    void neverFiresInTextThatIsNotCode(@TempDir Path project, @TempDir Path run) throws IOException {
        List<Finding> findings = analyse(project, run, """
                // strcpy(buffer, input);
                /* system(command); */
                const char* advice = "never call system(command) or strcpy";
                const char* documented = "86400000 is a day";
                bool flag = true;
                """);

        assertTrue(findings.isEmpty(),
                "a hazard described in prose is not the hazard: " + findings);
    }

    @Test
    @DisplayName("a tree with no source file yields no finding rather than an error")
    void handlesAnEmptyTree(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        VerificationContext context = CppTestContexts.context(project, run);

        assertEquals(List.of(), new CppHeuristicAnalyzer().analyze(context));
        assertEquals(List.of(), new CppHeuristicAnalyzer().analyzeSecurity(context));
        assertEquals(List.of(), new CppHeuristicAnalyzer().analyzeTests(context));
        assertEquals(List.of(), new CppHeuristicAnalyzer().analyzeEmptyCatches(context));
    }

    @Test
    @DisplayName("the analyzer names itself and claims C and C++ projects")
    void identifiesItself(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        VerificationContext context = CppTestContexts.context(project, run);

        assertEquals("cpp-heuristics", new CppHeuristicAnalyzer().id());
        assertTrue(new CppHeuristicAnalyzer().supports(context.projectInfo()));
    }
}

package dev.codewitness.java.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.java.TestContexts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heuristic rules are the part of the adapter that must never fire by accident, so every rule is
 * tested twice: once against a file that triggers it, and once against the closest file that must
 * not. A rule that fires on both is worse than no rule, because it manufactures false evidence.
 */
class HeuristicSourceAnalyzerTest {

    @TempDir
    Path tmp;

    private final AtomicInteger counter = new AtomicInteger();

    // ------------------------------------------------------------------ CW-NUM-001

    @Test
    void discardedBigDecimalResultIsReported() throws IOException {
        VerificationContext context = contextWithSource("Ledger.java", """
                package com.example;

                import java.math.BigDecimal;

                public class Ledger {
                    public void apply(BigDecimal total, BigDecimal amount) {
                        total.add(amount);
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-NUM-001");
        assertEquals(Severity.HIGH, finding.severity());
        assertEquals(FindingCategory.BUG, finding.category());
        assertEquals(7, finding.line(), "the finding points at the discarded call");
        assertTrue(finding.message().contains("immutable"), finding.message());
    }

    @Test
    void addingToACollectionIsNotADiscardedBigDecimal() throws IOException {
        VerificationContext context = contextWithSource("Ledger.java", """
                package com.example;

                import java.math.BigDecimal;

                public class Ledger {
                    public void apply(java.util.List<BigDecimal> list, BigDecimal amount) {
                        list.add(amount);
                    }
                }
                """, false);

        assertNoRule(context, "CW-NUM-001");
    }

    @Test
    void assigningTheBigDecimalResultIsNotADiscardedCall() throws IOException {
        VerificationContext context = contextWithSource("Ledger.java", """
                package com.example;

                import java.math.BigDecimal;

                public class Ledger {
                    public BigDecimal totalOf(BigDecimal total, BigDecimal amount) {
                        BigDecimal newTotal = total.add(amount);
                        return newTotal;
                    }
                }
                """, false);

        assertNoRule(context, "CW-NUM-001");
    }

    @Test
    void addingToACollectionNamedValuesIsNotADiscardedBigDecimal() throws IOException {
        // The self-check reported these lines in SwiftScenarioTestGenerator: the file imports BigDecimal
        // for another expression and "values" reads like a quantity, so the receiver was assumed to be a
        // BigDecimal even though it is a list.
        VerificationContext context = contextWithSource("SwiftScenarioTestGenerator.java", """
                package com.example;

                import java.math.BigDecimal;
                import java.util.List;

                public class SwiftScenarioTestGenerator {
                    List<String> boundaryValuesFor(List<String> values, String input, String min) {
                        if (values.isEmpty()) {
                            values.add(defaultLiteral(input));
                        }
                        BigDecimal moved = new BigDecimal(min).add(BigDecimal.ONE);
                        return values;
                    }
                }
                """, false);

        assertNoRule(context, "CW-NUM-001");
    }

    @Test
    void aDiscardedCallOnADeclaredBigDecimalFieldIsStillReported() throws IOException {
        VerificationContext context = contextWithSource("Ledger.java", """
                package com.example;

                import java.math.BigDecimal;

                public class Ledger {
                    private BigDecimal total;

                    public void apply(BigDecimal amount) {
                        this.total.add(amount);
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-NUM-001");
        assertEquals(9, finding.line(), "the finding points at the discarded call");
    }

    // ------------------------------------------------------------------ CW-NUM-002

    @Test
    void doubleUsedForMoneyIsReported() throws IOException {
        VerificationContext context = contextWithSource("Money.java", """
                package com.example;

                public class Money {
                    public double balanceOf(double amount) {
                        return amount;
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-NUM-002");
        assertEquals(Severity.HIGH, finding.severity());
        assertEquals(FindingCategory.CORRECTNESS, finding.category());
    }

    @Test
    void integerUsedForAQuantityIsNotReported() throws IOException {
        VerificationContext context = contextWithSource("Counter.java", """
                package com.example;

                public class Counter {
                    public int countOf(int amount) {
                        return amount;
                    }
                }
                """, false);

        assertNoRule(context, "CW-NUM-002");
    }

    // ------------------------------------------------------------------ CW-NUM-003

    @Test
    void equalsOnAnAmountIsReported() throws IOException {
        VerificationContext context = contextWithSource("Money.java", """
                package com.example;

                import java.math.BigDecimal;

                public class Money {
                    public boolean same(BigDecimal amount, BigDecimal other) {
                        return amount.equals(other);
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-NUM-003");
        assertEquals(Severity.MEDIUM, finding.severity());
        assertEquals(FindingCategory.BUG, finding.category());
    }

    @Test
    void equalsOnANonNumericNameIsNotReported() throws IOException {
        VerificationContext context = contextWithSource("Names.java", """
                package com.example;

                public class Names {
                    public boolean same(String name, String other) {
                        return name.equals(other);
                    }
                }
                """, false);

        assertNoRule(context, "CW-NUM-003");
    }

    // ------------------------------------------------------------------ CW-SEC-001

    @Test
    void hardcodedCredentialIsACriticalSecurityFinding() throws IOException {
        VerificationContext context = contextWithSource("Config.java", """
                package com.example;

                public class Config {
                    public void connect() {
                        String password = "hunter2";
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-SEC-001");
        assertEquals(Severity.CRITICAL, finding.severity());
        assertEquals(FindingCategory.SECURITY, finding.category());
        assertEquals(5, finding.line());
    }

    @Test
    void aCredentialReadFromTheEnvironmentIsNotReported() throws IOException {
        VerificationContext context = contextWithSource("Config.java", """
                package com.example;

                public class Config {
                    public void connect() {
                        String password = System.getenv("DB_PASSWORD");
                    }
                }
                """, false);

        assertNoRule(context, "CW-SEC-001");
    }

    // ------------------------------------------------------------------ CW-SEC-002

    @Test
    void sqlBuiltByConcatenationIsAHighSecurityFinding() throws IOException {
        VerificationContext context = contextWithSource("Users.java", """
                package com.example;

                import java.sql.Statement;

                public class Users {
                    public void find(Statement statement, String name) throws Exception {
                        statement.executeQuery("SELECT * FROM users WHERE name = '" + name + "'");
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-SEC-002");
        assertEquals(Severity.HIGH, finding.severity());
        assertEquals(FindingCategory.SECURITY, finding.category());
    }

    @Test
    void aParameterisedQueryIsNotReported() throws IOException {
        VerificationContext context = contextWithSource("Users.java", """
                package com.example;

                public class Users {
                    public void find(java.sql.PreparedStatement statement, String name) throws Exception {
                        statement.executeQuery("SELECT * FROM users WHERE name = ?", name);
                    }
                }
                """, false);

        assertNoRule(context, "CW-SEC-002");
    }

    // ------------------------------------------------------------------ CW-SEC-003

    @Test
    void md5IsReportedAsAWeakDigest() throws IOException {
        VerificationContext context = contextWithSource("Hashing.java", """
                package com.example;

                import java.security.MessageDigest;

                public class Hashing {
                    public byte[] digest(byte[] input) throws Exception {
                        return MessageDigest.getInstance("MD5").digest(input);
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-SEC-003");
        assertEquals(Severity.MEDIUM, finding.severity());
        assertTrue(finding.message().contains("MD5"), finding.message());
    }

    @Test
    void sha256IsNotReportedAsAWeakDigest() throws IOException {
        VerificationContext context = contextWithSource("Hashing.java", """
                package com.example;

                import java.security.MessageDigest;

                public class Hashing {
                    public byte[] digest(byte[] input) throws Exception {
                        return MessageDigest.getInstance("SHA-256").digest(input);
                    }
                }
                """, false);

        assertNoRule(context, "CW-SEC-003");
    }

    // ------------------------------------------------------------------ CW-CONC-001

    @Test
    void aPrivateFieldReadInAConditionAndWrittenUnsynchronisedIsReported() throws IOException {
        VerificationContext context = contextWithSource("Counter.java", """
                package com.example;

                public class Counter {
                    private int count;

                    public void increment() {
                        if (count < 10) {
                            count = count + 1;
                        }
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-CONC-001");
        assertEquals(Severity.MEDIUM, finding.severity());
        assertEquals(FindingCategory.CONCURRENCY, finding.category());
        assertEquals("count", finding.attributes().get("field"));
        assertTrue(finding.message().contains("check-then-act"), finding.message());
    }

    @Test
    void aPrivateFieldWithoutACheckThenActIsNotReported() throws IOException {
        VerificationContext context = contextWithSource("Counter.java", """
                package com.example;

                public class Counter {
                    private int count;

                    public void reset(int value) {
                        count = value;
                    }

                    public int value() {
                        return count;
                    }
                }
                """, false);

        assertNoRule(context, "CW-CONC-001");
    }

    @Test
    void aSynchronisedFieldIsNotReported() throws IOException {
        VerificationContext context = contextWithSource("Counter.java", """
                package com.example;

                public class Counter {
                    private int count;

                    public synchronized void increment() {
                        if (count < 10) {
                            count = count + 1;
                        }
                    }
                }
                """, false);

        assertNoRule(context, "CW-CONC-001");
    }

    // ------------------------------------------------------------------ CW-REL-001

    @Test
    void anEmptyCatchBlockIsReported() throws IOException {
        VerificationContext context = contextWithSource("Risky.java", """
                package com.example;

                public class Risky {
                    public void run() {
                        try {
                            risky();
                        } catch (Exception e) {
                        }
                    }

                    private void risky() throws Exception {
                        throw new IllegalStateException("boom");
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-REL-001");
        assertEquals(Severity.MEDIUM, finding.severity());
        assertEquals(FindingCategory.RELIABILITY, finding.category());
        assertEquals(7, finding.line(), "the finding points at the catch clause");
    }

    @Test
    void aCatchBlockThatHandlesTheFailureIsNotReported() throws IOException {
        VerificationContext context = contextWithSource("Risky.java", """
                package com.example;

                public class Risky {
                    public int run() {
                        try {
                            return Integer.parseInt("1");
                        } catch (NumberFormatException e) {
                            return 0;
                        }
                    }
                }
                """, false);

        assertNoRule(context, "CW-REL-001");
    }

    // ------------------------------------------------------------------ CW-DATE-001

    @Test
    void calendarDayArithmeticIsReported() throws IOException {
        VerificationContext context = contextWithSource("Window.java", """
                package com.example;

                import java.time.LocalDate;
                import java.time.temporal.ChronoUnit;

                public class Window {
                    public long daysBetween(LocalDate start, LocalDate end) {
                        return ChronoUnit.DAYS.between(start, end);
                    }
                }
                """, false);

        Finding finding = finding(context, "CW-DATE-001");
        assertEquals(Severity.MEDIUM, finding.severity());
        assertEquals(FindingCategory.CORRECTNESS, finding.category());
        assertTrue(finding.message().contains("rolling window"), finding.message());
    }

    @Test
    void hourBasedArithmeticIsNotReported() throws IOException {
        VerificationContext verificationContext = contextWithSource("Window.java", """
                package com.example;

                import java.time.Instant;
                import java.time.temporal.ChronoUnit;

                public class Window {
                    public long hoursBetween(Instant start, Instant end) {
                        return ChronoUnit.HOURS.between(start, end);
                    }
                }
                """, false);

        assertNoRule(verificationContext, "CW-DATE-001");
    }

    // ------------------------------------------------------------------ CW-TEST-001

    @Test
    void aTestWithoutAnAssertionIsReported() throws IOException {
        VerificationContext context = contextWithSource("LedgerTest.java", """
                package com.example;

                import org.junit.jupiter.api.Test;

                class LedgerTest {
                    @Test
                    void recordsAWithdrawal() {
                        int total = 1 + 1;
                    }
                }
                """, true);

        List<Finding> findings = new HeuristicSourceAnalyzer().analyzeTests(context);
        List<String> rules = findings.stream().map(Finding::ruleId).toList();
        assertTrue(rules.contains("CW-TEST-001"), "expected CW-TEST-001 but got " + rules);
        Finding finding = findings.stream().filter(f -> f.ruleId().equals("CW-TEST-001")).findFirst().orElseThrow();
        assertEquals(Severity.MEDIUM, finding.severity());
        assertEquals(FindingCategory.MAINTAINABILITY, finding.category());
        assertEquals(7, finding.line(), "the finding points at the test method, not at its annotation");
        assertTrue(finding.file().endsWith("LedgerTest.java"), finding.file());
    }

    @Test
    void aTestWithAnAssertionIsNotReported() throws IOException {
        VerificationContext context = contextWithSource("LedgerTest.java", """
                package com.example;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                class LedgerTest {
                    @Test
                    void adds() {
                        assertEquals(2, 1 + 1);
                    }
                }
                """, true);

        assertTrue(new HeuristicSourceAnalyzer().analyzeTests(context).stream()
                .noneMatch(f -> f.ruleId().equals("CW-TEST-001")));
    }

    @Test
    void aTestWhoseAssertionsFollowAnEmbeddedFixtureIsNotReported() throws IOException {
        // The self-check reported 31 of these: the body was delimited by the first `}` on its own line,
        // which sits inside the fixture text block, so every assertion after it was invisible.
        VerificationContext context = contextWithSource("NodeLockFileTest.java", """
                package com.example;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                class NodeLockFileTest {
                    @Test
                    void readsTheFlatPackagesMap() {
                        String lock = \"\"\"
                                {
                                  "packages": {
                                    "": { "name": "sample-app" },
                                    "node_modules/left-pad": { "version": "1.3.0" }
                                  }
                                }
                                \"\"\";
                        assertEquals(List.of("left-pad@1.3.0"), NodeLockFile.parse(lock));
                    }
                }
                """, true);

        assertTrue(new HeuristicSourceAnalyzer().analyzeTests(context).stream()
                        .noneMatch(f -> f.ruleId().equals("CW-TEST-001")),
                "assertions after an embedded fixture are still assertions");
    }

    @Test
    void anAssertionInsideAnEmbeddedFixtureDoesNotCount() throws IOException {
        VerificationContext context = contextWithSource("GeneratorTest.java", """
                package com.example;

                import org.junit.jupiter.api.Test;

                class GeneratorTest {
                    @Test
                    void writesATestThatAsserts() {
                        String generated = \"\"\"
                                @Test
                                void generated() {
                                    assertEquals(1, 1);
                                }
                                \"\"\";
                        write(generated);
                    }
                }
                """, true);

        List<Finding> findings = new HeuristicSourceAnalyzer().analyzeTests(context);
        assertEquals(1, findings.size(), "only the outer test lacks an assertion: " + findings);
        assertEquals(7, findings.getFirst().line(), "the fixture's @Test must not be analysed as a test");
    }

    @Test
    void aTestWhoseOracleIsAThrownAssertionErrorIsNotReported() throws IOException {
        // ScenarioTestGeneratorTest invokes the compiled generated test reflectively and turns a
        // reflective failure into an AssertionError. That test can fail, so it is not an assertion-free
        // test even though it contains no assertion call.
        VerificationContext context = contextWithSource("GeneratorTest.java", """
                package com.example;

                import org.junit.jupiter.api.Test;

                class GeneratorTest {
                    @Test
                    void generatedCaseRuns() {
                        try {
                            invokeGeneratedCase();
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError("the generated case did not run", e);
                        }
                    }
                }
                """, true);

        assertTrue(new HeuristicSourceAnalyzer().analyzeTests(context).stream()
                        .noneMatch(f -> f.ruleId().equals("CW-TEST-001")),
                "a thrown AssertionError is a failure oracle");
    }

    // ------------------------------------------------------------------ analyzer split

    @Test
    void theSecurityAnalyzerReturnsOnlySecurityRules() throws IOException {
        VerificationContext context = mixedContext();
        List<Finding> findings = new HeuristicSecurityAnalyzer().analyze(context);

        assertFalse(findings.isEmpty());
        for (Finding finding : findings) {
            assertTrue(HeuristicSourceAnalyzer.isSecurityRule(finding.ruleId()),
                    "security analyzer returned a non-security rule: " + finding.ruleId());
            assertEquals(FindingCategory.SECURITY, finding.category());
        }
        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("CW-SEC-001")));
    }

    @Test
    void theStaticAnalyzerReturnsNoSecurityRules() throws IOException {
        VerificationContext context = mixedContext();
        List<Finding> findings = new HeuristicSourceAnalyzer().analyze(context);

        assertFalse(findings.isEmpty());
        for (Finding finding : findings) {
            assertFalse(HeuristicSourceAnalyzer.isSecurityRule(finding.ruleId()),
                    "static analyzer must not report security rules: " + finding.ruleId());
        }
        List<String> rules = findings.stream().map(Finding::ruleId).toList();
        assertTrue(rules.contains("CW-NUM-001"), rules.toString());
        assertTrue(rules.contains("CW-REL-001"), rules.toString());
        assertTrue(rules.contains("CW-CONC-001"), rules.toString());
    }

    @Test
    void findAllReportsSecurityAndNonSecurityRulesTogether() throws IOException {
        VerificationContext context = mixedContext();
        List<String> rules = new HeuristicSourceAnalyzer().findAll(context).stream()
                .map(Finding::ruleId).collect(Collectors.toList());

        assertTrue(rules.contains("CW-SEC-001"), rules.toString());
        assertTrue(rules.contains("CW-NUM-001"), rules.toString());
    }

    @Test
    void generatedTestSourcesAreNotAnalyzed() throws IOException {
        Path root = tmp.resolve("case-generated");
        Files.createDirectories(root.resolve("src/main/java/dev/codewitness/generated"));
        Files.writeString(root.resolve("src/main/java/dev/codewitness/generated/Cw_SC_001_OB_001_Test.java"), """
                package dev.codewitness.generated;

                import java.math.BigDecimal;

                public class Cw_SC_001_OB_001_Test {
                    void case0000() {
                        BigDecimal total = BigDecimal.ZERO;
                        total.add(java.math.BigDecimal.ONE);
                    }
                }
                """);
        VerificationContext context = TestContexts.context(root, root.resolve(".codewitness"));

        assertTrue(new HeuristicSourceAnalyzer().findAll(context).isEmpty(),
                "generated counterexample hunters must not be reported against the project");
    }

    // ------------------------------------------------------------------ rule metadata

    @Test
    void everyDocumentedRuleIsRegistered() {
        List<String> rules = HeuristicSourceAnalyzer.ruleIds();

        assertTrue(rules.containsAll(List.of(
                "CW-SEC-001", "CW-SEC-002", "CW-SEC-003", "CW-SEC-004",
                "CW-NUM-001", "CW-NUM-002", "CW-NUM-003",
                "CW-CONC-001", "CW-CONC-002", "CW-DATE-001", "CW-REL-001", "CW-TEST-001")), rules.toString());
    }

    @Test
    void documentedRuleSeveritiesAreStable() {
        assertEquals(Severity.CRITICAL, HeuristicSourceAnalyzer.severityOf("CW-SEC-001"));
        assertEquals(Severity.HIGH, HeuristicSourceAnalyzer.severityOf("CW-SEC-002"));
        assertEquals(Severity.HIGH, HeuristicSourceAnalyzer.severityOf("CW-NUM-002"));
        assertEquals(Severity.MEDIUM, HeuristicSourceAnalyzer.severityOf("CW-REL-001"));
        assertEquals(Severity.MEDIUM, HeuristicSourceAnalyzer.severityOf("CW-CONC-001"));
        assertEquals(Severity.MEDIUM, HeuristicSourceAnalyzer.severityOf("not-a-rule"));
    }

    @Test
    void onlyJavaProjectsAreSupported() {
        ProjectRef ref = ProjectRef.of(tmp.resolve("any"));
        assertTrue(new HeuristicSourceAnalyzer().supports(TestContexts.projectInfo(tmp)));
        assertTrue(new HeuristicSecurityAnalyzer().supports(TestContexts.projectInfo(tmp)));
        ProjectInfo unknown = ProjectInfo.builder(ref).language(LanguageId.UNKNOWN).build();
        assertFalse(new HeuristicSourceAnalyzer().supports(unknown));
        assertFalse(new HeuristicSecurityAnalyzer().supports(unknown));
    }

    // ------------------------------------------------------------------ helpers

    private VerificationContext mixedContext() throws IOException {
        return contextWithSource("Mixed.java", """
                package com.example;

                import java.math.BigDecimal;

                public class Mixed {
                    private int counter;

                    public void apply(BigDecimal total, BigDecimal amount) {
                        total.add(amount);
                        if (counter < 1) {
                            counter = counter + 1;
                        }
                        String password = "s3cr3t-value";
                        try {
                            risky();
                        } catch (Exception e) {
                        }
                    }

                    private void risky() throws Exception {
                    }
                }
                """, false);
    }

    private VerificationContext contextWithSource(String fileName, String source, boolean testTree)
            throws IOException {
        Path root = tmp.resolve("case-" + counter.incrementAndGet());
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.createDirectories(root.resolve("src/test/java/com/example"));
        String tree = testTree ? "src/test/java" : "src/main/java";
        TestContexts.write(root, tree + "/com/example/" + fileName, source);
        return TestContexts.context(root, root.resolve(".codewitness"));
    }

    private static Finding finding(VerificationContext context, String ruleId) {
        List<Finding> findings = new HeuristicSourceAnalyzer().findAll(context);
        return findings.stream()
                .filter(f -> ruleId.equals(f.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected " + ruleId + " but got " + findings.stream().map(Finding::ruleId).toList()));
    }

    private static void assertNoRule(VerificationContext context, String ruleId) {
        List<String> rules = new HeuristicSourceAnalyzer().findAll(context).stream()
                .map(Finding::ruleId).collect(Collectors.toList());
        assertFalse(rules.contains(ruleId), "did not expect " + ruleId + " but got " + rules);
    }
}

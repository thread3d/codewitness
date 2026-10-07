package dev.codewitness.kotlin.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.kotlin.KotlinTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule table, one rule at a time.
 *
 * <p>Two properties are being pinned. A rule fires on the Kotlin spelling of the defect it describes, and
 * it does not fire on text that only looks like code - which is what the two source projections exist for.
 * The second half matters more: a rule table whose false positives are not tested is a rule table nobody
 * should trust.
 */
class KotlinHeuristicAnalyzerTest {

    @TempDir
    Path tmp;

    private final KotlinHeuristicAnalyzer analyzer = new KotlinHeuristicAnalyzer();

    @Test
    @DisplayName("a hardcoded credential is CRITICAL and a security finding")
    void findsHardcodedCredentials() throws IOException {
        List<Finding> findings = analyze("""
                package example

                class Config {
                    private val apiKey = "sk-live-0123456789"
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-SEC-001").size(), findings.toString());
        assertEquals(Severity.CRITICAL, finding(findings, "CW-KT-SEC-001").severity());
        assertFalse(finding(findings, "CW-KT-SEC-001").fromDeterministicTool(),
                "a heuristic finding must be advisory, never deterministic proof");

    }

    @Test
    @DisplayName("the static-analysis half of the table excludes the security rules by contract")
    void analyzeExcludesSecurityRules() throws IOException {
        Path root = KotlinTestContexts.project(Files.createDirectories(tmp.resolve("split-" + counter())));
        KotlinTestContexts.write(root, "src/main/kotlin/example/Sample.kt", """
                package example

                class Config {
                    private val password = "hunter2-secret"
                    val balance: Double = 0.0
                }
                """);
        VerificationContext context = KotlinTestContexts.context(root, root.resolve(".codewitness"));

        assertTrue(analyzer.analyze(context).stream()
                        .noneMatch(finding -> KotlinHeuristicAnalyzer.isSecurityRule(finding.ruleId())),
                "the security phase reports those, so reporting them twice would double-count them");
        assertTrue(analyzer.findAll(context).stream()
                        .anyMatch(finding -> KotlinHeuristicAnalyzer.isSecurityRule(finding.ruleId())));
    }

    @Test
    @DisplayName("a commented-out credential is not a finding")
    void ignoresCommentedOutCode() throws IOException {
        List<Finding> findings = analyze("""
                package example

                class Config {
                    // private val apiKey = "sk-live-0123456789"
                }
                """);

        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("a weak digest is reported")
    void findsWeakDigests() throws IOException {
        List<Finding> findings = analyze("""
                package example

                import java.security.MessageDigest

                class Digest {
                    fun hash(): ByteArray = MessageDigest.getInstance("MD5").digest()
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-SEC-002").size(), findings.toString());
    }

    @Test
    @DisplayName("process execution with a computed command is reported")
    void findsComputedCommandExecution() throws IOException {
        List<Finding> findings = analyze("""
                package example

                class Runner {
                    fun run(command: String) {
                        ProcessBuilder(command).start()
                    }
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-SEC-003").size(), findings.toString());
        assertEquals(Severity.HIGH, finding(findings, "CW-KT-SEC-003").severity());
    }

    @Test
    @DisplayName("a discarded BigDecimal result is HIGH: the bug is silent")
    void findsDiscardedImmutableResult() throws IOException {
        List<Finding> findings = analyze("""
                package example

                import java.math.BigDecimal

                class Ledger {
                    private var total: BigDecimal = BigDecimal.ZERO

                    fun applyFee(fee: BigDecimal) {
                        total.add(fee)
                    }
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-NUM-001").size(), findings.toString());
        assertEquals(Severity.HIGH, finding(findings, "CW-KT-NUM-001").severity());
    }

    @Test
    @DisplayName("collecting into a mutable list is not a discarded immutable result")
    void aCollectionAddIsNotDiscardedValue() throws IOException {
        List<Finding> findings = analyze("""
                package example

                import java.math.BigDecimal

                class Ledger {
                    private val amounts = ArrayList<BigDecimal>()

                    fun record(amount: BigDecimal) {
                        amounts.add(amount)
                    }
                }
                """);

        assertTrue(ruleIds(findings, "CW-KT-NUM-001").isEmpty(),
                "a mutable collection's add is not a lost update: " + findings);
    }

    @Test
    @DisplayName("Double used for money is reported, and so is a BigDecimal compared as a Double would be")
    void findsFloatingPointMoneyAndScaleComparison() throws IOException {
        List<Finding> findings = analyze("""
                package example

                import java.math.BigDecimal

                class Ledger {
                    val balance: Double = 0.0

                    fun same(other: BigDecimal): Boolean {
                        val total: BigDecimal = BigDecimal.ZERO
                        return total.equals(other)
                    }
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-NUM-002").size(), findings.toString());
        assertEquals(1, ruleIds(findings, "CW-KT-NUM-003").size(), findings.toString());
    }

    @Test
    @DisplayName("calendar day arithmetic is flagged so it gets checked against the requirement")
    void findsCalendarDayArithmetic() throws IOException {
        List<Finding> findings = analyze("""
                package example

                import java.time.LocalDate

                class Ledger {
                    fun day(): LocalDate = LocalDate.now()
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-DATE-001").size(), findings.toString());
    }

    @Test
    @DisplayName("an empty catch block and a swallowed runCatching are both reported")
    void findsSwallowedFailures() throws IOException {
        List<Finding> findings = analyze("""
                package example

                class Loader {
                    fun read(): String? {
                        try {
                            return java.io.File("x").readText()
                        } catch (e: Exception) {
                        }
                    }

                    fun readQuietly(): String? = runCatching { java.io.File("x").readText() }.getOrNull()
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-REL-001").size(), findings.toString());
        assertEquals(1, ruleIds(findings, "CW-KT-REL-002").size(), findings.toString());
        assertEquals(Severity.LOW, finding(findings, "CW-KT-REL-002").severity(),
                "a defaulted runCatching is a hazard, but it is not always a defect");
    }

    @Test
    @DisplayName("an unsynchronised check-then-act on a Kotlin property is reported")
    void findsCheckThenAct() throws IOException {
        List<Finding> findings = analyze("""
                package example

                import java.math.BigDecimal

                class Ledger {
                    private var concurrentTotal: BigDecimal = BigDecimal.ZERO

                    fun withdraw(amount: BigDecimal): Boolean {
                        if (concurrentTotal.add(amount).compareTo(BigDecimal("1000")) > 0) {
                            return false
                        }
                        concurrentTotal = concurrentTotal.add(amount)
                        return true
                    }
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-CONC-001").size(), findings.toString());
    }

    @Test
    @DisplayName("a test with no assertion passes whatever the code does, and is reported")
    void findsTestsThatCannotFail() throws IOException {
        List<Finding> findings = analyzeTests("""
                package example

                import org.junit.jupiter.api.Test

                class LedgerTest {
                    @Test
                    fun doesNotThrow() {
                        Ledger().applyFee(java.math.BigDecimal("1"))
                    }
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-TEST-001").size(), findings.toString());
    }

    @Test
    @DisplayName("a test that asserts something is not reported")
    void aTestWithAnAssertionIsFine() throws IOException {
        List<Finding> findings = analyzeTests("""
                package example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class LedgerTest {
                    @Test
                    fun records() {
                        val ledger = Ledger()
                        assertEquals(1, ledger.size())
                    }
                }
                """);

        assertTrue(ruleIds(findings, "CW-KT-TEST-001").isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("a test inside a string is not a test, and an assertion inside a string is not an assertion")
    void literalsDoNotConfuseTheTestRule() throws IOException {
        List<Finding> findings = analyzeTests("""
                package example

                import org.junit.jupiter.api.Test

                class LedgerTest {
                    @Test
                    fun writesASnippet() {
                        val source = "assertEquals(1, 1)"
                        java.io.File("x").writeText(source)
                    }
                }
                """);

        assertEquals(1, ruleIds(findings, "CW-KT-TEST-001").size(),
                "the assertion is inside a string, so the test still asserts nothing: " + findings);
    }

    @Test
    @DisplayName("the analyzer only speaks for Kotlin, and reports the rules it documents")
    void supportsOnlyKotlin() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("supported"));

        assertTrue(analyzer.supports(KotlinTestContexts.projectInfo(root)));
        assertFalse(analyzer.supports(dev.codewitness.core.model.ProjectInfo
                .builder(dev.codewitness.core.model.ProjectRef.of(root))
                .language(dev.codewitness.core.model.LanguageId.JAVA)
                .build()));
        assertEquals("kotlin-heuristics", analyzer.id());
        assertEquals(12, KotlinHeuristicAnalyzer.ruleIds().size(), KotlinHeuristicAnalyzer.ruleIds().toString());
        assertEquals(12, KotlinHeuristicAnalyzer.ruleIds().stream().distinct().count(),
                "every rule id is distinct: " + KotlinHeuristicAnalyzer.ruleIds());
    }

    @Test
    @DisplayName("the security analyzer reports exactly the security rules")
    void theSecurityAnalyzerReportsOnlySecurityRules() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("security"));
        KotlinTestContexts.write(root, "src/main/kotlin/Config.kt", """
                package example

                class Config {
                    private val password = "hunter2-secret"
                    val balance: Double = 0.0
                }
                """);
        VerificationContext context = KotlinTestContexts.context(root, root.resolve(".codewitness"));

        List<Finding> findings = new KotlinSecurityAnalyzer().analyze(context);

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).ruleId().startsWith("CW-KT-SEC"), findings.get(0).ruleId());
        assertTrue(new KotlinSecurityAnalyzer().supports(context.projectInfo()));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @return every finding the rule table produces for a file, security rules included.
     *
     * <p>{@code findAll}, not {@code analyze}: the latter is the static-analysis half and deliberately
     * excludes the security rules, which the security phase reports. A test that used it would report every
     * {@code CW-KT-SEC-*} rule as broken.
     */
    private List<Finding> analyze(String source) throws IOException {
        Path root = KotlinTestContexts.project(Files.createDirectories(tmp.resolve("case-" + counter())));
        KotlinTestContexts.write(root, "src/main/kotlin/example/Sample.kt", source);
        return analyzer.findAll(KotlinTestContexts.context(root, root.resolve(".codewitness")));
    }

    private List<Finding> analyzeTests(String source) throws IOException {
        Path root = KotlinTestContexts.project(Files.createDirectories(tmp.resolve("tests-" + counter())));
        KotlinTestContexts.write(root, "src/test/kotlin/example/SampleTest.kt", source);
        return analyzer.analyzeTests(KotlinTestContexts.context(root, root.resolve(".codewitness")));
    }

    private static int counter = 0;

    private static synchronized int counter() {
        return counter++;
    }

    private static List<Finding> ruleIds(List<Finding> findings, String ruleId) {
        return findings.stream().filter(finding -> ruleId.equals(finding.ruleId())).toList();
    }

    private static Finding finding(List<Finding> findings, String ruleId) {
        return ruleIds(findings, ruleId).getFirst();
    }
}

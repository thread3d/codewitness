package dev.codewitness.php.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.php.PhpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendering of declared scenarios as PHPUnit test classes.
 *
 * <p>The generator is tested by reading the PHP it produces. Nothing here needs PHP or PHPUnit: the
 * contract is the source text, and a test that had to run a suite to check a class name would be testing
 * the machine.
 */
class PhpScenarioTestGeneratorTest {

    @Test
    @DisplayName("a declared scenario becomes a PHPUnit TestCase with a structured counterexample protocol")
    void rendersScenario(@TempDir Path root, @TempDir Path run) {
        Scenario scenario = rolling();
        Requirement requirement = Requirement.builder("R-001", "rolling limit")
                .title("Rolling withdrawal limit").build();
        VerificationObligation obligation = obligation("OB-001", "SC-ROLLING");
        VerificationContext context = context(root, run, scenario);

        List<GeneratedTest> generated = new PhpScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("CwScSC_ROLLINGTest", test.className(),
                "the class is named after the scenario, and a hyphen cannot appear in a PHP identifier");
        assertEquals(List.of("OB-001"), test.obligationIds());
        assertEquals(Long.valueOf(42L), test.seed());
        assertTrue(test.checksInvariant(), "an observation and an invariant are what make the test an oracle");
        String source = test.sourceCode();
        assertTrue(source.startsWith("<?php\n"), source);
        assertTrue(source.contains("final class CwScSC_ROLLINGTest extends \\PHPUnit\\Framework\\TestCase"),
                source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), source);
        assertTrue(source.contains("$ledger->withdraw($t1, $a1);"),
                "the placeholder must become the PHP variable the fragment names: " + source);
        assertTrue(source.contains("$observation = $ledger->totalWithinLast24Hours($t2);"), source);
        assertTrue(source.contains("if (!($observation <= 100000)) {"), source);
        assertTrue(source.contains("self::cw_int(0, 172800000)"), source);
        assertTrue(source.contains("public function test_case0000(): void"), source);
        assertTrue(source.contains("public function test_random_search(): void"), source);
        assertTrue(source.contains(
                        "private function run_case(int $cwCase, ?int $cwSeed, array $cwInputs, $t1, $a1, $t2, $a2)"),
                source);
        assertTrue(source.contains("'t1' => $t1"), source);
        assertTrue(source.contains("self::fail(self::cw_protocol("),
                "a failing case must fail through PHPUnit's own assertion, or the report shows an error: " + source);
        assertTrue(source.contains("$this->addToAssertionCount(1);"),
                "a case that evaluated the invariant must not be reported as a risky test with no assertions: "
                        + source);
        assertFalse(source.contains("${"), "no placeholder may survive into generated code:\n" + source);
    }

    @Test
    @DisplayName("the generated class is valid PHP and needs no namespace, import or closing tag")
    void rendersParsablePhp(@TempDir Path root, @TempDir Path run) {
        VerificationContext context = context(root, run, rolling());

        String source = new PhpScenarioTestGenerator()
                .generate(context, Requirement.builder("R-001", "rolling limit").build(),
                        List.of(obligation("OB-001", "SC-ROLLING")))
                .getFirst().sourceCode();

        assertFalse(source.contains("?>"), "a closing tag would let a stray newline reach the output: " + source);
        assertFalse(source.contains("namespace "),
                "a generated class in the global namespace is reported under the name the engine derives: " + source);
        assertTrue(source.contains("private const CW_REQUIREMENT = 'R-001';"), source);
        assertTrue(source.contains("private const CW_OBLIGATIONS = 'OB-001';"), source);
        assertTrue(source.contains("private const CW_INVARIANT = '$observation <= 100000';"),
                "the invariant is recorded verbatim, in single quotes, so no PHP variable interpolates: " + source);
        assertEquals(countOccurrences(source, "\n    public function test_"),
                countOccurrences(source, "$this->run_case("),
                "every test method must run exactly one case:\n" + source);
    }

    @Test
    @DisplayName("a concurrent scenario is executed sequentially, and the generated file says so")
    void rendersConcurrentScenarioWithItsLimit(@TempDir Path root, @TempDir Path run) {
        Scenario scenario = new Scenario(
                "SC-CONC", "R-004", "concurrent withdrawals", Scenario.Mode.CONCURRENT,
                "$ledger = new Ledger();",
                List.of(new Scenario.Step("$ledger->withdraw(${amount});", List.of(
                        new Scenario.Input("amount", "int", Scenario.Domain.INTEGER, List.of("200"),
                                "100", "300", 0)), "step")),
                null, null, 5, 7L, 60);
        VerificationContext context = context(root, run, scenario);

        GeneratedTest test = new PhpScenarioTestGenerator()
                .generate(context, Requirement.builder("R-004", "concurrency").build(),
                        List.of(obligation("OB-009", "SC-CONC")))
                .getFirst();

        assertEquals("CwScSC_CONCTest", test.className());
        assertEquals("test_concurrent_search", test.testMethod());
        assertFalse(test.checksInvariant(), "no invariant was declared, so the test asserts nothing");
        String source = test.sourceCode();
        assertTrue(source.contains("public function test_concurrent_search(): void"), source);
        assertTrue(source.contains("LIMIT: the scenario declares mode \"concurrent\""),
                "PHP's standard build has no threads, so the limitation must be visible where the test is: "
                        + source);
        assertTrue(source.contains("mt_srand($cwSeed + $cwCase);"), source);
        assertTrue(source.contains("private const CW_INVARIANT = '(no invariant declared)';"), source);
        assertFalse(source.contains("$this->addToAssertionCount(1);"),
                "a test with no oracle asserted nothing, and must be reported as such rather than dressed up");
    }

    @Test
    @DisplayName("inputs without boundary values still produce exactly one executed case")
    void rendersOneDefaultCaseWhenNoInputHasBoundaryValues(@TempDir Path root, @TempDir Path run) {
        Scenario scenario = new Scenario(
                "SC-DEFAULTS", "R-001", "inputs without boundary values", Scenario.Mode.SINGLE,
                "$ledger = new Ledger();",
                List.of(new Scenario.Step("$ledger->record(${label}, ${note});", List.of(
                        new Scenario.Input("label", "string", Scenario.Domain.STRING, List.of("explicit"),
                                null, null, 0),
                        new Scenario.Input("note", "string", Scenario.Domain.STRING, List.of(),
                                null, null, 0)), "record both")),
                null, null, 1, 1L, 60);
        VerificationContext context = context(root, run, scenario);

        GeneratedTest test = new PhpScenarioTestGenerator()
                .generate(context, Requirement.builder("R-001", "defaults").build(),
                        List.of(obligation("OB-007", "SC-DEFAULTS")))
                .getFirst();
        String source = test.sourceCode();

        assertEquals(1, countOccurrences(source, "\n    public function test_"),
                "an empty class reports nothing, so exactly one case must be emitted:\n" + source);
        assertEquals("test_case0000", test.testMethod());
        assertTrue(source.contains("public function test_case0000(): void"), source);
        assertTrue(source.contains("$label = 'explicit';"), "the first declared value is used when there is one:\n" + source);
        assertTrue(source.contains("$note = '';"), "the type default is used when there is none:\n" + source);
        assertTrue(source.contains("'label' => $label"), source);
        assertTrue(source.contains("'note' => $note"), source);
        assertFalse(source.contains("public function test_random_search"),
                "a SINGLE scenario with one case has nothing to search over");
        assertFalse(source.contains("public function test_single_case"), source);
        assertFalse(source.contains("${"), source);
    }

    @Test
    @DisplayName("a PHP type name is read when the scenario declares no domain")
    void phpTypeNamesWithoutADomainProducePhpLiterals(@TempDir Path root, @TempDir Path run) {
        // The configuration's domain table knows Java spellings, so `type: string` and `type: bool` arrive
        // here as RAW. A generated literal of `explicit` or `TRUE` would not parse, which is why the PHP
        // type name section 5a fixes is read as well.
        Scenario scenario = new Scenario(
                "SC-TYPES", "R-001", "php type names", Scenario.Mode.SEQUENCE,
                "$ledger = new Ledger();",
                List.of(new Scenario.Step("$ledger->record(${label}, ${flag}, ${price});", List.of(
                        new Scenario.Input("label", "string", Scenario.Domain.RAW, List.of(), null, null, 0),
                        new Scenario.Input("flag", "bool", Scenario.Domain.RAW, List.of(), null, null, 0),
                        new Scenario.Input("price", "float", Scenario.Domain.RAW, List.of("2.5"), null, null, 0)),
                        "record the three")),
                null, null, 4, 3L, 60);
        VerificationContext context = context(root, run, scenario);

        String source = new PhpScenarioTestGenerator()
                .generate(context, Requirement.builder("R-001", "types").build(),
                        List.of(obligation("OB-002", "SC-TYPES")))
                .getFirst().sourceCode();

        assertTrue(source.contains("$label = '';"), "a string type takes a string literal:\n" + source);
        assertTrue(source.contains("$flag = false;"), "a bool type takes a boolean literal:\n" + source);
        assertTrue(source.contains("$price = 2.5;"), "a float type takes a numeric literal:\n" + source);
        assertTrue(source.contains("$label = self::cw_string('cw-');"), source);
        assertTrue(source.contains("$flag = self::cw_bool();"), source);
    }

    @Test
    @DisplayName("one scenario declared for two obligations produces one class covering both")
    void oneScenarioProducesOneClassForEveryObligation(@TempDir Path root, @TempDir Path run) {
        VerificationContext context = context(root, run, rolling());

        List<GeneratedTest> generated = new PhpScenarioTestGenerator().generate(
                context, Requirement.builder("R-001", "rolling limit").build(),
                List.of(obligation("OB-001", "SC-ROLLING"), obligation("OB-002", "SC-ROLLING")));

        assertEquals(1, generated.size(),
                "two classes with one name would be written to one file, and one obligation would be lost");
        assertEquals(List.of("OB-001", "OB-002"), generated.getFirst().obligationIds());
        assertTrue(generated.getFirst().sourceCode().contains("private const CW_OBLIGATIONS = 'OB-001, OB-002';"),
                generated.getFirst().sourceCode());
    }

    @Test
    @DisplayName("an obligation that names no declared scenario produces no test")
    void unknownScenarioProducesNothing(@TempDir Path root, @TempDir Path run) {
        VerificationContext context = context(root, run, rolling());

        List<GeneratedTest> generated = new PhpScenarioTestGenerator().generate(
                context, Requirement.builder("R-009", "absent").build(),
                List.of(obligation("OB-009", "SC-NOT-DECLARED")));

        assertEquals(List.of(), generated);
    }

    @Test
    @DisplayName("the class name is a PHP identifier whatever the scenario id is")
    void classNamesAreValidPhpIdentifiers() {
        assertEquals("CwScSC_ROLLINGTest", PhpScenarioTestGenerator.classNameFor(rolling()));
        assertEquals("CwScSC_A_BTest", PhpScenarioTestGenerator.classNameFor(
                        new Scenario("SC-A_B", "R-001", "x", Scenario.Mode.SINGLE,
                                "$x = 1;", List.of(new Scenario.Step("$x;", List.of(), "step")), null, null, 1, 1L, 60)),
                "underscores replace the separators, so two ids cannot collapse into one class");
        assertEquals("Scenario", PhpScenarioTestGenerator.identifier(""),
                "an empty id still has to yield a usable identifier");
    }

    // ------------------------------------------------------------------ helpers

    private static VerificationContext context(Path root, Path run, Scenario scenario) {
        return PhpTestContexts.context(PhpTestContexts.projectInfo(root), run, List.of(scenario),
                new LocalProcessSandbox(List.of(root, run)));
    }

    /** The fixture's own scenario, reduced to the shape the DSL fixes. */
    private static Scenario rolling() {
        return new Scenario(
                "SC-ROLLING", "R-001", "two withdrawals across midnight", Scenario.Mode.SEQUENCE,
                "$ledger = new Ledger();",
                List.of(new Scenario.Step("$ledger->withdraw(${t1}, ${a1});", List.of(
                        new Scenario.Input("t1", "int", Scenario.Domain.INTEGER, List.of("85800000"),
                                "0", "172800000", 2),
                        new Scenario.Input("a1", "int", Scenario.Domain.INTEGER, List.of("70000"),
                                "100", "90000", 2)), "first step"),
                        new Scenario.Step("$ledger->withdraw(${t2}, ${a2});", List.of(
                                new Scenario.Input("t2", "int", Scenario.Domain.INTEGER, List.of("90000000"),
                                        null, null, 2),
                                new Scenario.Input("a2", "int", Scenario.Domain.INTEGER, List.of("70000"),
                                        null, null, 2)), "second step")),
                "$ledger->totalWithinLast24Hours(${t2})",
                "$observation <= 100000",
                200, 42L, 60);
    }

    private static VerificationObligation obligation(String id, String scenarioId) {
        return new VerificationObligation(id, "R-001", "execute scenario", ObligationKind.PROPERTY,
                ObligationOrigin.USER_DECLARED, scenarioId, Map.of("scenario", scenarioId), false);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}

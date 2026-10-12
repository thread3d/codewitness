package dev.codewitness.swift.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.swift.SwiftTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendering of declared scenarios as Swift XCTest classes.
 *
 * <p>The generator is tested by reading the Swift it produces. Nothing here needs a toolchain: the
 * contract is the source text, and what the tests are really about is the two things that make a
 * generated file attributable and replayable - the {@code <test module>.<class>} string XCTest reports it
 * under, and the {@code test}-prefixed method the counterexample's own {@code --filter} names.
 */
class SwiftScenarioTestGeneratorTest {

    @Test
    @DisplayName("a declared scenario becomes an XCTest class with a structured counterexample protocol")
    void rendersScenario(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        Scenario scenario = rolling();
        VerificationContext context = SwiftTestContexts.context(root, run, List.of(scenario));

        List<GeneratedTest> generated = new SwiftScenarioTestGenerator()
                .generate(context, requirement("R-001"), List.of(obligation("OB-001", "SC-ROLLING")));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("LedgerTests.CwScSC_ROLLINGTests", test.className(),
                "the recorded name is exactly the suite XCTest prints, which is what the engine matches on");
        assertEquals("CwScSC_ROLLINGTests", test.simpleName());
        assertEquals("testCwSearch", test.testMethod(),
                "a SEQUENCE scenario searches, so the search method is the one worth replaying");
        assertEquals(List.of("R-001"), test.requirementIds());
        assertEquals(List.of("OB-001"), test.obligationIds());
        assertEquals(Long.valueOf(42L), test.seed());
        assertTrue(test.checksInvariant(), "an observation and an invariant are what make the file an oracle");
        assertFalse(test.sourceCode().contains("${"), "no placeholder may survive into generated code");

        String source = test.sourceCode();
        assertTrue(source.contains(CounterexampleProtocol.MARKER),
                "the marker comes from the engine's own protocol, so the generated file cannot disagree "
                        + "with the decoder: " + source);
        assertTrue(source.contains("@testable import Ledger"),
                "the class imports the library target, not the test target it is written into: " + source);
        assertTrue(source.contains("import XCTest"), source);
        assertTrue(source.contains("final class CwScSC_ROLLINGTests: XCTestCase {"), source);
        assertTrue(source.contains("func testCwSearch() {"), source);
        assertTrue(source.contains("func testCwCase0000() {"),
                "the boundary cases are emitted with the XCTest-discoverable prefix: " + source);
        assertTrue(source.contains("let amount = 70000"), source);
        assertTrue(source.contains("ledger.withdraw(amount)"),
                "the ${...} placeholder becomes the Swift local the fragment names: " + source);
        assertTrue(source.contains("cwSeed(42)"), source);
        assertTrue(source.contains("cwCases(200)"), source);
        assertTrue(source.contains("if !(observation <= 100000)"), source);
    }

    @Test
    @DisplayName("testMethod is the search, the first boundary case, or the single case, by shape")
    void testMethodFollowsTheScenarioShape() {
        Scenario searched = rolling();
        Scenario firstCase = new Scenario(
                "SC-ONE", "R-001", "one boundary case", Scenario.Mode.SINGLE,
                "let ledger = Ledger()",
                List.of(new Scenario.Step("ledger.withdraw(${amount})", List.of(
                        new Scenario.Input("amount", "int", Scenario.Domain.INTEGER, List.of("70000"),
                                "100", "90000", 0)), "withdraw")),
                "ledger.total", "observation <= 100000", 1, 1L, 60);
        Scenario singleCase = new Scenario(
                "SC-EMPTY", "R-001", "no inputs", Scenario.Mode.SINGLE,
                "let ledger = Ledger()",
                List.of(new Scenario.Step("ledger.flush", List.of(), "flush")),
                "ledger.total", null, 1, 1L, 60);

        assertEquals("testCwSearch", SwiftScenarioTestGenerator.testMethod(searched));
        assertEquals("testCwCase0000", SwiftScenarioTestGenerator.testMethod(firstCase),
                "a single boundary case is the first generated case method");
        assertEquals("testCwSingleCase", SwiftScenarioTestGenerator.testMethod(singleCase),
                "a scenario with no inputs emits one single case rather than naming one that does not exist");
    }

    @Test
    @DisplayName("a scenario id becomes a Swift class name without letting two ids collide")
    void identifiersAreCompilableAndDistinct(@TempDir Path root) {
        assertEquals("CwScSC_ROLLINGTests", SwiftScenarioTestGenerator.simpleClassName(scenario("SC-ROLLING")));
        assertEquals("Scenario", SwiftScenarioTestGenerator.identifier(""),
                "an empty id still has to yield a usable identifier");
        assertEquals("SC_ROLLING", SwiftScenarioTestGenerator.identifier("SC-ROLLING"));
        assertEquals("SC_A_B", SwiftScenarioTestGenerator.identifier("SC-A_B"));
        assertEquals("SC_AB", SwiftScenarioTestGenerator.identifier("SC-AB"));
        assertNotEquals(SwiftScenarioTestGenerator.identifier("SC-A_B"),
                SwiftScenarioTestGenerator.identifier("SC-AB"),
                "a deletion would make SC-A_B and SC-AB the same class, and one requirement would be "
                        + "judged by the other's experiment");
    }

    @Test
    @DisplayName("the test module and the host module are two different facts, and both fall back to the layout")
    void moduleNamesComeFromTheDetectorOrTheLayout(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext detected = SwiftTestContexts.context(root, run, List.of(rolling()));

        assertEquals("LedgerTests", SwiftScenarioTestGenerator.testModule(detected),
                "XCTest reports a case under the test target's module");
        assertEquals("Ledger", SwiftScenarioTestGenerator.importModule(detected),
                "the class imports the library target, which is a different fact");
        assertEquals("LedgerTests.CwScSC_ROLLINGTests",
                SwiftScenarioTestGenerator.classNameFor(detected, rolling()));

        ProjectInfo fallbackInfo = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.SWIFT)
                .buildSystem(BuildSystemId.SWIFTPM)
                .sourceExtensions(Set.of("swift"))
                .addModule(new ModuleInfo("Fallback", root, "swiftpm",
                        List.of(root.resolve("Sources/Fallback")),
                        List.of(root.resolve("Tests/FallbackTests")), null))
                .build();
        VerificationContext fallback = SwiftTestContexts.context(fallbackInfo, run);

        assertEquals("FallbackTests", SwiftScenarioTestGenerator.testModule(fallback),
                "with no recorded attribute the test root's own name is the module");
        assertEquals("Fallback", SwiftScenarioTestGenerator.importModule(fallback));
        assertEquals("FallbackTests.CwScSC_ROLLINGTests",
                SwiftScenarioTestGenerator.classNameFor(fallback, rolling()));

        VerificationContext noLayout = SwiftTestContexts.context(
                ProjectInfo.builder(ProjectRef.of(root)).language(LanguageId.SWIFT).build(), run);

        assertNull(SwiftScenarioTestGenerator.testModule(noLayout));
        assertNull(SwiftScenarioTestGenerator.importModule(noLayout));        assertEquals("CwScSC_ROLLINGTests", SwiftScenarioTestGenerator.classNameFor(noLayout, rolling()),
                "a project with no named test target has no module to report, so the bare class name is "
                        + "the honest fallback");
    }

    @Test
    @DisplayName("the generated file is written where the recorded name says it is")
    void theFileIsWrittenIntoTheTestTarget(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = SwiftTestContexts.context(root, run);

        assertEquals(root.resolve("Tests/LedgerTests/Generated/CwScSC_ROLLINGTests.swift"),
                SwiftScenarioTestGenerator.fileFor(context, "CwScSC_ROLLINGTests"),
                "SwiftPM only compiles files inside a declared test target, so the path is not optional");
    }

    @Test
    @DisplayName("one scenario declared for two obligations produces one class covering both")
    void oneScenarioProducesOneFileForEveryObligation(@TempDir Path root, @TempDir Path run)
            throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = SwiftTestContexts.context(root, run, List.of(rolling()));

        List<GeneratedTest> generated = new SwiftScenarioTestGenerator().generate(
                context, requirement("R-001"),
                List.of(obligation("OB-001", "SC-ROLLING"), obligation("OB-002", "SC-ROLLING")));

        assertEquals(1, generated.size(),
                "two files with one name would be written to one path, and one obligation would be lost");
        assertEquals(List.of("OB-001", "OB-002"), generated.getFirst().obligationIds());
        assertTrue(generated.getFirst().sourceCode().contains("OB-001, OB-002"),
                generated.getFirst().sourceCode());
    }

    @Test
    @DisplayName("an unknown scenario or one that cannot be spelled as Swift produces no test")
    void unrenderableScenariosProduceNothing(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = SwiftTestContexts.context(root, run, List.of(rolling(),
                unrepresentable()));
        SwiftScenarioTestGenerator generator = new SwiftScenarioTestGenerator();

        assertEquals(List.of(), generator.generate(context, requirement("R-009"),
                List.of(obligation("OB-009", "SC-NOT-DECLARED"))),
                "an obligation that names no declared scenario has nothing to execute");
        assertEquals(List.of(), generator.generate(context, requirement("R-001"),
                List.of(obligation("OB-010", "SC-OPAQUE"))),
                "a class that would not compile must be skipped rather than emitted into the test target");
    }

    @Test
    @DisplayName("the generator names itself and claims only Swift projects")
    void namesItselfAndClaimsOnlySwift(@TempDir Path root) {
        SwiftScenarioTestGenerator generator = new SwiftScenarioTestGenerator();

        assertEquals("swift-scenarios", generator.id());
        assertTrue(generator.supports(SwiftTestContexts.projectInfo(root)));
        assertFalse(generator.supports(ProjectInfo.builder(ProjectRef.of(root))
                        .language(LanguageId.JAVA)
                        .build()),
                "a Java scenario is the Java generator's to render");
    }

    // ------------------------------------------------------------------ helpers

    private static Requirement requirement(String id) {
        return Requirement.builder(id, "declared scenario").title("Rolling withdrawal limit").build();
    }

    private static VerificationObligation obligation(String id, String scenarioId) {
        return new VerificationObligation(id, "R-001", "execute scenario", ObligationKind.PROPERTY,
                ObligationOrigin.USER_DECLARED, scenarioId, Map.of("scenario", scenarioId), false);
    }

    /** The fixture scenario: one integer input with an explicit and a range-derived boundary. */
    private static Scenario rolling() {
        return new Scenario(
                "SC-ROLLING", "R-001", "one withdrawal", Scenario.Mode.SEQUENCE,
                "let ledger = Ledger()",
                List.of(new Scenario.Step("ledger.withdraw(${amount})", List.of(
                        new Scenario.Input("amount", "int", Scenario.Domain.INTEGER, List.of("70000"),
                                "100", "90000", 0)), "withdraw")),
                "ledger.total", "observation <= 100000",
                200, 42L, 60);
    }

    /** A scenario whose input this generator cannot spell as a Swift literal. */
    private static Scenario unrepresentable() {
        return new Scenario(
                "SC-OPAQUE", "R-001", "an opaque input", Scenario.Mode.SINGLE,
                "let ledger = Ledger()",
                List.of(new Scenario.Step("ledger.consume(${payload})", List.of(
                        new Scenario.Input("payload", "Opaque", Scenario.Domain.NULL, List.of(),
                                null, null, 0)), "consume")),
                null, null, 1, 1L, 60);
    }

    private static Scenario scenario(String id) {
        return new Scenario(id, "R-001", "x", Scenario.Mode.SINGLE,
                "let ledger = Ledger()",
                List.of(new Scenario.Step("ledger.flush", List.of(), "flush")),
                null, null, 1, 1L, 60);
    }
}

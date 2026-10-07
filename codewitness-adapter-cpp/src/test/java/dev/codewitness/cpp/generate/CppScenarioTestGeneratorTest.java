package dev.codewitness.cpp.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.cpp.CppTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the generated C++ scenario test and the command that builds it.
 *
 * <p>The generated source is asserted as text because it is the artefact a reader replays a counterexample
 * from: the includes that make the scenario's types resolve, the exact call the scenario declared, the
 * invariant check and the protocol line all have to be there. The commands are asserted as argv for the same
 * reason the other adapters' commands are: they are the interface between CodeWitness and the toolchain, and
 * they are the cheapest thing to check.
 */
class CppScenarioTestGeneratorTest {

    private static final Requirement REQUIREMENT =
            Requirement.builder("R-001", "A customer may withdraw at most 1,000 during any rolling 24-hour "
                    + "period.").title("Rolling withdrawal limit").build();

    private static Scenario rollingScenario() {
        Scenario.Input first = new Scenario.Input("t1", "long long", Scenario.Domain.INTEGER,
                List.of("85800000"), "0", "172800000", 0);
        Scenario.Input amount = new Scenario.Input("a1", "long long", Scenario.Domain.INTEGER,
                List.of("70000"), "10000", "90000", 0);
        Scenario.Input second = new Scenario.Input("t2", "long long", Scenario.Domain.INTEGER,
                List.of("90000000"), null, null, 0);
        return new Scenario("SC-ROLLING", "R-001", "Two withdrawals across midnight",
                Scenario.Mode.SEQUENCE, "auto l = ledger::WithdrawalLedger();",
                List.of(new Scenario.Step("l.withdraw(${t1}, ${a1})", List.of(first, amount), "first"),
                        new Scenario.Step("l.withdraw(${t2}, 70000)", List.of(second), "second")),
                "l.totalWithinLast24Hours(${t2})", "observation <= 100000", 200, 42, 60);
    }

    private static VerificationObligation obligation() {
        return new VerificationObligation("OB-001", "R-001", "Two withdrawals across midnight must respect "
                + "the rolling limit", ObligationKind.BOUNDARY_VALUE, ObligationOrigin.USER_DECLARED, null,
                Map.of("scenario", "SC-ROLLING"), false);
    }

    /** @return a context for the fixture-shaped project, with the rolling scenario declared in it. */
    private static VerificationContext context(Path project, Path run) throws IOException {
        CppTestContexts.fixture(project, run);
        return CppTestContexts.context(project, run, List.of(rollingScenario()));
    }

    /** @return the one generated test the generator produces for the rolling scenario obligation. */
    private static GeneratedTest generated(Path project, Path run) throws IOException {
        List<GeneratedTest> tests =
                new CppScenarioTestGenerator().generate(context(project, run), REQUIREMENT, List.of(obligation()));
        assertEquals(1, tests.size());
        return tests.getFirst();
    }

    @Test
    @DisplayName("a scenario obligation becomes one named C++ program that asserts the declared invariant")
    void generatesAProgram(@TempDir Path project, @TempDir Path run) throws IOException {
        GeneratedTest test = generated(project, run);

        assertEquals("CwScRollingOb001", test.className());
        assertEquals(List.of("R-001"), test.requirementIds());
        assertEquals(List.of("OB-001"), test.obligationIds());
        assertTrue(test.checksInvariant(), "the scenario declares both an observation and an invariant");
        assertEquals(42L, test.seed());

        String source = test.sourceCode();
        assertTrue(source.contains("#include \"include/ledger/withdrawal_ledger.hpp\""),
                "the generated file includes the project's own headers: " + source);
        assertTrue(source.contains("const char* const CwRequirement = \"R-001\";"), source);
        assertTrue(source.contains("const char* const CwObligation = \"OB-001\";"), source);
        assertTrue(source.contains("\"CODEWITNESS_COUNTEREXAMPLE\""), source);
        assertTrue(source.contains("l.withdraw(t1, a1);"), "the scenario's call is emitted verbatim: " + source);
        assertTrue(source.contains("const auto observation = l.totalWithinLast24Hours(t2);"), source);
        assertTrue(source.contains("if (!(observation <= 100000))"), source);
        assertTrue(source.contains("const long long t1 = 85800000;"),
                "a declared boundary value is a literal the scenario wrote: " + source);
        assertTrue(source.contains("int main(int argc, char** argv)"), source);
        assertTrue(source.contains("CODEWITNESS_SEED"), "a counterexample can be replayed by environment");
    }

    @Test
    @DisplayName("a project with no CMake file gets no generated test, so its requirements stay unverified")
    void refusesToGenerateWithoutABuild(@TempDir Path project, @TempDir Path run) throws IOException {
        CppTestContexts.write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        VerificationContext context = CppTestContexts.context(project, run, List.of(rollingScenario()));

        List<GeneratedTest> tests =
                new CppScenarioTestGenerator().generate(context, REQUIREMENT, List.of(obligation()));

        assertEquals(List.of(), tests,
                "there would be no compile database to compile the test with, so nothing is generated");
    }

    @Test
    @DisplayName("an obligation that names no declared scenario produces nothing")
    void ignoresObligationsWithoutAScenario(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.context(project, run, List.of());
        VerificationObligation withoutScenario = new VerificationObligation("OB-002", "R-001", "no scenario",
                ObligationKind.MANUAL, ObligationOrigin.REQUIREMENT_TEXT, null, Map.of(), false);

        assertEquals(List.of(), new CppScenarioTestGenerator().generate(context, REQUIREMENT,
                List.of(withoutScenario)));
    }

    @Test
    @DisplayName("a scenario name and an obligation name become a stable identifier")
    void namesTheGeneratedProgram() {
        assertEquals("ScRolling", CppScenarioTestGenerator.camel("SC-ROLLING"));
        assertEquals("Ob001", CppScenarioTestGenerator.camel("OB-001"));
        assertEquals("Case", CppScenarioTestGenerator.camel("---"));
        assertEquals("CwScRollingOb001",
                CppScenarioTestGenerator.classNameFor(rollingScenario(), obligation()));

        Scenario concurrent = new Scenario("SC-ROLLING", "R-001", "concurrent", Scenario.Mode.CONCURRENT,
                null, List.of(new Scenario.Step("l.withdraw(0, 1)", List.of(), null)), null, null, 8, 1, 60);
        assertEquals("CwScRollingOb001Concurrent",
                CppScenarioTestGenerator.classNameFor(concurrent, obligation()));
    }

    @Test
    @DisplayName("only the headers, relative to the project, are included")
    void collectsProjectHeaders(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);
        CppTestContexts.write(project, "include/ledger/fee.hpp", "#pragma once\n");
        CppTestContexts.write(project, "src/private.cpp", "int x;\n");

        List<String> headers = CppScenarioTestGenerator.projectHeaders(context);

        assertTrue(headers.contains("include/ledger/withdrawal_ledger.hpp"), headers.toString());
        assertTrue(headers.contains("include/ledger/fee.hpp"), headers.toString());
        assertFalse(headers.contains("src/private.cpp"), "a source file is not a header: " + headers);
    }

    @Test
    @DisplayName("boundary cases are the product of the declared values, capped")
    void enumeratesBoundaryCases() {
        Scenario.Input one = new Scenario.Input("a", "long long", Scenario.Domain.INTEGER,
                List.of("1", "2"), null, null, 0);
        Scenario.Input two = new Scenario.Input("b", "long long", Scenario.Domain.INTEGER,
                List.of("3", "4"), null, null, 0);

        List<Map<String, String>> cases = CppScenarioTestGenerator.boundaryCases(List.of(one, two));

        assertEquals(4, cases.size(), cases.toString());
        assertEquals(Map.of("a", "1", "b", "3"), cases.getFirst());

        Scenario.Input unbounded = new Scenario.Input("c", "long long", Scenario.Domain.INTEGER,
                List.of(), null, null, 0);
        assertEquals(List.of(), CppScenarioTestGenerator.boundaryCases(List.of(one, unbounded)),
                "an input with no declared boundary value means there is nothing to enumerate");
        assertEquals(List.of(), CppScenarioTestGenerator.boundaryCases(List.of()));
    }

    @Test
    @DisplayName("a fragment's inputs are substituted, and text is escaped so it cannot break the file")
    void substitutesAndEscapes() {
        Scenario.Input amount = new Scenario.Input("amount", "long long", Scenario.Domain.INTEGER,
                List.of(), "-10", "10", 0);

        assertEquals("l.withdraw(0, amount)",
                CppScenarioTestGenerator.substitute("l.withdraw(0, ${amount})", List.of(amount)));
        assertEquals("l.withdraw(0, ${missing})",
                CppScenarioTestGenerator.substitute("l.withdraw(0, ${missing})", List.of(amount)),
                "an input that is not declared is left alone rather than replaced with a name that is not in scope");
        assertEquals("\"a\\\\b\\\"c\\nd\"", CppScenarioTestGenerator.cppString("a\\b\"c\nd"));
        assertEquals("one two", CppScenarioTestGenerator.oneLine("  one\n\ttwo  "));
        assertEquals("a + b", CppScenarioTestGenerator.ensureExpression("a + b;"));
        assertEquals("l.withdraw(0, 1);", CppScenarioTestGenerator.ensureStatement("l.withdraw(0, 1)"));
        assertEquals("a(); b();", CppScenarioTestGenerator.ensureStatement("a(); b();"));
    }

    @Test
    @DisplayName("a generated class name becomes a file name no compiler will object to")
    void makesSafeIdentifiers() {
        assertEquals("CwScRollingOb001", CppGeneratedTestRunner.identifier("CwScRollingOb001"));
        assertEquals("Ledger_Test", CppGeneratedTestRunner.identifier("Ledger.Test"));
        assertEquals("CwGenerated", CppGeneratedTestRunner.identifier(""));
    }

    @Test
    @DisplayName("the compile and link commands use the project's own compiler, flags and archives")
    void buildsTheCommands(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);
        Path compiler = Path.of("/usr/bin/c++");
        Path source = project.resolve("CwA.cpp");
        Path object = project.resolve("CwA.o");
        Path executable = project.resolve("CwA");

        List<String> compile = CppGeneratedTestRunner.compileCommand(compiler, List.of("-I/include", "-O2"),
                source, object);
        assertEquals(List.of("/usr/bin/c++", "-I/include", "-O2", source.toString(), "-o", object.toString(),
                "-c"), compile);

        List<String> link = CppGeneratedTestRunner.linkCommand(compiler, object, List.of(project.resolve("libledger.a")),
                executable);
        assertEquals("/usr/bin/c++", link.getFirst());
        assertTrue(link.contains(object.toString()), link.toString());
        assertTrue(link.contains(project.resolve("libledger.a").toString()), link.toString());
        assertTrue(link.contains(executable.toString()), link.toString());

        List<String> flags = CppGeneratedTestRunner.flags(context, project.resolve("build"), null);
        assertTrue(flags.contains("-I" + project), "the project root is on the search path: " + flags);
    }

    @Test
    @DisplayName("a counterexample line is found wherever the test printed it")
    void findsTheCounterexample() {
        String output = """
                some noise
                CODEWITNESS_COUNTEREXAMPLE|case=0|seed=42|inputs={t1=1}|expected=x|actual=140000
                more noise
                """;

        assertEquals("CODEWITNESS_COUNTEREXAMPLE|case=0|seed=42|inputs={t1=1}|expected=x|actual=140000",
                CppGeneratedTestRunner.counterexampleLine(output));
        assertEquals(null, CppGeneratedTestRunner.counterexampleLine("nothing here"));
        assertEquals(null, CppGeneratedTestRunner.counterexampleLine(null));
    }

    @Test
    @DisplayName("the replay command runs the kept executable with the counterexample's case pinned")
    void replaysACounterexample(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);
        GeneratedTest test = generated(project, run);

        List<String> command = new CppGeneratedTestRunner().reproductionCommand(context, List.of(test), 42L, 0);

        assertTrue(command.contains("CODEWITNESS_SEED=42"), command.toString());
        assertTrue(command.contains("CODEWITNESS_CASE=0"), command.toString());
        assertTrue(command.getLast().endsWith("CwScRollingOb001"), command.toString());
        assertEquals(List.of(), new CppGeneratedTestRunner().reproductionCommand(context, List.of(), 42L, 0));
    }

    @Test
    @DisplayName("the executor names itself and claims C and C++ projects")
    void identifiesItself(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);
        CppGeneratedTestRunner runner = new CppGeneratedTestRunner();

        assertEquals("cpp-generated-tests", runner.id());
        assertTrue(runner.supports(context.projectInfo()));
        assertEquals("cpp-scenario-generator", new CppScenarioTestGenerator().id());
        assertTrue(new CppScenarioTestGenerator().supports(context.projectInfo()));
    }

    @Test
    @DisplayName("a batch with nothing in it runs nothing")
    void emptyBatchRunsNothing(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);

        var execution = new CppGeneratedTestRunner().execute(context, List.of());

        assertFalse(execution.result().executed());
        assertEquals(List.of(), execution.executed());
    }

    @Test
    @DisplayName("a batch is not compiled when the project has nothing to compile it against")
    void refusesToRunWithoutABuild(@TempDir Path project, @TempDir Path run) throws IOException {
        CppTestContexts.write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        VerificationContext context = CppTestContexts.context(project, run, List.of(rollingScenario()));
        GeneratedTest test = new GeneratedTest("CwFake", "int main() { return 0; }\n", null,
                List.of("R-001"), List.of("OB-001"), "a program that is never compiled", null, null, true);

        var execution = new CppGeneratedTestRunner().execute(context, List.of(test));

        assertFalse(execution.result().executed(), execution.note());
        assertTrue(execution.note().contains("could not be built"), execution.note());
        assertEquals(List.of(test), execution.executed());
    }
}

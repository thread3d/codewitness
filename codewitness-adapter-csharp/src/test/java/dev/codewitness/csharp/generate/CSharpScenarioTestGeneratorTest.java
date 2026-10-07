package dev.codewitness.csharp.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.csharp.CSharpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.codewitness.csharp.CSharpTestContexts.LIBRARY_PROJECT;
import static dev.codewitness.csharp.CSharpTestContexts.TEST_PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the xUnit scenario generator.
 *
 * <p>The generated file is checked by reading it: what matters is that it is a compilable xUnit class in
 * the test project's own namespace, that the scenario's fragments appear with their inputs substituted,
 * and that a violation reports through the structured line the engine parses. Rendering a scenario into
 * a runnable project is exercised end to end by the fixture.
 */
class CSharpScenarioTestGeneratorTest {

    private final CSharpScenarioTestGenerator generator = new CSharpScenarioTestGenerator();

    private Scenario rollingScenario() {
        Scenario.Input time = new Scenario.Input("t1", "long", Scenario.Domain.INTEGER,
                List.of("85800000"), "0", "172800000", 0);
        Scenario.Input amount = new Scenario.Input("a1", "long", Scenario.Domain.INTEGER,
                List.of("70000"), "10000", "90000", 0);
        Scenario.Step step = new Scenario.Step("l.Withdraw(${t1}, ${a1})", List.of(time, amount),
                "first withdrawal at 23:50");
        return new Scenario("SC-ROLLING", "R-001", "Two withdrawals across midnight", Scenario.Mode.SEQUENCE,
                "var l = new WithdrawalLedger();", List.of(step),
                "l.TotalWithinLast24Hours(${t1})", "observation <= 100000", 200, 42L, 60);
    }

    private VerificationContext project(Path project, Path run) throws IOException {
        CSharpTestContexts.write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        CSharpTestContexts.write(project, "src/Ledger/WithdrawalLedger.cs", "namespace Ledger;\n");
        CSharpTestContexts.write(project, "tests/Ledger.Tests/Ledger.Tests.csproj", TEST_PROJECT);
        CSharpTestContexts.write(project, "tests/Ledger.Tests/WithdrawalLedgerTests.cs", """
                using Xunit;

                namespace Ledger.Tests;

                public class WithdrawalLedgerTests
                {
                    // R-001 mentions the requirement, so the generated test is written beside it.
                    [Fact]
                    public void Passes() => Assert.Equal(1, 1);
                }
                """);
        return CSharpTestContexts.context(project, run, List.of(rollingScenario()));
    }

    @Test
    @DisplayName("a scenario renders as an xUnit class in the test project's own namespace")
    void rendersATest(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = project(project, run);
        Requirement requirement = Requirement.builder("R-001", "A customer must not exceed the limit.")
                .title("Rolling withdrawal limit").build();
        VerificationObligation obligation = VerificationObligation.of("OB-001", "R-001",
                "The rolling window must hold", ObligationKind.UPPER_BOUND, ObligationOrigin.USER_DECLARED)
                .withParameters(Map.of("scenario", "SC-ROLLING"));

        List<GeneratedTest> generated = generator.generate(context, requirement, List.of(obligation));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("Ledger.Tests.CwScRollingOb001", test.className());
        assertEquals("CwScRollingOb001", test.simpleName());
        assertTrue(test.checksInvariant(), "the scenario declares both an observation and an invariant");
        assertEquals(List.of("OB-001"), test.obligationIds());
        assertEquals(42L, test.seed());

        String source = test.sourceCode();
        assertTrue(source.contains("namespace Ledger.Tests;"), source);
        assertTrue(source.contains("using Ledger;"), "the production namespace is imported so the scenario "
                + "may name the project's own types unqualified");
        assertTrue(source.contains("using Xunit;"));
        assertTrue(source.contains("public sealed class CwScRollingOb001"));
        assertTrue(source.contains("[Fact]"));
        assertTrue(source.contains("var l = new WithdrawalLedger();"));
        assertTrue(source.contains("l.Withdraw(t1, a1);"), "inputs are substituted into the fragment");
        assertTrue(source.contains("observation <= 100000"));
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), "the protocol line is in the helpers");
        assertTrue(source.contains("public void Case0000()"), "the declared boundary values are enumerated");
        assertTrue(source.contains("public void RandomSearch()"), "a randomised search follows them");
        assertTrue(source.contains("CwCases(200L)"));
        assertEquals(-1, source.indexOf("${"), "no input placeholder survives substitution");
    }

    @Test
    @DisplayName("nothing is generated for a scenario when there is no test project to run it in")
    void generatesNothingWithoutATestProject(@TempDir Path project, @TempDir Path run) throws IOException {
        CSharpTestContexts.write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        CSharpTestContexts.write(project, "src/Ledger/WithdrawalLedger.cs", "namespace Ledger;\n");
        VerificationContext context = CSharpTestContexts.context(project, run, List.of(rollingScenario()));
        Requirement requirement = Requirement.builder("R-001", "description").build();
        VerificationObligation obligation = VerificationObligation.of("OB-001", "R-001", "description",
                ObligationKind.UPPER_BOUND, ObligationOrigin.USER_DECLARED)
                .withParameters(Map.of("scenario", "SC-ROLLING"));

        assertTrue(generator.generate(context, requirement, List.of(obligation)).isEmpty(),
                "a requirement stays unverified rather than blocked when nothing can run its test");
    }

    @Test
    @DisplayName("the generated identifiers are C# identifiers, whatever the ids look like")
    void rendersIdentifiers() {
        assertEquals("ScRolling", CSharpScenarioTestGenerator.camel("SC-ROLLING"));
        assertEquals("Ob001", CSharpScenarioTestGenerator.camel("OB-001"));
        assertEquals("Case", CSharpScenarioTestGenerator.camel("---"));
        assertEquals("\"a \\\"quoted\\\" value\"", CSharpScenarioTestGenerator.csharpString("a \"quoted\" value"));
        assertEquals("\"line\\nbreak\"", CSharpScenarioTestGenerator.csharpString("line\nbreak"));
    }

    @Test
    @DisplayName("the test project that mentions the requirement is the one the test is written into")
    void choosesTheDirectory(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = project(project, run);

        assertEquals(project.resolve("tests/Ledger.Tests"),
                CSharpScenarioTestGenerator.testDirectory(context, "R-001"));
        assertEquals("Ledger.Tests", CSharpScenarioTestGenerator.namespaceFor(project.resolve("tests/Ledger.Tests")));
        assertEquals("CodeWitness.Generated",
                CSharpScenarioTestGenerator.namespaceFor(project.resolve("tests/Nowhere")),
                "a test project in the global namespace still gets a namespace of its own");
    }
}

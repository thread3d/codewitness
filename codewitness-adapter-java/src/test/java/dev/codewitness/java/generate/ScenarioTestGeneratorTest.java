package dev.codewitness.java.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.java.TestContexts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generator produces the mechanical part of property based testing: boundary values, a seeded
 * random search, and the counterexample protocol. These tests pin the shape of the source and - most
 * importantly - prove that the source is valid Java, because a generated test that does not compile
 * turns every downstream phase into noise.
 */
class ScenarioTestGeneratorTest {

    @TempDir
    Path tmp;

    private final ScenarioTestGenerator generator = new ScenarioTestGenerator();

    @Test
    void sequenceScenarioProducesBoundaryCasesAndARandomSearch() throws IOException {
        GeneratedTest generated = generateSequence();

        assertEquals("dev.codewitness.generated.Cw_SC_001_OB_001_Test", generated.className());
        assertEquals("Cw_SC_001_OB_001_Test", generated.simpleName());
        assertEquals(List.of("R-001"), generated.requirementIds());
        assertEquals(List.of("OB-001"), generated.obligationIds());
        assertEquals("randomSearch", generated.testMethod());
        assertEquals(42L, generated.seed());

        String source = generated.sourceCode();
        assertTrue(source.startsWith("package dev.codewitness.generated;"), source.substring(0, 60));
        assertTrue(source.contains("public class Cw_SC_001_OB_001_Test"), "the class name must match the file name");
        assertTrue(source.contains("R-001"), "the requirement id must be recorded in the source");
        assertTrue(source.contains("OB-001"), "the obligation id must be recorded in the source");
        assertTrue(source.contains("\"observation >= 0\""), "the invariant string must be recorded");
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"),
                "the counterexample protocol marker must be emitted by the protocol helper");
    }

    @Test
    void sequenceScenarioHasOneTestPerBoundaryCase() throws IOException {
        String source = generateSequence().sourceCode();

        assertEquals(4, countOccurrences(source, "@org.junit.jupiter.api.Test"),
                "three boundary cases plus the seeded random search:\n" + source);
        assertTrue(source.contains("void case0000()"), source);
        assertTrue(source.contains("void case0001()"), source);
        assertTrue(source.contains("void case0002()"), source);
        assertTrue(source.contains("void randomSearch()"), source);
        assertTrue(source.contains("long seed = cwSeed(42L);"), "the declared seed must be baked in");
        assertTrue(source.contains("int cases = cwCases(10);"), "the declared case count must be baked in");
        assertTrue(source.contains("if (pinned >= 0 && cwCase != pinned) { continue; }"),
                "a single case must be replayable by index");
    }

    @Test
    void concurrentScenarioReleasesAllThreadsWithACountDownLatch() throws IOException {
        GeneratedTest generated = generateConcurrent();

        assertEquals("dev.codewitness.generated.Cw_SC_002_OB_002_ConcurrencyTest", generated.className());
        String source = generated.sourceCode();
        assertTrue(source.contains("void concurrentSearch()"), source);
        assertTrue(source.contains("java.util.concurrent.CountDownLatch cwStart = new java.util.concurrent.CountDownLatch(1);"),
                "threads must be released together");
        assertTrue(source.contains("cwStart.await();"), source);
        assertTrue(source.contains("cwStart.countDown();"), source);
        assertTrue(source.contains("Executors.newFixedThreadPool(8)"), source);
        assertTrue(source.contains("cwPool.shutdown();"), source);
    }

    @Test
    void generatedSequenceSourceCompiles() throws IOException {
        GeneratedTest generated = generateSequence();
        Path classes = compile(generated);
        assertTrue(Files.isRegularFile(classes.resolve("dev/codewitness/generated/Cw_SC_001_OB_001_Test.class")),
                "the generated class file must exist after compilation");
    }

    @Test
    void generatedConcurrentSourceCompiles() throws IOException {
        GeneratedTest generated = generateConcurrent();
        Path classes = compile(generated);
        assertTrue(Files.isRegularFile(
                        classes.resolve("dev/codewitness/generated/Cw_SC_002_OB_002_ConcurrencyTest.class")),
                "the generated class file must exist after compilation");
    }

    @Test
    void generatedBoundaryCaseRunsAndPasses() throws IOException {
        GeneratedTest generated = generateSequence();
        Path classes = compile(generated);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = Class.forName(generated.className(), true, loader);
            Object instance = type.getDeclaredConstructor().newInstance();
            var method = type.getDeclaredMethod("case0000");
            method.setAccessible(true);
            method.invoke(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the generated boundary case did not run: " + e, e);
        }
    }

    @Test
    void aScenarioWithNoSearchableInputsProducesExactlyOneSingleCase() throws IOException {
        Scenario scenario = new Scenario(
                "SC-003", "R-001", "a fixed sequence of calls is still an experiment",
                Scenario.Mode.SINGLE, null,
                List.of(new Scenario.Step("Math.abs(-1)", List.of(), "call something")),
                null, null, 1, 1L, 60);
        GeneratedTest generated = generate(scenario, obligation("OB-003", "SC-003"));

        String source = generated.sourceCode();
        assertEquals(1, countOccurrences(source, "@org.junit.jupiter.api.Test"), source);
        assertTrue(source.contains("void singleCase()"), source);
        assertFalse(source.contains("void randomSearch()"), "there is nothing to search over");
        assertFalse(source.contains("void case0000()"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"),
                "the counterexample protocol helper is always generated");
    }

    @Test
    void anObligationWithoutAScenarioProducesNothing() throws IOException {
        VerificationContext context = TestContexts.context(tmp.resolve("empty"), tmp.resolve("empty-run"));
        Requirement requirement = requirement();

        List<GeneratedTest> generated = generator.generate(
                context, requirement, List.of(obligation("OB-404", "SC-missing")));

        assertTrue(generated.isEmpty(), "a scenario declared in the config is required to generate anything");
    }

    @Test
    void theGeneratedHelperIsOnlyProducedForJavaProjects() {
        assertTrue(generator.supports(TestContexts.projectInfo(tmp)));
        assertEquals("java-scenario-generator", generator.id());
    }

    // ------------------------------------------------------------------ helpers

    private GeneratedTest generateSequence() throws IOException {
        return generate(sequenceScenario(), obligation("OB-001", "SC-001"));
    }

    private GeneratedTest generateConcurrent() throws IOException {
        Scenario scenario = new Scenario(
                "SC-002", "R-001", "concurrent withdrawals", Scenario.Mode.CONCURRENT,
                "java.util.List<Integer> cwSeen = new java.util.ArrayList<>();",
                List.of(new Scenario.Step("cwSeen.add(${amount})", List.of(amount()), "record the amount")),
                "cwSeen.size()", "observation >= 0", 5, 7L, 60);
        return generate(scenario, obligation("OB-002", "SC-002"));
    }

    private GeneratedTest generate(Scenario scenario, VerificationObligation obligation) throws IOException {
        Path root = tmp.resolve("project-" + scenario.id());
        Files.createDirectories(root.resolve("src/main/java"));
        VerificationContext context = TestContexts.context(root, root.resolve(".codewitness"), List.of(scenario));
        List<GeneratedTest> generated = generator.generate(context, requirement(), List.of(obligation));
        assertEquals(1, generated.size(), "one scenario produces one generated test");
        return generated.get(0);
    }

    /** @return the output directory containing the compiled class. */
    private Path compile(GeneratedTest generated) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required to compile generated tests");

        Path sourceRoot = Files.createDirectories(tmp.resolve("compile/src"));
        Path classes = Files.createDirectories(tmp.resolve("compile/" + generated.simpleName()));
        Path source = sourceRoot.resolve(generated.relativePath());
        Files.createDirectories(source.getParent());
        Files.writeString(source, generated.sourceCode(), StandardCharsets.UTF_8);

        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int exitCode = compiler.run(null, null, diagnostics,
                "-d", classes.toString(),
                "-classpath", classpath(),
                source.toString());
        if (exitCode != 0) {
            throw new AssertionError("generated source did not compile (exit " + exitCode + "):\n"
                    + diagnostics.toString(StandardCharsets.UTF_8) + "\n--- source ---\n" + generated.sourceCode());
        }
        return classes;
    }

    /** Surefire publishes the real test classpath separately when it uses a manifest-only jar. */
    private static String classpath() {
        String fromSurefire = System.getProperty("surefire.test.class.path");
        if (fromSurefire != null && !fromSurefire.isBlank()) {
            return fromSurefire;
        }
        String classpath = System.getProperty("java.class.path");
        if (classpath == null) {
            throw new IllegalStateException("no classpath available for compilation");
        }
        return classpath;
    }

    private static Requirement requirement() {
        return Requirement.builder("R-001", "A customer may withdraw at most 1,000 per rolling 24 hours.")
                .title("Withdrawal limit")
                .build();
    }

    private static VerificationObligation obligation(String id, String scenarioId) {
        return new VerificationObligation(id, "R-001", "Execute scenario " + scenarioId,
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, scenarioId,
                Map.of("scenario", scenarioId), false);
    }

    private static Scenario sequenceScenario() {
        return new Scenario(
                "SC-001", "R-001", "withdrawals never exceed the limit", Scenario.Mode.SEQUENCE,
                "java.util.List<Integer> cwSeen = new java.util.ArrayList<>();",
                List.of(new Scenario.Step("cwSeen.add(${amount})", List.of(amount()), "record the amount")),
                "cwSeen.size()", "observation >= 0", 10, 42L, 60);
    }

    private static Scenario.Input amount() {
        return new Scenario.Input("amount", "int", Scenario.Domain.INTEGER,
                List.of("0", "1000", "1001"), "0", "1001", 0);
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

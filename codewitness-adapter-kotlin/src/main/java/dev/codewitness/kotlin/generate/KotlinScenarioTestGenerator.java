package dev.codewitness.kotlin.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns declared scenarios into executable Kotlin JUnit 5 tests.
 *
 * <p>The generator supplies the mechanical part of property based testing: the boundary values, the seeded
 * random search, the reproducibility scaffolding and the structured failure report. The user supplies the
 * oracle: the setup statements, the calls and the invariant, all written in Kotlin in
 * {@code codewitness.yaml}. CodeWitness does not invent an oracle, because an invented oracle is exactly the
 * kind of assertion this tool exists to avoid.
 *
 * <p>Every generated test is deterministic given its seed, and a failing case reports the exact inputs plus
 * the seed and case index, so it can be replayed with
 * {@code -Dcodewitness.seed=... -Dcodewitness.case=...} or through the environment variables of the same
 * name, which is the only channel Gradle gives a test JVM.
 *
 * <p>The emitted file is Kotlin, so three details differ from the Java generator. Locals are declared with
 * {@code val} and no type annotation, so the initialiser decides the type and a scenario never has to name a
 * Kotlin type for an input it draws - only for the parameter of the case runner, which is read from the
 * declaration's {@code type}. A failure throws {@code AssertionError} rather than calling
 * {@code Assertions.fail}, because the latter is a generic method whose type variable Kotlin cannot always
 * infer from a statement position. And a {@code $} in a message that reaches a string literal is escaped, as
 * Kotlin would otherwise read it as the start of a template.
 */
public final class KotlinScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Default number of threads used by CONCURRENT scenarios. */
    private static final int CONCURRENT_THREADS = 8;

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    @Override
    public String id() {
        return "kotlin-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.KOTLIN);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        List<GeneratedTest> generated = new ArrayList<>();
        for (VerificationObligation obligation : obligations) {
            String scenarioId = obligation.parameter("scenario");
            if (scenarioId == null) {
                continue;
            }
            Scenario scenario = context.config().scenarios().stream()
                    .filter(candidate -> candidate.id().equals(scenarioId))
                    .findFirst()
                    .orElse(null);
            if (scenario == null) {
                continue;
            }
            String className = classNameFor(scenario, obligation);
            String source = render(context, requirement, obligation, scenario, className);
            generated.add(new GeneratedTest(className, source, "randomSearch",
                    List.of(requirement.id()), List.of(obligation.id()),
                    scenario.description(), scenario.seed(), null,
                    scenario.observation() != null && scenario.invariant() != null));
        }
        return generated;
    }

    private static String classNameFor(Scenario scenario, VerificationObligation obligation) {
        String scenarioPart = sanitize(scenario.id());
        String obligationPart = sanitize(obligation.id());
        String suffix = scenario.mode() == Scenario.Mode.CONCURRENT ? "ConcurrencyTest" : "Test";
        return KotlinGeneratedTestRunner.generatedPackage() + ".Cw_" + scenarioPart + "_" + obligationPart
                + "_" + suffix;
    }

    private static String sanitize(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }

    private String render(
            VerificationContext context,
            Requirement requirement,
            VerificationObligation obligation,
            Scenario scenario,
            String className) {

        List<Scenario.Input> inputs = collectInputs(scenario);
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        String indent = "    ";
        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(KotlinGeneratedTestRunner.generatedPackage()).append("\n\n");
        sb.append("// Generated by CodeWitness ").append(dev.codewitness.core.engine.VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("// Requirement: ").append(requirement.id()).append(" - ").append(oneLine(requirement.title())).append('\n');
        sb.append("// Obligation: ").append(obligation.id()).append(" - ").append(oneLine(obligation.description())).append('\n');
        sb.append("// Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases()).append(")\n");
        sb.append("// This file is temporary: CodeWitness removes it after the run and keeps a copy in the run directory.\n\n");

        sb.append("class ").append(simpleName).append(" {\n\n");

        // --- boundary cases
        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            sb.append(indent).append("@org.junit.jupiter.api.Test\n");
            sb.append(indent).append("fun case").append(String.format(Locale.ROOT, "%04d", index)).append("() {\n");
            for (Scenario.Input input : inputs) {
                sb.append(indent).append(indent).append("val ").append(input.name()).append(" = ")
                        .append(literal(input, values.get(input.name()))).append('\n');
            }
            sb.append(indent).append(indent).append("runCase(").append(index).append(", null");
            for (Scenario.Input input : inputs) {
                sb.append(", ").append(input.name());
            }
            sb.append(")\n").append(indent).append("}\n\n");
            index++;
        }

        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input has a declared type but none declares a boundary value, so there is nothing to
            // enumerate. Run the declared defaults once instead of emitting a class with no test function
            // at all: an empty test class reports nothing, and a scenario that reports nothing must not be
            // mistaken for one that ran and passed.
            Map<String, String> values = defaultValues(inputs);
            sb.append(indent).append("@org.junit.jupiter.api.Test\n");
            sb.append(indent).append("fun case0000() {\n");
            for (Scenario.Input input : inputs) {
                sb.append(indent).append(indent).append("val ").append(input.name()).append(" = ")
                        .append(literal(input, values.get(input.name()))).append('\n');
            }
            sb.append(indent).append(indent).append("runCase(0, null");
            for (Scenario.Input input : inputs) {
                sb.append(", ").append(input.name());
            }
            sb.append(")\n").append(indent).append("}\n\n");
        }

        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            // A scenario with no searchable input still deserves to be executed once: a fixed sequence of
            // calls is a perfectly good experiment.
            sb.append(indent).append("@org.junit.jupiter.api.Test\n");
            sb.append(indent).append("fun singleCase() {\n");
            sb.append(indent).append(indent).append("runCase(0, null)\n");
            sb.append(indent).append("}\n\n");
        }

        // --- randomised search (and concurrency), driven by seed and optional case pinning
        boolean random = scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1;
        if (random && !inputs.isEmpty()) {
            if (scenario.mode() == Scenario.Mode.CONCURRENT) {
                sb.append(concurrentTest(inputs, scenario, indent));
            } else {
                sb.append(randomTest(inputs, scenario, indent));
            }
        }

        sb.append(runnerBody(inputs, scenario, indent));
        sb.append(companionBody(indent, requirement, obligation, scenario));
        sb.append("}\n");
        return sb.toString();
    }

    private String randomTest(List<Scenario.Input> inputs, Scenario scenario, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("/**\n");
        sb.append(indent).append(" * Seeded random search over the declared domains.\n");
        sb.append(indent).append(" * Override with -Dcodewitness.seed=<seed> -Dcodewitness.case=<index>, or with\n");
        sb.append(indent).append(" * CODEWITNESS_SEED and CODEWITNESS_CASE, to replay one case.\n");
        sb.append(indent).append(" */\n");
        sb.append(indent).append("@org.junit.jupiter.api.Test\n");
        sb.append(indent).append("fun randomSearch() {\n");
        sb.append(indent).append(indent).append("val seed = cwSeed(").append(scenario.seed()).append("L)\n");
        sb.append(indent).append(indent).append("val cases = cwCases(").append(scenario.cases()).append(")\n");
        sb.append(indent).append(indent).append("val pinned = cwPinnedCase()\n");
        sb.append(indent).append(indent).append("for (cwCase in 0 until cases) {\n");
        sb.append(indent).append(indent).append(indent).append("if (pinned >= 0 && cwCase != pinned) { continue }\n");
        sb.append(indent).append(indent).append(indent).append("val rnd = java.util.Random(seed + cwCase)\n");
        for (Scenario.Input input : inputs) {
            sb.append(indent).append(indent).append(indent).append("val ").append(input.name()).append(" = ")
                    .append(drawExpression(input)).append('\n');
        }
        sb.append(indent).append(indent).append(indent).append("runCase(cwCase, seed");
        for (Scenario.Input input : inputs) {
            sb.append(", ").append(input.name());
        }
        sb.append(")\n").append(indent).append(indent).append("}\n").append(indent).append("}\n\n");
        return sb.toString();
    }

    private String concurrentTest(List<Scenario.Input> inputs, Scenario scenario, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("/**\n");
        sb.append(indent).append(" * Executes the declared steps concurrently against one shared subject and then checks the\n");
        sb.append(indent).append(" * invariant. A failure here is a counterexample: the requirement does not hold when the\n");
        sb.append(indent).append(" * operations interleave.\n");
        sb.append(indent).append(" */\n");
        sb.append(indent).append("@org.junit.jupiter.api.Test\n");
        sb.append(indent).append("fun concurrentSearch() {\n");
        String i2 = indent + indent;
        String i3 = i2 + indent;
        sb.append(i2).append("val seed = cwSeed(").append(scenario.seed()).append("L)\n");
        sb.append(i2).append("val cases = cwCases(").append(scenario.cases()).append(")\n");
        sb.append(i2).append("val pinned = cwPinnedCase()\n");
        sb.append(i2).append("for (cwCase in 0 until cases) {\n");
        sb.append(i3).append("if (pinned >= 0 && cwCase != pinned) { continue }\n");
        sb.append(i3).append("val rnd = java.util.Random(seed + cwCase)\n");
        for (Scenario.Input input : inputs) {
            sb.append(i3).append("val ").append(input.name()).append(" = ").append(drawExpression(input)).append('\n');
        }
        sb.append(i3).append("val cwInputs = cwInputs(");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(inputs.get(i).name()).append("\" to ")
                    .append(inputs.get(i).name());
        }
        sb.append(")\n");
        sb.append(i3).append("try {\n");
        if (scenario.setup() != null) {
            sb.append(i3).append(indent).append(scenario.setup()).append('\n');
        }
        sb.append(i3).append(indent).append("val cwPool = java.util.concurrent.Executors.newFixedThreadPool(")
                .append(CONCURRENT_THREADS).append(")\n");
        sb.append(i3).append(indent).append("// All threads are released together: a race that only appears when calls\n");
        sb.append(i3).append(indent).append("// genuinely overlap must not be hidden by thread start-up skew.\n");
        sb.append(i3).append(indent).append("val cwStart = java.util.concurrent.CountDownLatch(1)\n");
        sb.append(i3).append(indent).append("val cwTasks = java.util.ArrayList<java.util.concurrent.Future<*>>()\n");
        sb.append(i3).append(indent).append("for (cwThread in 0 until ").append(CONCURRENT_THREADS).append(") {\n");
        sb.append(i3).append(indent).append(indent)
                .append("cwTasks.add(cwPool.submit(java.util.concurrent.Callable<Unit> {\n");
        sb.append(i3).append(indent).append(indent).append(indent).append("cwStart.await()\n");
        for (Scenario.Step step : scenario.steps()) {
            sb.append(i3).append(indent).append(indent).append(indent)
                    .append(substitute(step.call(), inputs)).append('\n');
        }
        sb.append(i3).append(indent).append(indent).append("}))\n");
        sb.append(i3).append(indent).append("}\n");
        sb.append(i3).append(indent).append("cwStart.countDown()\n");
        sb.append(i3).append(indent).append("for (cwTask in cwTasks) { cwTask.get() }\n");
        sb.append(i3).append(indent).append("cwPool.shutdown()\n");
        if (scenario.observation() != null && scenario.invariant() != null) {
            sb.append(i3).append(indent).append("val observation = ")
                    .append(substitute(scenario.observation(), inputs)).append('\n');
            sb.append(i3).append(indent).append("if (!(").append(substitute(scenario.invariant(), inputs))
                    .append(")) {\n");
            sb.append(i3).append(indent).append(indent).append("cwFail(cwCase, seed, cwInputs, \"")
                    .append(escape(oneLine(scenario.invariant())))
                    .append("\", observation.toString(), \"concurrent execution\")\n");
            sb.append(i3).append(indent).append("}\n");
        }
        sb.append(i3).append("} catch (cwAssertion: AssertionError) {\n")
                .append(i3).append(indent).append("throw cwAssertion\n");
        sb.append(i3).append("} catch (cwError: Throwable) {\n");
        sb.append(i3).append(indent).append("cwFail(cwCase, seed, cwInputs, \"the declared steps threw \" + cwError, "
                + "cwError.toString(), \"concurrent execution\")\n");
        sb.append(i3).append("}\n");
        sb.append(i2).append("}\n").append(indent).append("}\n\n");
        return sb.toString();
    }

    private String runnerBody(List<Scenario.Input> inputs, Scenario scenario, String indent) {
        StringBuilder sb = new StringBuilder();
        String i2 = indent + indent;
        sb.append(indent).append("private fun runCase(cwCase: Int, cwSeed: Long?");
        for (Scenario.Input input : inputs) {
            sb.append(", ").append(input.name()).append(": ").append(kotlinType(input.type()));
        }
        sb.append(") {\n");
        sb.append(i2).append("val cwInputs = cwInputs(");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(inputs.get(i).name()).append("\" to ")
                    .append(inputs.get(i).name());
        }
        sb.append(")\n");
        sb.append(i2).append("try {\n");
        if (scenario.setup() != null) {
            sb.append(i2).append(indent).append(scenario.setup()).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append(i2).append(indent).append(substitute(step.call(), inputs)).append('\n');
            if (scenario.observation() != null && scenario.invariant() != null) {
                // `kotlin.run { ... }` rather than a bare block. In Kotlin a brace that follows a call is a
                // trailing lambda argument to it, so `ledger.applyFee(fee)` followed by a bare `{` would be
                // read as `ledger.applyFee(fee) { ... }` - one call with an extra argument - and the file
                // would not compile. A named block gives the observation its own scope, so that a scenario
                // with several steps can declare one per step.
                sb.append(i2).append(indent).append("kotlin.run {\n");
                sb.append(i2).append(indent).append(indent).append("val observation = ")
                        .append(substitute(scenario.observation(), inputs)).append('\n');
                sb.append(i2).append(indent).append(indent).append("if (!(")
                        .append(substitute(scenario.invariant(), inputs)).append(")) {\n");
                sb.append(i2).append(indent).append(indent).append(indent).append("cwFail(cwCase, cwSeed, cwInputs, \"")
                        .append(escape(oneLine(scenario.invariant())))
                        .append("\", observation.toString(), \"")
                        .append(escape(oneLine(step.description() == null ? step.call() : step.description())))
                        .append("\")\n");
                sb.append(i2).append(indent).append(indent).append("}\n");
                sb.append(i2).append(indent).append("}\n");
            }
        }
        sb.append(i2).append("} catch (cwAssertion: AssertionError) {\n")
                .append(i2).append(indent).append("throw cwAssertion\n");
        sb.append(i2).append("} catch (cwError: Throwable) {\n");
        sb.append(i2).append(indent).append("cwFail(cwCase, cwSeed, cwInputs, \"the declared steps threw \" + cwError, "
                + "cwError.toString(), \"")
                .append(escape(oneLine(scenario.observation() == null ? "step execution" : scenario.observation())))
                .append("\")\n");
        sb.append(i2).append("}\n").append(indent).append("}\n\n");
        return sb.toString();
    }

    private String companionBody(
            String indent, Requirement requirement, VerificationObligation obligation, Scenario scenario) {
        String i2 = indent + indent;
        String i3 = i2 + indent;
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("companion object {\n\n");
        sb.append(i2).append("private const val CW_REQUIREMENT = \"").append(escape(requirement.id())).append("\"\n");
        sb.append(i2).append("private const val CW_OBLIGATION = \"").append(escape(obligation.id())).append("\"\n");
        sb.append(i2).append("private const val CW_INVARIANT = \"")
                .append(escape(oneLine(scenario.invariant() == null ? "(no invariant declared)" : scenario.invariant())))
                .append("\"\n\n");
        sb.append(i2).append("private fun cwInputs(vararg cwPairs: Pair<String, Any?>): "
                + "java.util.LinkedHashMap<String, Any?> {\n");
        sb.append(i3).append("val cwMap = java.util.LinkedHashMap<String, Any?>()\n");
        sb.append(i3).append("for (cwPair in cwPairs) { cwMap[cwPair.first] = cwPair.second }\n");
        sb.append(i3).append("return cwMap\n").append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwProtocol(cwCase: Int, cwSeed: Long?, cwInputs: Map<String, Any?>,\n");
        sb.append(i2).append(indent).append(indent).append("cwExpected: String, cwActual: String, cwStep: String): String {\n");
        sb.append(i3).append("val cwFields = java.util.LinkedHashMap<String, String>()\n");
        sb.append(i3).append("cwFields[\"case\"] = cwCase.toString()\n");
        sb.append(i3).append("if (cwSeed != null) { cwFields[\"seed\"] = cwSeed.toString() }\n");
        sb.append(i3).append("cwFields[\"inputs\"] = cwRenderInputs(cwInputs)\n");
        sb.append(i3).append("cwFields[\"expected\"] = cwExpected\n");
        sb.append(i3).append("cwFields[\"actual\"] = cwActual\n");
        sb.append(i3).append("cwFields[\"invariant\"] = CW_INVARIANT\n");
        sb.append(i3).append("cwFields[\"step\"] = cwStep\n");
        sb.append(i3).append("cwFields[\"requirement\"] = CW_REQUIREMENT\n");
        sb.append(i3).append("cwFields[\"obligation\"] = CW_OBLIGATION\n");
        sb.append(i3).append("val cwLine = StringBuilder(\"").append(CounterexampleProtocol.MARKER).append("\")\n");
        sb.append(i3).append("for (cwEntry in cwFields.entries) {\n");
        sb.append(i3).append(indent).append("cwLine.append('|').append(cwEntry.key).append('=')\n");
        sb.append(i3).append(indent).append(indent).append(".append(cwEntry.value.replace('|', '/').replace('\\n', ' '))\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("return cwLine.toString()\n").append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwRenderInputs(cwInputs: Map<String, Any?>): String {\n");
        sb.append(i3).append("val cwText = StringBuilder(\"{\")\n");
        sb.append(i3).append("var cwFirst = true\n");
        sb.append(i3).append("for (cwEntry in cwInputs.entries) {\n");
        sb.append(i3).append(indent).append("if (!cwFirst) { cwText.append(\", \") }\n");
        sb.append(i3).append(indent).append("cwFirst = false\n");
        sb.append(i3).append(indent).append("cwText.append(cwEntry.key).append('=').append(cwEntry.value)\n");
        sb.append(i3).append("}\n");
        sb.append(i3).append("return cwText.append('}').toString()\n").append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwFail(cwCase: Int, cwSeed: Long?, cwInputs: Map<String, Any?>,\n");
        sb.append(i2).append(indent).append(indent).append("cwExpected: String, cwActual: String, cwStep: String) {\n");
        sb.append(i3).append("throw AssertionError(cwProtocol(cwCase, cwSeed, cwInputs, cwExpected, cwActual, cwStep))\n");
        sb.append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwSeed(fallback: Long): Long {\n");
        sb.append(i3).append("val cwValue = System.getProperty(\"codewitness.seed\") ?: System.getenv(\"CODEWITNESS_SEED\")\n");
        sb.append(i3).append("if (cwValue.isNullOrBlank()) { return fallback }\n");
        sb.append(i3).append("return cwValue.trim().toLongOrNull() ?: fallback\n");
        sb.append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwCases(fallback: Int): Int {\n");
        sb.append(i3).append("val cwValue = System.getProperty(\"codewitness.cases\")\n");
        sb.append(i3).append("if (cwValue.isNullOrBlank()) { return fallback }\n");
        sb.append(i3).append("return cwValue.trim().toIntOrNull() ?: fallback\n");
        sb.append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwPinnedCase(): Int {\n");
        sb.append(i3).append("val cwValue = System.getProperty(\"codewitness.case\") ?: System.getenv(\"CODEWITNESS_CASE\")\n");
        sb.append(i3).append("if (cwValue.isNullOrBlank()) { return -1 }\n");
        sb.append(i3).append("return cwValue.trim().toIntOrNull() ?: -1\n");
        sb.append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwLong(rnd: java.util.Random, min: Long, max: Long): Long {\n");
        sb.append(i3).append("if (max <= min) { return min }\n");
        sb.append(i3).append("val cwSpan = max - min\n");
        sb.append(i3).append("if (cwSpan <= 0) { return min + (rnd.nextDouble() * (max / 2.0)).toLong() }\n");
        sb.append(i3).append("return min + Math.floor(rnd.nextDouble() * (cwSpan + 1)).toLong()\n");
        sb.append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwInt(rnd: java.util.Random, min: Int, max: Int): Int =\n");
        sb.append(i3).append("cwLong(rnd, min.toLong(), max.toLong()).toInt()\n\n");

        sb.append(i2).append("private fun cwDouble(rnd: java.util.Random, min: Double, max: Double): Double =\n");
        sb.append(i3).append("min + rnd.nextDouble() * (max - min)\n\n");

        sb.append(i2).append("private fun cwDecimal(rnd: java.util.Random, min: String, max: String, scale: Int): "
                + "java.math.BigDecimal {\n");
        sb.append(i3).append("val cwLow = java.math.BigDecimal(min)\n");
        sb.append(i3).append("val cwHigh = java.math.BigDecimal(max)\n");
        sb.append(i3).append("val cwValue = cwLow.add(cwHigh.subtract(cwLow).multiply(java.math.BigDecimal.valueOf(rnd.nextDouble())))\n");
        sb.append(i3).append("return cwValue.setScale(scale, java.math.RoundingMode.HALF_UP)\n");
        sb.append(i2).append("}\n\n");

        sb.append(i2).append("private fun cwInstant(rnd: java.util.Random, min: Long, max: Long): java.time.Instant =\n");
        sb.append(i3).append("java.time.Instant.ofEpochMilli(cwLong(rnd, min, max))\n\n");

        sb.append(i2).append("private fun cwString(rnd: java.util.Random, cwPrefix: String): String =\n");
        sb.append(i3).append("cwPrefix + rnd.nextInt(10_000)\n");

        sb.append(indent).append("}\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static List<Scenario.Input> collectInputs(Scenario scenario) {
        Map<String, Scenario.Input> byName = new LinkedHashMap<>();
        for (Scenario.Step step : scenario.steps()) {
            for (Scenario.Input input : step.inputs()) {
                byName.putIfAbsent(input.name(), input);
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * @return one value per input, taken from the declared boundary values when there are any and from the
     *     type's default otherwise. Used when a scenario declares inputs but no boundary values, so that it
     *     still produces an executed case.
     */
    private static Map<String, String> defaultValues(List<Scenario.Input> inputs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Scenario.Input input : inputs) {
            values.put(input.name(), input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst());
        }
        return values;
    }

    static List<Map<String, String>> boundaryCases(List<Scenario.Input> inputs) {

        // One implementation, in support: fourteen copies of this existed and three of them

        // truncated differently, which changes which experiments run and therefore what the

        // same scenario verifies.

        return dev.codewitness.support.generate.ScenarioCases.boundaryCases(inputs);

    }

    private static String literal(Scenario.Input input, String rawValue) {
        String value = rawValue;
        if (value == null) {
            value = input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst();
        }
        return switch (input.domain()) {
            case DECIMAL -> "java.math.BigDecimal(\"" + escape(value) + "\")";
            case INTEGER -> value;
            case LONG -> value + "L";
            case DOUBLE -> doubleLiteral(value);
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(value));
            case STRING -> "\"" + escape(value) + "\"";
            case INSTANT_MILLIS -> "java.time.Instant.ofEpochMilli(" + value + "L)";
            case NULL -> "null";
            case RAW -> value;
        };
    }

    /**
     * @return the value used when an input declares no boundary value.
     *
     * <p>It is a raw value, not a rendered literal: {@link #literal} applies the domain's wrapper, so a
     * decimal default is {@code 0} here and becomes {@code java.math.BigDecimal("0")} there. Returning an
     * already-wrapped value would wrap it twice.
     */
    private static String defaultLiteral(Scenario.Input input) {
        return switch (input.domain()) {
            // Kotlin has no `d` suffix, and a bare integer literal assigned to a Double does not convert,
            // so the decimal point has to be written out.
            case DOUBLE -> "0.0";
            case BOOLEAN -> "false";
            case STRING -> "";
            case NULL, RAW -> "null";
            default -> "0";
        };
    }

    private static String doubleLiteral(String value) {
        if (value == null || value.isBlank()) {
            return "0.0";
        }
        String trimmed = value.strip();
        if (trimmed.indexOf('.') >= 0 || trimmed.indexOf('e') >= 0 || trimmed.indexOf('E') >= 0) {
            return trimmed;
        }
        return trimmed + ".0";
    }

    private static String drawExpression(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (input.domain()) {
            case DECIMAL -> "cwDecimal(rnd, \"" + (min == null ? "0" : escape(min)) + "\", \""
                    // An omitted maximum is widened to 10000, and an integer-valued boundary is still a legal
                    // BigDecimal literal, so the scale is the only thing that has to be stated.
                    + (max == null ? "10000" : escape(max)) + "\", " + Math.max(0, input.scale()) + ")";
            case INTEGER -> "cwInt(rnd, " + (min == null ? "0" : min) + ", " + (max == null ? "10000" : max) + ")";
            case LONG -> "cwLong(rnd, " + (min == null ? "0" : min) + "L, " + (max == null ? "10000" : max) + "L)";
            case DOUBLE -> "cwDouble(rnd, " + doubleLiteral(min == null ? "0" : min) + ", "
                    + doubleLiteral(max == null ? "1000" : max) + ")";
            case BOOLEAN -> "rnd.nextBoolean()";
            case STRING -> "cwString(rnd, \"cw-\")";
            case INSTANT_MILLIS -> "cwInstant(rnd, " + (min == null ? "0" : min) + "L, "
                    + (max == null ? "86400000" : max) + "L)";
            case NULL -> "null";
            case RAW -> input.values().isEmpty() ? "null" : input.values().getFirst();
        };
    }

    /**
     * @param type the scenario's declared type for an input
     * @return the same type as Kotlin spells it.
     *
     * <p>A scenario declares one type for the input and expects it to work for the domain inference and for
     * the emitted code, so the Java-primitive spellings the domain table understands are translated here
     * rather than requiring every Kotlin scenario to be written with Kotlin names. Anything else - a JVM
     * class such as {@code java.math.BigDecimal} - is spelled the same way in both languages and is passed
     * through unchanged.
     */
    static String kotlinType(String type) {
        if (type == null || type.isBlank()) {
            return "Any?";
        }
        return switch (type.strip()) {
            case "long", "java.lang.Long" -> "Long";
            case "int", "java.lang.Integer" -> "Int";
            case "short", "java.lang.Short" -> "Short";
            case "byte", "java.lang.Byte" -> "Byte";
            case "double", "java.lang.Double" -> "Double";
            case "float", "java.lang.Float" -> "Float";
            case "boolean", "java.lang.Boolean" -> "Boolean";
            case "char", "java.lang.Character" -> "Char";
            case "java.lang.String" -> "String";
            default -> type.strip();
        };
    }

    private static String substitute(String fragment, List<Scenario.Input> inputs) {
        String result = fragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("${" + input.name() + "}", input.name());
        }
        return result;
    }

    /**
     * Escapes a value for a Kotlin string literal.
     *
     * <p>{@code $} is escaped as well as the usual characters: Kotlin reads an unescaped one as the start of
     * a template, so an invariant or a step description containing a dollar sign would otherwise either fail
     * to compile or quietly interpolate something that does not exist.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    /** @return the distinct input names of a scenario, used by tests. */
    public static java.util.Set<String> inputNames(Scenario scenario) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Scenario.Step step : scenario.steps()) {
            step.inputs().forEach(input -> names.add(input.name()));
        }
        return names;
    }
}

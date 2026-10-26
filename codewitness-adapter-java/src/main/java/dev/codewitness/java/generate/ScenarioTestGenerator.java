package dev.codewitness.java.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns declared scenarios into executable JUnit 5 tests.
 *
 * <p>The generator supplies the mechanical part of property based testing: the boundary values, the
 * seeded random search, the reproducibility scaffolding and the structured failure report. The user
 * supplies the oracle: the setup statements, the calls and the invariant, all written in Java in
 * {@code codewitness.yaml}. CodeWitness does not invent an oracle, because an invented oracle is
 * exactly the kind of assertion this tool exists to avoid.
 *
 * <p>Every generated test is deterministic given its seed, and a failing case reports the exact
 * inputs plus the seed and case index, so it can be replayed with
 * {@code -Dcodewitness.seed=... -Dcodewitness.case=...}.
 */
public final class ScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Default number of threads used by CONCURRENT scenarios. */
    private static final int CONCURRENT_THREADS = 8;

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    @Override
    public String id() {
        return "java-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA);
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
                    .filter(s -> s.id().equals(scenarioId))
                    .findFirst()
                    .orElse(null);
            if (scenario == null) {
                continue;
            }
            if (scenario.mode() == Scenario.Mode.PROCESS) {
                // A process scenario has no steps, observation or invariant, so the step-based renderer would emit a
                // test with an empty body. The engine executes it unless the project asked for a native test.
                if (scenario.nativeTest()) {
                    generated.add(processTest(context, requirement, obligation, scenario));
                }
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

    /**
     * Renders a JUnit 5 test that runs the declared command and asserts on it.
     *
     * <p>The same assertion the engine makes, as a class the project can keep: it starts the command with
     * `ProcessBuilder`, drains both streams, compares the exit code and the expectations, and on a violation prints
     * the shared `CODEWITNESS_COUNTEREXAMPLE|…` line before failing.
     *
     * <p>The generated class is written into the project's own test sources and run with the project's own runner,
     * so its assertions live in the suite that will still be there when CodeWitness is not. `${project_root}` becomes
     * the working directory the test runs in, because a generated test cannot know the absolute path of the checkout
     * it will eventually live in.
     *
     * @param context     the run
     * @param requirement the requirement the scenario is written for
     * @param obligation  the obligation this test discharges
     * @param scenario    the process scenario
     * @return the generated test
     */
    private GeneratedTest processTest(
            VerificationContext context,
            Requirement requirement,
            VerificationObligation obligation,
            Scenario scenario) {
        Scenario.ProcessExpectation expect = scenario.expect();
        String className = "dev.codewitness.generated.Cw_" + sanitize(scenario.id()) + "_"
                + sanitize(obligation.id()) + "_ProcessTest";
        StringBuilder sb = new StringBuilder();
        sb.append("package dev.codewitness.generated;\n\n");
        sb.append("// Generated by CodeWitness ").append(dev.codewitness.core.engine.VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("// Requirement: ").append(requirement.id()).append(" - ").append(oneLine(requirement.title()))
                .append('\n');
        sb.append("// Scenario: ").append(scenario.id()).append(" (mode process). This class is yours to keep: it\n");
        sb.append("// asserts what the scenario declared, in JUnit, so the guarantee survives without CodeWitness.\n\n");
        sb.append("import org.junit.jupiter.api.Test;\n\n");
        sb.append("import java.io.IOException;\n");
        sb.append("import java.nio.charset.StandardCharsets;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.concurrent.TimeUnit;\n\n");
        sb.append("import static org.junit.jupiter.api.Assertions.assertTrue;\n");
        sb.append("import static org.junit.jupiter.api.Assertions.fail;\n\n");
        sb.append("class Cw_").append(sanitize(scenario.id())).append('_').append(sanitize(obligation.id()))
                .append("_ProcessTest {\n\n");
        sb.append("    private static final List<String> COMMAND = List.of(");
        for (int i = 0; i < scenario.command().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(escape(scenario.command().get(i))).append('"');
        }
        sb.append(");\n");
        sb.append("    private static final Integer EXIT_CODE = ").append(expect.exitCode()).append(";\n");
        sb.append("    private static final List<String> STDOUT = ").append(javaList(expect.stdoutContains()))
                .append(";\n");
        sb.append("    private static final List<String> STDERR = ").append(javaList(expect.stderrContains()))
                .append(";\n\n");
        sb.append("    @Test\n");
        sb.append("    void process() throws Exception {\n");
        sb.append("        List<String> command = COMMAND.stream()\n");
        sb.append("                .map(part -> part.replace(\"${project_root}\", System.getProperty(\"user.dir\")))\n");
        sb.append("                .toList();\n");
        sb.append("        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);\n");
        sb.append("        Process process;\n");
        sb.append("        try {\n");
        sb.append("            process = builder.start();\n");
        sb.append("        } catch (IOException e) {\n");
        sb.append("            fail(\"the declared command could not be started: \" + e);\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        sb.append("        boolean finished = process.waitFor(").append(scenario.timeoutSeconds())
                .append(", TimeUnit.SECONDS);\n");
        sb.append("        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);\n");
        sb.append("        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);\n");
        sb.append("        if (!finished) {\n");
        sb.append("            process.destroyForcibly();\n");
        sb.append("            fail(\"the declared command did not finish in time; nothing it printed can be trusted\");\n");
        sb.append("        }\n");
        sb.append("        StringBuilder problems = new StringBuilder();\n");
        sb.append("        if (EXIT_CODE != null && process.exitValue() != EXIT_CODE) {\n");
        sb.append("            problems.append(\"exit code was \").append(process.exitValue())\n");
        sb.append("                    .append(\", required \").append(EXIT_CODE).append(\"; \");\n");
        sb.append("        }\n");
        sb.append("        for (String required : STDOUT) {\n");
        sb.append("            if (!out.contains(required)) {\n");
        sb.append("                problems.append(\"standard output did not contain \\\"\").append(required)\n");
        sb.append("                        .append(\"\\\"; \");\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        for (String required : STDERR) {\n");
        sb.append("            if (!err.contains(required)) {\n");
        sb.append("                problems.append(\"standard error did not contain \\\"\").append(required)\n");
        sb.append("                        .append(\"\\\"; \");\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        if (!problems.isEmpty()) {\n");
        sb.append("            System.out.println(\"CODEWITNESS_COUNTEREXAMPLE|case=0|seed=|inputs=\"\n");
        sb.append("                    + String.join(\" \", command) + \"|expected=\" + EXIT_CODE + \"|actual=\"\n");
        sb.append("                    + problems + \"|requirement=").append(requirement.id())
                .append("|obligation=").append(obligation.id()).append("\");\n");
        sb.append("            assertTrue(problems.isEmpty(), problems.toString());\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n");
        return new GeneratedTest(className, sb.toString(), "process",
                List.of(requirement.id()), List.of(obligation.id()), scenario.description(), null, null, true);
    }

    /**
     * @param values strings to render as a Java list literal
     * @return the literal
     */
    private static String javaList(List<String> values) {
        return values.stream().map(value -> '"' + escape(value) + '"')
                .collect(java.util.stream.Collectors.joining(", ", "List.of(", ")"));
    }

    private static String classNameFor(Scenario scenario, VerificationObligation obligation) {
        String scenarioPart = sanitize(scenario.id());
        String obligationPart = sanitize(obligation.id());
        String suffix = scenario.mode() == Scenario.Mode.CONCURRENT ? "ConcurrencyTest" : "Test";
        return "dev.codewitness.generated.Cw_" + scenarioPart + "_" + obligationPart + "_" + suffix;
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
        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(JavaGeneratedTestRunner.generatedPackage()).append(";\n\n");
        sb.append("// Generated by CodeWitness ").append(dev.codewitness.core.engine.VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("// Requirement: ").append(requirement.id()).append(" - ").append(oneLine(requirement.title())).append('\n');
        sb.append("// Obligation: ").append(obligation.id()).append(" - ").append(oneLine(obligation.description())).append('\n');
        sb.append("// Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases()).append(")\n");
        sb.append("// This file is temporary: CodeWitness removes it after the run and keeps a copy in the run directory.\n\n");
        sb.append("public class ").append(simpleName).append(" {\n\n");

        sb.append("    private static final String CW_REQUIREMENT = \"").append(escape(requirement.id())).append("\";\n");
        sb.append("    private static final String CW_OBLIGATION = \"").append(escape(obligation.id())).append("\";\n");
        sb.append("    private static final String CW_INVARIANT = \"")
                .append(escape(oneLine(scenario.invariant() == null ? "(no invariant declared)" : scenario.invariant())))
                .append("\";\n\n");

        // --- boundary cases
        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            sb.append("    @org.junit.jupiter.api.Test\n");
            sb.append("    void case").append(String.format(Locale.ROOT, "%04d", index)).append("() {\n");
            for (Scenario.Input input : inputs) {
                sb.append("        ").append(input.type()).append(' ').append(input.name()).append(" = ")
                        .append(literal(input, values.get(input.name()))).append(";\n");
            }
            sb.append("        runCase(").append(index).append(", null");
            for (Scenario.Input input : inputs) {
                sb.append(", ").append(input.name());
            }
            sb.append(");\n    }\n\n");
            index++;
        }

        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input has a declared type but none declares a boundary value, so there is nothing
            // to enumerate. Run the declared defaults once instead of emitting a class with no test
            // methods at all: an empty test class reports nothing, and a scenario that reports nothing
            // must not be mistaken for one that ran and passed.
            Map<String, String> values = defaultValues(inputs);
            sb.append("    @org.junit.jupiter.api.Test\n");
            sb.append("    void case0000() {\n");
            for (Scenario.Input input : inputs) {
                sb.append("        ").append(input.type()).append(' ').append(input.name()).append(" = ")
                        .append(literal(input, values.get(input.name()))).append(";\n");
            }
            sb.append("        runCase(0, null");
            for (Scenario.Input input : inputs) {
                sb.append(", ").append(input.name());
            }
            sb.append(");\n    }\n\n");
        }

        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            // A scenario with no searchable inputs still deserves to be executed once: a fixed
            // sequence of calls is a perfectly good experiment.
            sb.append("    @org.junit.jupiter.api.Test\n");
            sb.append("    void singleCase() {\n");
            sb.append("        runCase(0, null);\n");
            sb.append("    }\n\n");
        }

        // --- randomised search (and concurrency), driven by seed and optional case pinning
        boolean random = scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1;
        if (random && !inputs.isEmpty()) {
            if (scenario.mode() == Scenario.Mode.CONCURRENT) {
                sb.append(concurrentTest(inputs, scenario));
            } else {
                sb.append(randomTest(inputs, scenario));
            }
        }

        // --- runner body
        sb.append(runnerBody(inputs, scenario));

        // --- support helpers
        sb.append(supportMethods(inputs));
        sb.append("}\n");
        return sb.toString();
    }

    private String randomTest(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("    /**\n");
        sb.append("     * Seeded random search over the declared domains.\n");
        sb.append("     * Override with -Dcodewitness.seed=<seed> -Dcodewitness.case=<index> to replay one case.\n");
        sb.append("     */\n");
        sb.append("    @org.junit.jupiter.api.Test\n");
        sb.append("    void randomSearch() {\n");
        sb.append("        long seed = cwSeed(").append(scenario.seed()).append("L);\n");
        sb.append("        int cases = cwCases(").append(scenario.cases()).append(");\n");
        sb.append("        int pinned = cwPinnedCase();\n");
        sb.append("        for (int cwCase = 0; cwCase < cases; cwCase++) {\n");
        sb.append("            if (pinned >= 0 && cwCase != pinned) { continue; }\n");
        sb.append("            java.util.Random rnd = new java.util.Random(seed + cwCase);\n");
        for (Scenario.Input input : inputs) {
            sb.append("            ").append(input.type()).append(' ').append(input.name()).append(" = ")
                    .append(drawExpression(input)).append(";\n");
        }
        sb.append("            runCase(cwCase, seed");
        for (Scenario.Input input : inputs) {
            sb.append(", ").append(input.name());
        }
        sb.append(");\n        }\n    }\n\n");
        return sb.toString();
    }

    private String concurrentTest(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("    /**\n");
        sb.append("     * Executes the declared steps concurrently against one shared subject and then checks the\n");
        sb.append("     * invariant. A failure here is a counterexample: the requirement does not hold when the\n");
        sb.append("     * operations interleave.\n");
        sb.append("     */\n");
        sb.append("    @org.junit.jupiter.api.Test\n");
        sb.append("    void concurrentSearch() {\n");
        sb.append("        long seed = cwSeed(").append(scenario.seed()).append("L);\n");
        sb.append("        int cases = cwCases(").append(scenario.cases()).append(");\n");
        sb.append("        int pinned = cwPinnedCase();\n");
        sb.append("        for (int cwCase = 0; cwCase < cases; cwCase++) {\n");
        sb.append("            if (pinned >= 0 && cwCase != pinned) { continue; }\n");
        sb.append("            java.util.Random rnd = new java.util.Random(seed + cwCase);\n");
        for (Scenario.Input input : inputs) {
            sb.append("            ").append(input.type()).append(' ').append(input.name()).append(" = ")
                    .append(drawExpression(input)).append(";\n");
        }
        sb.append("            java.util.LinkedHashMap<String, Object> cwInputs = cwInputs(");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(inputs.get(i).name()).append("\", ")
                    .append(inputs.get(i).name());
        }
        sb.append(");\n");
        sb.append("            try {\n");
        sb.append(scenario.setup() == null ? "" : "                " + scenario.setup() + "\n");
        sb.append("                java.util.concurrent.ExecutorService cwPool = java.util.concurrent.Executors.newFixedThreadPool(")
                .append(CONCURRENT_THREADS).append(");\n");
        sb.append("                // All threads are released together: a race that only appears when calls genuinely\n");
        sb.append("                // overlap must not be hidden by thread start-up skew.\n");
        sb.append("                final java.util.concurrent.CountDownLatch cwStart = new java.util.concurrent.CountDownLatch(1);\n");
        sb.append("                java.util.List<java.util.concurrent.Future<?>> cwTasks = new java.util.ArrayList<>();\n");
        sb.append("                for (int cwThread = 0; cwThread < ").append(CONCURRENT_THREADS)
                .append("; cwThread++) {\n");
        sb.append("                    cwTasks.add(cwPool.submit(() -> {\n");
        sb.append("                        cwStart.await();\n");
        for (Scenario.Step step : scenario.steps()) {
            sb.append("                        ").append(substitute(step.call(), inputs)).append(";\n");
        }
        sb.append("                        return null;\n");
        sb.append("                    }));\n");
        sb.append("                }\n");
        sb.append("                cwStart.countDown();\n");
        sb.append("                for (java.util.concurrent.Future<?> cwTask : cwTasks) { cwTask.get(); }\n");
        sb.append("                cwPool.shutdown();\n");
        if (scenario.observation() != null && scenario.invariant() != null) {
            sb.append("                var observation = ").append(substitute(scenario.observation(), inputs)).append(";\n");
            sb.append("                if (!(").append(substitute(scenario.invariant(), inputs))
                    .append(")) { cwFail(cwCase, seed, cwInputs, \"")
                    .append(escape(oneLine(scenario.invariant())))
                    .append("\", String.valueOf(observation), \"concurrent execution\"); }\n");
        }
        sb.append("            } catch (AssertionError cwAssertion) {\n                throw cwAssertion;\n");
        sb.append("            } catch (Throwable cwError) {\n");
        sb.append("                cwFail(cwCase, seed, cwInputs, \"the declared steps threw "
                + "\" + cwError, cwError.toString(), \"concurrent execution\");\n            }\n");
        sb.append("        }\n    }\n\n");
        return sb.toString();
    }

    private String runnerBody(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("    private void runCase(int cwCase, Long cwSeed");
        for (Scenario.Input input : inputs) {
            sb.append(", ").append(input.type()).append(' ').append(input.name());
        }
        sb.append(") {\n");
        sb.append("        java.util.LinkedHashMap<String, Object> cwInputs = cwInputs(");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(inputs.get(i).name()).append("\", ")
                    .append(inputs.get(i).name());
        }
        sb.append(");\n");
        sb.append("        try {\n");
        if (scenario.setup() != null) {
            sb.append("            ").append(scenario.setup()).append("\n");
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append("            ").append(substitute(step.call(), inputs)).append(";\n");
            if (scenario.observation() != null && scenario.invariant() != null) {
                sb.append("            {\n");
                sb.append("                var observation = ").append(substitute(scenario.observation(), inputs))
                        .append(";\n");
                sb.append("                if (!(").append(substitute(scenario.invariant(), inputs)).append(")) {\n");
                sb.append("                    cwFail(cwCase, cwSeed, cwInputs, \"")
                        .append(escape(oneLine(scenario.invariant())))
                        .append("\", String.valueOf(observation), \"")
                        .append(escape(oneLine(step.description() == null ? step.call() : step.description())))
                        .append("\");\n");
                sb.append("                }\n");
                sb.append("            }\n");
            }
        }
        sb.append("        } catch (AssertionError cwAssertion) {\n            throw cwAssertion;\n");
        sb.append("        } catch (Throwable cwError) {\n");
        sb.append("            cwFail(cwCase, cwSeed, cwInputs, \"the declared steps threw "
                + "\" + cwError, cwError.toString(), \"");
        sb.append(escape(oneLine(scenario.observation() == null ? "step execution" : scenario.observation())));
        sb.append("\");\n        }\n    }\n\n");
        return sb.toString();
    }

    private String supportMethods(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("    private static java.util.LinkedHashMap<String, Object> cwInputs(");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ");
            sb.append("String cwName").append(i).append(", Object cwValue").append(i);
        }
        sb.append(") {\n        java.util.LinkedHashMap<String, Object> cwMap = new java.util.LinkedHashMap<>();\n");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append("        cwMap.put(cwName").append(i).append(", cwValue").append(i).append(");\n");
        }
        sb.append("        return cwMap;\n    }\n\n");

        sb.append("    private static String cwProtocol(int cwCase, Long cwSeed, java.util.Map<String, Object> cwInputs,\n");
        sb.append("            String cwExpected, String cwActual, String cwStep) {\n");
        sb.append("        java.util.LinkedHashMap<String, String> cwFields = new java.util.LinkedHashMap<>();\n");
        sb.append("        cwFields.put(\"case\", String.valueOf(cwCase));\n");
        sb.append("        if (cwSeed != null) { cwFields.put(\"seed\", String.valueOf(cwSeed)); }\n");
        sb.append("        cwFields.put(\"inputs\", cwRenderInputs(cwInputs));\n");
        sb.append("        cwFields.put(\"expected\", cwExpected);\n");
        sb.append("        cwFields.put(\"actual\", cwActual);\n");
        sb.append("        cwFields.put(\"invariant\", CW_INVARIANT);\n");
        sb.append("        cwFields.put(\"step\", cwStep);\n");
        sb.append("        cwFields.put(\"requirement\", CW_REQUIREMENT);\n");
        sb.append("        cwFields.put(\"obligation\", CW_OBLIGATION);\n");
        sb.append("        StringBuilder cwLine = new StringBuilder(\"").append(CounterexampleProtocol.MARKER).append("\");\n");
        sb.append("        for (java.util.Map.Entry<String, String> cwEntry : cwFields.entrySet()) {\n");
        sb.append("            cwLine.append('|').append(cwEntry.getKey()).append('=')\n");
        sb.append("                    .append(cwEntry.getValue() == null ? \"\" : cwEntry.getValue()\n");
        sb.append("                            .replace('|', '/').replace('\\n', ' '));\n");
        sb.append("        }\n        return cwLine.toString();\n    }\n\n");

        sb.append("    private static String cwRenderInputs(java.util.Map<String, Object> cwInputs) {\n");
        sb.append("        StringBuilder cwText = new StringBuilder(\"{\");\n");
        sb.append("        boolean cwFirst = true;\n");
        sb.append("        for (java.util.Map.Entry<String, Object> cwEntry : cwInputs.entrySet()) {\n");
        sb.append("            if (!cwFirst) { cwText.append(\", \"); }\n");
        sb.append("            cwFirst = false;\n");
        sb.append("            cwText.append(cwEntry.getKey()).append('=').append(cwEntry.getValue());\n");
        sb.append("        }\n        return cwText.append('}').toString();\n    }\n\n");

        sb.append("    private static void cwFail(int cwCase, Long cwSeed, java.util.Map<String, Object> cwInputs,\n");
        sb.append("            String cwExpected, String cwActual, String cwStep) {\n");
        sb.append("        org.junit.jupiter.api.Assertions.fail(\n");
        sb.append("                cwProtocol(cwCase, cwSeed, cwInputs, cwExpected, cwActual, cwStep));\n    }\n\n");

        sb.append("""
                    private static long cwSeed(long fallback) {
                        String property = System.getProperty("codewitness.seed");
                        if (property == null || property.isBlank()) {
                            property = System.getenv("CODEWITNESS_SEED");
                        }
                        if (property == null || property.isBlank()) {
                            return fallback;
                        }
                        try {
                            return Long.parseLong(property.trim());
                        } catch (NumberFormatException e) {
                            return fallback;
                        }
                    }

                    private static int cwCases(int fallback) {
                        String property = System.getProperty("codewitness.cases");
                        if (property == null || property.isBlank()) {
                            return fallback;
                        }
                        try {
                            return Integer.parseInt(property.trim());
                        } catch (NumberFormatException e) {
                            return fallback;
                        }
                    }

                    private static int cwPinnedCase() {
                        String property = System.getProperty("codewitness.case");
                        if (property == null || property.isBlank()) {
                            property = System.getenv("CODEWITNESS_CASE");
                        }
                        if (property == null || property.isBlank()) {
                            return -1;
                        }
                        try {
                            return Integer.parseInt(property.trim());
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                    }

                    private static long cwLong(java.util.Random rnd, long min, long max) {
                        if (max <= min) {
                            return min;
                        }
                        long span = max - min;
                        if (span <= 0) {
                            return min + (long) (rnd.nextDouble() * (max / 2.0));
                        }
                        return min + (long) Math.floor(rnd.nextDouble() * (span + 1));
                    }

                    private static int cwInt(java.util.Random rnd, int min, int max) {
                        return (int) cwLong(rnd, min, max);
                    }

                    private static double cwDouble(java.util.Random rnd, double min, double max) {
                        return min + rnd.nextDouble() * (max - min);
                    }

                    private static java.math.BigDecimal cwDecimal(java.util.Random rnd, String min, String max, int scale) {
                        java.math.BigDecimal lo = new java.math.BigDecimal(min);
                        java.math.BigDecimal hi = new java.math.BigDecimal(max);
                        java.math.BigDecimal span = hi.subtract(lo);
                        java.math.BigDecimal value = lo.add(span.multiply(java.math.BigDecimal.valueOf(rnd.nextDouble())));
                        return value.setScale(scale, java.math.RoundingMode.HALF_UP);
                    }

                    private static java.time.Instant cwInstant(java.util.Random rnd, long min, long max) {
                        return java.time.Instant.ofEpochMilli(cwLong(rnd, min, max));
                    }

                    private static String cwString(java.util.Random rnd, String prefix) {
                        return prefix + rnd.nextInt(10_000);
                    }
                    """);
        sb.append('\n');
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
     * @return one value per input, taken from the declared boundary values when there are any and
     *     from the type's default otherwise. Used when a scenario declares inputs but no boundary
     *     values, so that it still produces an executed case.
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
            case DECIMAL -> "new java.math.BigDecimal(\"" + escape(value) + "\")";
            case INTEGER -> value + "";
            case LONG -> value + "L";
            case DOUBLE -> value + "d";
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(value));
            case STRING -> "\"" + escape(value) + "\"";
            case INSTANT_MILLIS -> "java.time.Instant.ofEpochMilli(" + value + "L)";
            case NULL -> "null";
            case RAW -> value;
        };
    }

    private static String defaultLiteral(Scenario.Input input) {
        return switch (input.domain()) {
            case DECIMAL -> "0";
            case INTEGER, LONG, INSTANT_MILLIS -> "0";
            case DOUBLE -> "0";
            case BOOLEAN -> "false";
            case STRING -> "";
            case NULL -> "null";
            case RAW -> "null";
        };
    }

    private static String drawExpression(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (input.domain()) {
            case DECIMAL -> "cwDecimal(rnd, \"" + (min == null ? "0" : escape(min)) + "\", \""
                    + (max == null ? "10000" : escape(max)) + "\", " + Math.max(0, input.scale()) + ")";
            case INTEGER -> "cwInt(rnd, " + (min == null ? "0" : min) + ", " + (max == null ? "10000" : max) + ")";
            case LONG -> "cwLong(rnd, " + (min == null ? "0" : min) + "L, " + (max == null ? "10000" : max) + "L)";
            case DOUBLE -> "cwDouble(rnd, " + (min == null ? "0" : min) + "d, " + (max == null ? "1000" : max) + "d)";
            case BOOLEAN -> "rnd.nextBoolean()";
            case STRING -> "cwString(rnd, \"cw-\")";
            case INSTANT_MILLIS -> "cwInstant(rnd, " + (min == null ? "0" : min) + "L, "
                    + (max == null ? "86400000" : max) + "L)";
            case NULL -> "null";
            case RAW -> input.values().isEmpty() ? "null" : input.values().getFirst();
        };
    }

    private static String substitute(String javaFragment, List<Scenario.Input> inputs) {
        String result = javaFragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("${" + input.name() + "}", input.name());
        }
        return result;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    /** @return the distinct input names of a scenario, used by tests. */
    public static Set<String> inputNames(Scenario scenario) {
        Set<String> names = new LinkedHashSet<>();
        for (Scenario.Step step : scenario.steps()) {
            step.inputs().forEach(input -> names.add(input.name()));
        }
        return names;
    }
}

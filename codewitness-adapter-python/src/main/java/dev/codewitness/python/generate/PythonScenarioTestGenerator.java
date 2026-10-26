package dev.codewitness.python.generate;

import dev.codewitness.core.config.Scenario;
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
 * Turns declared scenarios into executable standard-library tests.
 *
 * <p>A scenario supplies the oracle - the setup, the calls and the invariant, written in Python in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the
 * seeded random search, concurrent execution and the structured failure report. CodeWitness never
 * invents an oracle, because an invented oracle is exactly the kind of assertion this tool exists
 * to avoid.
 *
 * <p>Generated tests are {@code unittest.TestCase} classes. That choice is deliberate: pytest
 * collects them without any plugin, and the standard library runner executes them when pytest is not
 * installed, so a scenario is executable on any Python interpreter.
 *
 * <p>A failing case raises an {@code AssertionError} whose message is a single
 * {@code CODEWITNESS_COUNTEREXAMPLE|...} line carrying the exact inputs, seed and case index, which
 * is what makes the counterexample reproducible.
 */
public final class PythonScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Default number of threads used by CONCURRENT scenarios. */
    private static final int CONCURRENT_THREADS = 8;

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    @Override
    public String id() {
        return "python-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
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
            if (scenario.mode() == Scenario.Mode.PROCESS) {
                // A process scenario has no steps, no observation and no invariant, so the step-based renderer below
                // would emit a test with an empty body - syntactically invalid Python that the runner then fails to
                // import. The engine executes it unless the project asked for a native test, in which case this
                // adapter writes one its own runner can execute.
                if (scenario.nativeTest()) {
                    generated.add(processTest(context, requirement, obligation, scenario));
                }
                continue;
            }
            String className = classNameFor(scenario, obligation);
            String source = render(context, requirement, obligation, scenario, className);
            generated.add(new GeneratedTest(className, source, testMethod(scenario),
                    List.of(requirement.id()), List.of(obligation.id()),
                    scenario.description(), scenario.seed(), null,
                    scenario.observation() != null && scenario.invariant() != null));
        }
        return generated;
    }

    /**
     * Renders a standard-library `unittest` case that runs the declared command and asserts on it.
     *
     * <p>The same assertion the engine makes, written into a file the project can keep: it runs the command with
     * `subprocess.run`, compares the exit code and both streams, and on a violation prints the shared
     * `CODEWITNESS_COUNTEREXAMPLE|…` line before failing, so the counterexample carries data rather than a stack
     * trace.
     *
     * <p>`${project_root}` becomes the directory the test runs in. A generated test cannot know the absolute path of
     * the checkout it will eventually live in, and baking one in would make it pass on the machine that generated it
     * and nowhere else.
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
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by CodeWitness ").append(dev.codewitness.core.engine.VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("# Requirement: ").append(requirement.id()).append(" - ").append(oneLine(requirement.title()))
                .append('\n');
        sb.append("# Scenario: ").append(scenario.id()).append(" (mode process). This one is yours to keep: it\n");
        sb.append("# asserts what the scenario declared, in your own framework, so the guarantee survives without\n");
        sb.append("# CodeWitness being present.\n\n");
        sb.append("import subprocess\nimport unittest\n\n\n");
        sb.append("CW_REQUIREMENT = ").append(pyString(requirement.id())).append('\n');
        sb.append("CW_OBLIGATION = ").append(pyString(obligation.id())).append('\n');
        sb.append("CW_EXIT_CODE = ").append(expect.exitCode() == null ? "None" : expect.exitCode()).append('\n');
        sb.append("CW_STDOUT = ").append(pyStringList(expect.stdoutContains())).append('\n');
        sb.append("CW_STDERR = ").append(pyStringList(expect.stderrContains())).append('\n');
        sb.append("CW_TIMEOUT = ").append(scenario.timeoutSeconds()).append("\n\n\n");
        sb.append("class Cw_").append(sanitize(scenario.id())).append('_').append(sanitize(obligation.id()))
                .append("(unittest.TestCase):\n\n");
        sb.append("    def test_process(self):\n");
        sb.append("        import os\n");
        sb.append("        command = [part.replace(\"${project_root}\", os.getcwd())\n");
        sb.append("                   for part in ").append(pyStringList(scenario.command())).append("]\n");
        sb.append("        try:\n");
        sb.append("            result = subprocess.run(command, capture_output=True, text=True, timeout=CW_TIMEOUT)\n");
        sb.append("        except (OSError, subprocess.SubprocessError) as cw_error:\n");
        sb.append("            self.fail(\"the declared command could not be started: %s\" % cw_error)\n");
        sb.append("        problems = []\n");
        sb.append("        if CW_EXIT_CODE is not None and result.returncode != CW_EXIT_CODE:\n");
        sb.append("            problems.append(\"exit code was %d, required %d\" % (result.returncode, CW_EXIT_CODE))\n");
        sb.append("        for required in CW_STDOUT:\n");
        sb.append("            if required not in result.stdout:\n");
        sb.append("                problems.append('standard output did not contain \"%s\"' % required)\n");
        sb.append("        for required in CW_STDERR:\n");
        sb.append("            if required not in result.stderr:\n");
        sb.append("                problems.append('standard error did not contain \"%s\"' % required)\n");
        sb.append("        if problems:\n");
        sb.append("            print(\"CODEWITNESS_COUNTEREXAMPLE|case=0|seed=|inputs=\" + \" \".join(command)\n");
        sb.append("                  + \"|expected=\" + str(CW_EXIT_CODE) + \"|actual=\" + \"; \".join(problems)\n");
        sb.append("                  + \"|requirement=\" + CW_REQUIREMENT + \"|obligation=\" + CW_OBLIGATION)\n");
        sb.append("            self.fail(\"; \".join(problems))\n");
        return new GeneratedTest("test_cw_" + sanitize(scenario.id()) + "_" + sanitize(obligation.id()),
                sb.toString(), "test_process", List.of(requirement.id()), List.of(obligation.id()),
                scenario.description(), null, null, true);
    }

    /**
     * @param values strings to render as a Python list literal
     * @return the literal
     */
    private static String pyStringList(List<String> values) {
        return values.stream().map(PythonScenarioTestGenerator::pyString)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String testMethod(Scenario scenario) {
        return scenario.mode() == Scenario.Mode.CONCURRENT ? "test_concurrent_search" : "test_random_search";
    }

    private static String classNameFor(Scenario scenario, VerificationObligation obligation) {
        String suffix = scenario.mode() == Scenario.Mode.CONCURRENT ? "_concurrency" : "";
        return "test_cw_" + sanitize(scenario.id()) + "_" + sanitize(obligation.id()) + suffix;
    }

    private static String sanitize(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
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
        String classTitle = "Cw_" + sanitize(scenario.id()) + "_" + sanitize(obligation.id());
        StringBuilder sb = new StringBuilder();

        sb.append("# Generated by CodeWitness ").append(dev.codewitness.core.engine.VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("# Requirement: ").append(requirement.id()).append(" - ").append(oneLine(requirement.title())).append('\n');
        sb.append("# Obligation: ").append(obligation.id()).append(" - ").append(oneLine(obligation.description())).append('\n');
        sb.append("# Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases()).append(")\n");
        sb.append("# This file is temporary: CodeWitness keeps it with the run so a counterexample can be replayed.\n");
        sb.append("# It uses only the standard library, so it runs whether or not pytest is installed.\n\n");
        sb.append("import decimal\n");
        sb.append("import random\n");
        sb.append("import threading\n");
        sb.append("import unittest\n\n");
        sb.append("CW_REQUIREMENT = ").append(pyString(requirement.id())).append('\n');
        sb.append("CW_OBLIGATION = ").append(pyString(obligation.id())).append('\n');
        sb.append("CW_INVARIANT = ")
                .append(pyString(oneLine(scenario.invariant() == null ? "(no invariant declared)" : scenario.invariant())))
                .append("\n\n\n");
        sb.append("class ").append(classTitle).append("(unittest.TestCase):\n\n");

        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            sb.append("    def test_case").append(String.format(Locale.ROOT, "%04d", index)).append("(self):\n");
            for (Scenario.Input input : inputs) {
                sb.append("        ").append(input.name()).append(" = ")
                        .append(literal(input, values.get(input.name()))).append('\n');
            }
            sb.append("        self.run_case(").append(index).append(", None, ")
                    .append(inputsLiteral(inputs)).append(inputsSuffix(inputs)).append(")\n\n");
            index++;
        }

        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input has a declared type but none declares a boundary value, so there is nothing
            // to enumerate. Run the declared defaults once instead of emitting a class with no test
            // methods at all: an empty test class reports nothing, and a scenario that reports nothing
            // must not be mistaken for one that ran and passed.
            Map<String, String> values = defaultValues(inputs);
            sb.append("    def test_case0000(self):\n");
            for (Scenario.Input input : inputs) {
                sb.append("        ").append(input.name()).append(" = ")
                        .append(literal(input, values.get(input.name()))).append('\n');
            }
            sb.append("        self.run_case(0, None, ").append(inputsLiteral(inputs))
                    .append(inputsSuffix(inputs)).append(")\n\n");
        }

        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            sb.append("    def test_single_case(self):\n");
            sb.append("        self.run_case(0, None, {}");
            sb.append(")\n\n");
        }

        boolean random = scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1;
        if (random && !inputs.isEmpty()) {
            if (scenario.mode() == Scenario.Mode.CONCURRENT) {
                sb.append(concurrentMethod(inputs, scenario));
            } else {
                sb.append(randomMethod(inputs, scenario));
            }
        }

        sb.append(runnerMethod(inputs, scenario));
        sb.append(helpers());
        return sb.toString();
    }

    private static String inputsLiteral(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(pyString(inputs.get(i).name())).append(": ")
                    .append(inputs.get(i).name());
        }
        return sb.append('}').toString();
    }

    private static String inputsSuffix(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder();
        for (Scenario.Input input : inputs) {
            sb.append(", ").append(input.name());
        }
        return sb.toString();
    }

    private static String randomMethod(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("    def test_random_search(self):\n");
        sb.append("        \"\"\"Seeded random search; pin one case with CODEWITNESS_SEED/CODEWITNESS_CASE.\"\"\"\n");
        sb.append("        seed_value = cw_seed(").append(scenario.seed()).append(")\n");
        sb.append("        case_count = cw_cases(").append(scenario.cases()).append(")\n");
        sb.append("        pinned = cw_pinned_case()\n");
        sb.append("        for cw_case in range(case_count):\n");
        sb.append("            if pinned >= 0 and cw_case != pinned:\n");
        sb.append("                continue\n");
        sb.append("            rnd = random.Random(seed_value + cw_case)\n");
        for (Scenario.Input input : inputs) {
            sb.append("            ").append(input.name()).append(" = ").append(drawExpression(input)).append('\n');
        }
        sb.append("            self.run_case(cw_case, seed_value, ").append(inputsLiteral(inputs))
                .append(inputsSuffix(inputs)).append(")\n\n");
        return sb.toString();
    }

    private static String concurrentMethod(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("    def test_concurrent_search(self):\n");
        sb.append("        \"\"\"Executes the steps from several threads at once, then checks the invariant.\"\"\"\n");
        sb.append("        seed_value = cw_seed(").append(scenario.seed()).append(")\n");
        sb.append("        case_count = cw_cases(").append(scenario.cases()).append(")\n");
        sb.append("        pinned = cw_pinned_case()\n");
        sb.append("        for cw_case in range(case_count):\n");
        sb.append("            if pinned >= 0 and cw_case != pinned:\n");
        sb.append("                continue\n");
        sb.append("            rnd = random.Random(seed_value + cw_case)\n");
        for (Scenario.Input input : inputs) {
            sb.append("            ").append(input.name()).append(" = ").append(drawExpression(input)).append('\n');
        }
        sb.append("            cw_inputs = ").append(inputsLiteral(inputs)).append('\n');
        sb.append("            try:\n");
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), 16)).append('\n');
        }
        sb.append("                barrier = threading.Barrier(").append(CONCURRENT_THREADS).append(")\n");
        sb.append("                errors = []\n\n");
        sb.append("                def cw_worker():\n");
        sb.append("                    barrier.wait()\n");
        for (Scenario.Step step : scenario.steps()) {
            sb.append("                    ").append(substitute(step.call(), inputs)).append('\n');
        }
        sb.append("\n                threads = [threading.Thread(target=cw_worker) for _ in range(")
                .append(CONCURRENT_THREADS).append(")]\n");
        sb.append("                for thread in threads:\n");
        sb.append("                    thread.start()\n");
        sb.append("                for thread in threads:\n");
        sb.append("                    thread.join()\n");
        if (scenario.observation() != null && scenario.invariant() != null) {
            sb.append("                observation = ").append(substitute(scenario.observation(), inputs)).append('\n');
            sb.append("                if not (").append(substitute(scenario.invariant(), inputs)).append("):\n");
            sb.append("                    cw_fail(cw_case, seed_value, cw_inputs, ")
                    .append(pyString(oneLine(scenario.invariant())))
                    .append(", str(observation), \"concurrent execution\")\n");
        }
        sb.append("            except AssertionError:\n");
        sb.append("                raise\n");
        sb.append("            except BaseException as cw_error:\n");
        sb.append("                cw_fail(cw_case, seed_value, cw_inputs, \"the declared steps threw \"\n");
        sb.append("                        + type(cw_error).__name__, str(cw_error), \"concurrent execution\")\n\n");
        return sb.toString();
    }

    private static String runnerMethod(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("    def run_case(self, cw_case, cw_seed_value, cw_inputs");
        for (Scenario.Input input : inputs) {
            sb.append(", ").append(input.name());
        }
        sb.append("):\n");
        sb.append("        try:\n");
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), 12)).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append("            ").append(substitute(step.call(), inputs)).append('\n');
            if (scenario.observation() != null && scenario.invariant() != null) {
                sb.append("            observation = ").append(substitute(scenario.observation(), inputs)).append('\n');
                sb.append("            if not (").append(substitute(scenario.invariant(), inputs)).append("):\n");
                sb.append("                cw_fail(cw_case, cw_seed_value, cw_inputs, ")
                        .append(pyString(oneLine(scenario.invariant())))
                        .append(", str(observation), ")
                        .append(pyString(oneLine(step.description() == null ? step.call() : step.description())))
                        .append(")\n");
            }
        }
        sb.append("        except AssertionError:\n");
        sb.append("            raise\n");
        sb.append("        except BaseException as cw_error:\n");
        sb.append("            cw_fail(cw_case, cw_seed_value, cw_inputs, \"the declared steps threw \"\n");
        sb.append("                    + type(cw_error).__name__, str(cw_error), ")
                .append(pyString(oneLine(substitute(
                        scenario.observation() == null ? "step execution" : scenario.observation(), inputs))))
                .append(")\n\n");
        return sb.toString();
    }

    private static String helpers() {
        return """
                    def cw_seed(fallback):
                        value = __import__("os").environ.get("CODEWITNESS_SEED")
                        if value is None or not value.strip():
                            return fallback
                        try:
                            return int(value.strip())
                        except ValueError:
                            return fallback


                    def cw_cases(fallback):
                        value = __import__("os").environ.get("CODEWITNESS_CASES")
                        if value is None or not value.strip():
                            return fallback
                        try:
                            return int(value.strip())
                        except ValueError:
                            return fallback


                    def cw_pinned_case():
                        value = __import__("os").environ.get("CODEWITNESS_CASE")
                        if value is None or not value.strip():
                            return -1
                        try:
                            return int(value.strip())
                        except ValueError:
                            return -1


                    def cw_int(rnd, minimum, maximum):
                        if maximum <= minimum:
                            return minimum
                        return rnd.randint(minimum, maximum)


                    def cw_double(rnd, minimum, maximum):
                        if maximum <= minimum:
                            return minimum
                        return rnd.uniform(minimum, maximum)


                    def cw_decimal(rnd, minimum, maximum, scale):
                        lo = decimal.Decimal(minimum)
                        hi = decimal.Decimal(maximum)
                        value = lo + (hi - lo) * decimal.Decimal(str(rnd.random()))
                        exponent = decimal.Decimal(1).scaleb(-scale)
                        return value.quantize(exponent, rounding=decimal.ROUND_HALF_UP)


                    def cw_string(rnd, prefix):
                        return prefix + str(rnd.randint(0, 9999))


                    def cw_render_inputs(inputs):
                        return "{" + ", ".join("%s=%s" % (key, value) for key, value in inputs.items()) + "}"


                    def cw_protocol(case, seed_value, inputs, expected, actual, step):
                        fields = [
                            ("case", str(case)),
                            ("seed", "" if seed_value is None else str(seed_value)),
                            ("inputs", cw_render_inputs(inputs)),
                            ("expected", expected),
                            ("actual", actual),
                            ("invariant", CW_INVARIANT),
                            ("step", step),
                            ("requirement", CW_REQUIREMENT),
                            ("obligation", CW_OBLIGATION),
                        ]
                        text = "CODEWITNESS_COUNTEREXAMPLE"
                        for key, value in fields:
                            safe = "" if value is None else str(value).replace("|", "/").replace(chr(10), " ")
                            text += "|" + key + "=" + safe
                        return text


                    def cw_fail(case, seed_value, inputs, expected, actual, step):
                        raise AssertionError(cw_protocol(case, seed_value, inputs, expected, actual, step))
                    """;
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
     * @return one value per input, taken from the declared values when there are any and from the
     *     type's default otherwise. Used when a scenario declares inputs but no boundary values, so
     *     that it still produces an executed case.
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
            case DECIMAL -> "decimal.Decimal(" + pyString(value) + ")";
            case INTEGER, LONG, INSTANT_MILLIS -> value.isBlank() ? "0" : value;
            case DOUBLE -> value.isBlank() ? "0.0" : value;
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(value));
            case STRING -> pyString(value);
            case NULL -> "None";
            case RAW -> value.isBlank() ? "None" : value;
        };
    }

    private static String defaultLiteral(Scenario.Input input) {
        return switch (input.domain()) {
            case DECIMAL -> "0";
            case STRING -> "";
            case INTEGER, LONG, INSTANT_MILLIS, DOUBLE -> "0";
            case BOOLEAN -> "false";
            case NULL, RAW -> "null";
        };
    }

    private static String drawExpression(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (input.domain()) {
            case DECIMAL -> "cw_decimal(rnd, " + pyString(min == null ? "0" : min) + ", "
                    + pyString(max == null ? "10000" : max) + ", " + Math.max(0, input.scale()) + ")";
            case INTEGER, LONG -> "cw_int(rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "10000" : max) + ")";
            case DOUBLE -> "cw_double(rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "1000" : max) + ")";
            case BOOLEAN -> "rnd.choice([True, False])";
            case STRING -> "cw_string(rnd, \"cw-\")";
            case INSTANT_MILLIS -> "cw_int(rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "86400000" : max) + ")";
            case NULL -> "None";
            case RAW -> input.values().isEmpty() ? "None" : input.values().getFirst();
        };
    }

    private static String substitute(String fragment, List<Scenario.Input> inputs) {
        String result = fragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("${" + input.name() + "}", input.name());
        }
        return result;
    }

    /** Indents every non-blank line of a block so user-authored statements fit the generated body. */
    private static String indent(String block, int spaces) {
        String prefix = " ".repeat(spaces);
        StringBuilder sb = new StringBuilder();
        String[] lines = block.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].isBlank() ? lines[i] : prefix + lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Renders a Python string literal, including the surrounding quotes. */
    private static String pyString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }
}

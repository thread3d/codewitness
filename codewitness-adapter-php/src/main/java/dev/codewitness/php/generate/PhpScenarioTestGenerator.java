package dev.codewitness.php.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.VerificationEngine;
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
 * Turns declared scenarios into executable PHPUnit test classes.
 *
 * <p>A scenario supplies the oracle - the setup, the calls and the invariant, written in PHP in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the seeded
 * random search and the structured failure report. CodeWitness never invents an oracle, because an
 * invented oracle is exactly the kind of assertion this tool exists to avoid.
 *
 * <p>The generated class is a PHPUnit {@code TestCase} written into the project's own test root, and it
 * must be: PHP has no notion of a test tree discovered by name alone, so a class only runs if the
 * project's PHPUnit configuration scans it and the project's autoloader can load it. That also means the
 * generated file is deleted by nothing - the replay command in a counterexample is a real PHPUnit
 * invocation, and a deleted file would make that command a lie. The run keeps a copy as an artefact as
 * well.
 *
 * <p>Each case is one test method, so a failure is attributed by the ordinary JUnit report rather than by
 * a line number a reader has to interpret. A failing case fails through PHPUnit's own
 * {@code fail()}, whose message is a single {@code CODEWITNESS_COUNTEREXAMPLE|...} line carrying the exact
 * inputs, the seed, the case index, the observed value and the invariant; that line is what the engine
 * decodes out of the JUnit {@code <failure>} text.
 *
 * <p>Stated limit: PHP's standard build has no threads, so a scenario declared {@code concurrent} has its
 * step list executed once, in order, and the generated file says so. The test still executes the declared
 * steps and checks the declared invariant, but it demonstrates nothing about interleaving.
 */
public final class PhpScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** The PHPUnit base class, fully qualified so a generated class needs no namespace and no import. */
    private static final String TEST_CASE = "\\PHPUnit\\Framework\\TestCase";

    /** PHPUnit's assertion failure, which the runner's own {@code fail()} throws. */
    private static final String ASSERTION_FAILED = "\\PHPUnit\\Framework\\AssertionFailedError";

    @Override
    public String id() {
        return "php-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        // The class name identifies the scenario, not the obligation, so two obligations that share one
        // scenario would produce two classes with one name - and the executor writes them to one file.
        // Grouping first means the one class carries both obligations and neither is silently lost.
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        Map<String, List<VerificationObligation>> covering = new LinkedHashMap<>();
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
            scenarios.put(scenarioId, scenario);
            covering.computeIfAbsent(scenarioId, key -> new ArrayList<>()).add(obligation);
        }

        List<GeneratedTest> generated = new ArrayList<>();
        for (Map.Entry<String, List<VerificationObligation>> entry : covering.entrySet()) {
            Scenario scenario = scenarios.get(entry.getKey());
            List<VerificationObligation> obligationsForScenario = entry.getValue();
            String className = classNameFor(scenario);
            String source = render(context, requirement, obligationsForScenario, scenario, className);
            generated.add(new GeneratedTest(
                    className,
                    source,
                    testMethod(scenario),
                    List.of(requirement.id()),
                    obligationsForScenario.stream().map(VerificationObligation::id).toList(),
                    scenario.description(),
                    scenario.seed(),
                    null,
                    scenario.observation() != null && scenario.invariant() != null));
        }
        return List.copyOf(generated);
    }

    /**
     * @return the PHP class name for a scenario: {@code CwSc<ScenarioId>Test}, with every character that
     *     cannot appear in a PHP identifier replaced by an underscore.
     *
     * <p>An underscore rather than a deletion, because {@code SC-A_B} and {@code SC-AB} must not become the
     *     same class: two scenarios in one run would then overwrite each other's generated file and one
     *     requirement would be judged by the other's experiment.
     */
    static String classNameFor(Scenario scenario) {
        return "CwSc" + identifier(scenario.id()) + "Test";
    }

    /** @return an ASCII identifier fragment, so the result is a valid PHP class name whatever the id was. */
    static String identifier(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            sb.append(ascii ? c : '_');
        }
        return sb.isEmpty() ? "Scenario" : sb.toString();
    }

    /**
     * @return the method that reproduces one case, named as the generated file names it.
     *
     * <p>The random search is the interesting case to replay when there is one; a scenario that declares
     * neither boundary values nor a search has only its single case, and naming a method that does not
     * exist would make the counterexample's own recipe wrong.
     */
    static String testMethod(Scenario scenario) {
        if (hasRandomSearch(scenario)) {
            return scenario.mode() == Scenario.Mode.CONCURRENT ? "test_concurrent_search" : "test_random_search";
        }
        return collectInputs(scenario).isEmpty() ? "test_single_case" : "test_case0000";
    }

    private static boolean hasRandomSearch(Scenario scenario) {
        return (scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1) && !collectInputs(scenario).isEmpty();
    }

    private String render(
            VerificationContext context,
            Requirement requirement,
            List<VerificationObligation> obligations,
            Scenario scenario,
            String className) {

        List<Scenario.Input> inputs = collectInputs(scenario);
        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        StringBuilder sb = new StringBuilder();

        sb.append("<?php\n\n");
        sb.append("// Generated by CodeWitness ").append(VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("// Requirement: ").append(requirement.id()).append(" - ").append(oneLine(requirement.title()))
                .append('\n');
        for (VerificationObligation obligation : obligations) {
            sb.append("// Obligation: ").append(obligation.id()).append(" - ")
                    .append(oneLine(obligation.description())).append('\n');
        }
        sb.append("// Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases()).append(")\n");
        sb.append("// This file is temporary: CodeWitness writes it into the project's own test root, because a\n");
        sb.append("// PHP project's autoloader and PHPUnit configuration are what make a class runnable, and the\n");
        sb.append("// run keeps a copy as an artefact. It is left in place so the replay command works.\n");
        if (scenario.mode() == Scenario.Mode.CONCURRENT) {
            sb.append(CONCURRENT_NOTE);
        }
        sb.append('\n');

        sb.append("final class ").append(className).append(" extends ").append(TEST_CASE).append("\n{\n");
        sb.append("    private const CW_REQUIREMENT = ").append(phpString(requirement.id())).append(";\n");
        sb.append("    private const CW_OBLIGATIONS = ")
                .append(phpString(String.join(", ",
                        obligations.stream().map(VerificationObligation::id).toList())))
                .append(";\n");
        sb.append("    private const CW_INVARIANT = ")
                .append(phpString(oneLine(scenario.invariant() == null
                        ? "(no invariant declared)" : scenario.invariant())))
                .append(";\n\n");

        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            sb.append(caseMethod(index, inputs, values));
            index++;
        }

        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input has a declared type but none declares a boundary value, so there is nothing to
            // enumerate. Run the declared defaults once instead of emitting a class with no test methods
            // at all: an empty test class reports nothing, and a scenario that reports nothing must not be
            // mistaken for one that ran and passed.
            sb.append(caseMethod(0, inputs, defaultValues(inputs)));
        }

        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            sb.append("    public function test_single_case(): void\n    {\n");
            sb.append("        $this->run_case(0, null, []);\n");
            sb.append("    }\n\n");
        }

        if (hasRandomSearch(scenario)) {
            sb.append(randomMethod(inputs, scenario));
        }

        sb.append(runnerMethod(inputs, scenario));
        sb.append(helpers());
        sb.append("}\n");
        return sb.toString();
    }

    /** @return one boundary case as a test method that assigns the case's values and runs it. */
    private static String caseMethod(int index, List<Scenario.Input> inputs, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public function test_case").append(String.format(Locale.ROOT, "%04d", index))
                .append("(): void\n    {\n");
        for (Scenario.Input input : inputs) {
            sb.append("        ").append(variable(input)).append(" = ")
                    .append(literal(input, values.get(input.name()))).append(";\n");
        }
        sb.append("        $this->run_case(").append(index).append(", null, ")
                .append(inputsLiteral(inputs)).append(inputsSuffix(inputs)).append(");\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private static String randomMethod(List<Scenario.Input> inputs, Scenario scenario) {
        String method = scenario.mode() == Scenario.Mode.CONCURRENT
                ? "test_concurrent_search" : "test_random_search";
        StringBuilder sb = new StringBuilder();
        sb.append("    public function ").append(method).append("(): void\n    {\n");
        sb.append("        // Seeded search; pin one case with CODEWITNESS_SEED / CODEWITNESS_CASE.\n");
        sb.append("        $cwSeed = self::cw_seed(").append(scenario.seed()).append(");\n");
        sb.append("        $cwCases = self::cw_cases(").append(scenario.cases()).append(");\n");
        sb.append("        $cwPinned = self::cw_pinned_case();\n");
        sb.append("        for ($cwCase = 0; $cwCase < $cwCases; $cwCase++) {\n");
        sb.append("            if ($cwPinned >= 0 && $cwCase !== $cwPinned) {\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        // PHP's Mersenne Twister is fixed by the interpreter's own specification, so one seed reproduces
        // one case on every PHP 8.1 or newer - which is what makes a counterexample replayable at all.
        sb.append("            mt_srand($cwSeed + $cwCase);\n");
        for (Scenario.Input input : inputs) {
            sb.append("            ").append(variable(input)).append(" = ")
                    .append(drawExpression(input)).append(";\n");
        }
        sb.append("            $this->run_case($cwCase, $cwSeed, ").append(inputsLiteral(inputs))
                .append(inputsSuffix(inputs)).append(");\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private static String runnerMethod(List<Scenario.Input> inputs, Scenario scenario) {
        boolean checks = scenario.observation() != null && scenario.invariant() != null;
        StringBuilder sb = new StringBuilder();
        sb.append("    private function run_case(int $cwCase, ?int $cwSeed, array $cwInputs");
        for (Scenario.Input input : inputs) {
            sb.append(", ").append(variable(input));
        }
        sb.append("): void\n    {\n");
        sb.append("        try {\n");
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), 12)).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(substitute(step.call(), inputs), 12)).append('\n');
            if (checks) {
                sb.append("            $observation = ").append(substitute(scenario.observation(), inputs))
                        .append(";\n");
                sb.append("            if (!(").append(substitute(scenario.invariant(), inputs)).append(")) {\n");
                sb.append("                self::cw_fail($cwCase, $cwSeed, $cwInputs, ")
                        .append(phpString(oneLine(scenario.invariant())))
                        .append(", (string) $observation, ")
                        .append(phpString(oneLine(step.description())))
                        .append(");\n");
                sb.append("            }\n");
                // PHPUnit counts only the assertions it saw, and reports a test that recorded none as risky;
                // a passing case evaluated the invariant, so it says so. Without this the invariant check
                // reads as a test that asserted nothing, which is exactly the shape a real run must not
                // have. The check that found nothing is still an observation, not a fake assertion.
                sb.append("            $this->addToAssertionCount(1);\n");
            }
        }
        // The generated failure is re-thrown rather than reported as "the steps threw", so that a case the
        // invariant rejected keeps its own counterexample line instead of being restated as a crash.
        sb.append("        } catch (").append(ASSERTION_FAILED).append(" $cwAssertion) {\n");
        sb.append("            throw $cwAssertion;\n");
        sb.append("        } catch (\\Throwable $cwError) {\n");
        sb.append("            self::cw_fail($cwCase, $cwSeed, $cwInputs, 'the declared steps threw ' . ")
                .append("get_class($cwError), $cwError->getMessage(), ")
                .append(phpString(oneLine(substitute(
                        scenario.observation() == null ? "step execution" : scenario.observation(), inputs))))
                .append(");\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    /** The helper methods every generated class carries, written once so no case method owns the protocol. */
    private static String helpers() {
        return """
                private static function cw_seed(int $cwFallback): int
                {
                    $cwValue = getenv('CODEWITNESS_SEED');
                    if ($cwValue === false || !ctype_digit(ltrim(trim($cwValue), '-+'))) {
                        return $cwFallback;
                    }
                    return (int) trim($cwValue);
                }

                private static function cw_cases(int $cwFallback): int
                {
                    $cwValue = getenv('CODEWITNESS_CASES');
                    if ($cwValue === false || !ctype_digit(trim($cwValue))) {
                        return $cwFallback;
                    }
                    return (int) trim($cwValue);
                }

                private static function cw_pinned_case(): int
                {
                    $cwValue = getenv('CODEWITNESS_CASE');
                    if ($cwValue === false || !ctype_digit(ltrim(trim($cwValue), '-+'))) {
                        return -1;
                    }
                    return (int) trim($cwValue);
                }

                private static function cw_int(int $cwMin, int $cwMax): int
                {
                    return $cwMax <= $cwMin ? $cwMin : mt_rand($cwMin, $cwMax);
                }

                private static function cw_double(float $cwMin, float $cwMax): float
                {
                    return $cwMax <= $cwMin ? $cwMin : $cwMin + ($cwMax - $cwMin) * (mt_rand() / mt_getrandmax());
                }

                private static function cw_bool(): bool
                {
                    return mt_rand(0, 1) === 1;
                }

                private static function cw_string(string $cwPrefix): string
                {
                    return $cwPrefix . mt_rand(0, 9999);
                }

                private static function cw_render_inputs(array $cwInputs): string
                {
                    $cwParts = [];
                    foreach ($cwInputs as $cwKey => $cwValue) {
                        $cwParts[] = $cwKey . '=' . $cwValue;
                    }
                    return '{' . implode(', ', $cwParts) . '}';
                }

                private static function cw_protocol(int $cwCase, ?int $cwSeed, array $cwInputs, string $cwExpected, string $cwActual, string $cwStep): string
                {
                    $cwFields = [
                        'case' => (string) $cwCase,
                        'seed' => $cwSeed === null ? '' : (string) $cwSeed,
                        'inputs' => self::cw_render_inputs($cwInputs),
                        'expected' => $cwExpected,
                        'actual' => $cwActual,
                        'invariant' => self::CW_INVARIANT,
                        'step' => $cwStep,
                        'requirement' => self::CW_REQUIREMENT,
                        'obligation' => self::CW_OBLIGATIONS,
                    ];
                    $cwText = 'CODEWITNESS_COUNTEREXAMPLE';
                    foreach ($cwFields as $cwKey => $cwValue) {
                        $cwText .= '|' . $cwKey . '=' . str_replace([chr(13), chr(10), '|'], [' ', ' ', '/'], (string) $cwValue);
                    }
                    return $cwText;
                }

                private static function cw_fail(int $cwCase, ?int $cwSeed, array $cwInputs, string $cwExpected, string $cwActual, string $cwStep): never
                {
                    self::fail(self::cw_protocol($cwCase, $cwSeed, $cwInputs, $cwExpected, $cwActual, $cwStep));
                }
            """;
    }

    /** The note a concurrent scenario's generated file carries, because PHP's standard build has no threads. */
    private static final String CONCURRENT_NOTE = """
            // LIMIT: the scenario declares mode "concurrent" and this test executes its steps once, in order.
            // PHP's standard build has no threads and no shared-memory objects, so a step list cannot be
            // interleaved here the way the Python adapter's barrier and the Go adapter's goroutines
            // interleave it. The case still executes the declared steps and checks the declared invariant;
            // it demonstrates nothing about concurrency.
            """;

    // ------------------------------------------------------------------ values

    /** How a generated file treats one declared input, once the domain and the PHP type name agree. */
    private enum Kind { INTEGER, DOUBLE, BOOLEAN, STRING, OTHER }

    /**
     * @return the value kind of an input: the declared domain when the scenario names one, otherwise the
     *     PHP type name.
     *
     * <p>{@code Scenario.Domain} is derived from a type name by a table of Java spellings, so a PHP
     * scenario that writes {@code type: string} or {@code type: bool} with no explicit domain would land
     * on {@code RAW} and the generated literal would be a bare word that does not parse. The PHP type
     * names section 5a of the specification fixes are therefore read here as well.
     */
    private static Kind kind(Scenario.Input input) {
        return switch (input.domain()) {
            case INTEGER, LONG, INSTANT_MILLIS -> Kind.INTEGER;
            case DOUBLE, DECIMAL -> Kind.DOUBLE;
            case BOOLEAN -> Kind.BOOLEAN;
            case STRING -> Kind.STRING;
            case NULL -> Kind.OTHER;
            case RAW -> switch (input.type().trim().toLowerCase(Locale.ROOT)) {
                case "int", "integer" -> Kind.INTEGER;
                case "float", "double" -> Kind.DOUBLE;
                case "bool", "boolean" -> Kind.BOOLEAN;
                case "string" -> Kind.STRING;
                default -> Kind.OTHER;
            };
        };
    }

    private static String literal(Scenario.Input input, String rawValue) {
        String value = rawValue;
        if (value == null) {
            value = input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst();
        }
        return switch (kind(input)) {
            case INTEGER -> integer(value, "0");
            case DOUBLE -> number(value, "0.0");
            case BOOLEAN -> booleanLiteral(value);
            case STRING -> phpString(value);
            case OTHER -> value.isBlank() ? "null" : value;
        };
    }

    /**
     * @return the value a case uses when the input declares none, as a value rather than as source text:
     *     {@link #literal} is what turns it into a PHP literal, so a string default is the empty string
     *     and not two quote characters that would then be quoted again.
     */
    private static String defaultLiteral(Scenario.Input input) {
        return switch (kind(input)) {
            case INTEGER -> "0";
            case DOUBLE -> "0.0";
            case BOOLEAN -> "false";
            case STRING -> "";
            case OTHER -> "null";
        };
    }

    private static String drawExpression(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (input.domain()) {
            case DECIMAL, DOUBLE -> "self::cw_double(" + number(min, "0") + ", " + number(max, "1000") + ")";
            case INTEGER, LONG -> "self::cw_int(" + integer(min, "0") + ", " + integer(max, "10000") + ")";
            case INSTANT_MILLIS -> "self::cw_int(" + integer(min, "0") + ", " + integer(max, "86400000") + ")";
            case BOOLEAN -> "self::cw_bool()";
            case STRING -> "self::cw_string('cw-')";
            case NULL -> "null";
            case RAW -> rawDraw(input);
        };
    }

    /** @return the draw for an input whose domain is unknown, using the PHP type name the scenario gave. */
    private static String rawDraw(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (kind(input)) {
            case INTEGER -> "self::cw_int(" + (min == null ? "0" : min) + ", " + (max == null ? "10000" : max) + ")";
            case DOUBLE -> "self::cw_double(" + (min == null ? "0" : min) + ", " + (max == null ? "1000" : max) + ")";
            case BOOLEAN -> "self::cw_bool()";
            case STRING -> "self::cw_string('cw-')";
            case OTHER -> input.values().isEmpty() ? "null" : literal(input, input.values().getFirst());
        };
    }

    /** @return the text as a PHP integer literal, or {@code fallback} when it is not one. */
    private static String integer(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.matches("[+-]?\\d+") ? trimmed : fallback;
    }

    /** @return the text as a PHP numeric literal, or {@code fallback} when it is not one. */
    private static String number(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?") ? trimmed : fallback;
    }

    private static String booleanLiteral(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.equalsIgnoreCase("true") || trimmed.equals("1") ? "true" : "false";
    }

    /** @return a PHP single-quoted string literal; a single-quoted string does not interpolate {@code $}. */
    private static String phpString(String value) {
        if (value == null) {
            return "''";
        }
        StringBuilder sb = new StringBuilder("'");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '\'') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('\'').toString();
    }

    // ------------------------------------------------------------------ collection and text

    private static List<Scenario.Input> collectInputs(Scenario scenario) {
        Map<String, Scenario.Input> byName = new LinkedHashMap<>();
        for (Scenario.Step step : scenario.steps()) {
            for (Scenario.Input input : step.inputs()) {
                byName.putIfAbsent(input.name(), input);
            }
        }
        return List.copyOf(byName.values());
    }

    /** @return one value per input, from the declared values when there are any and the type default otherwise. */
    private static Map<String, String> defaultValues(List<Scenario.Input> inputs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Scenario.Input input : inputs) {
            values.put(input.name(), input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst());
        }
        return values;
    }

    private static List<Map<String, String>> boundaryCases(List<Scenario.Input> inputs) {
        List<Map<String, String>> cases = new ArrayList<>();
        if (inputs.isEmpty()) {
            return cases;
        }
        for (Scenario.Input input : inputs) {
            if (input.boundaryValues().isEmpty()) {
                return List.of();
            }
        }
        List<Map<String, String>> accumulation = new ArrayList<>();
        accumulation.add(new LinkedHashMap<>());
        for (Scenario.Input input : inputs) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : accumulation) {
                for (String value : input.boundaryValues()) {
                    Map<String, String> copy = new LinkedHashMap<>(partial);
                    copy.put(input.name(), value);
                    next.add(copy);
                    if (next.size() >= MAX_BOUNDARY_CASES * 4) {
                        break;
                    }
                }
            }
            accumulation = next;
        }
        for (Map<String, String> values : accumulation) {
            cases.add(values);
            if (cases.size() >= MAX_BOUNDARY_CASES) {
                break;
            }
        }
        return cases;
    }

    private static String variable(Scenario.Input input) {
        return "$" + input.name();
    }

    private static String inputsLiteral(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(phpString(inputs.get(i).name())).append(" => ")
                    .append(variable(inputs.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String inputsSuffix(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder();
        for (Scenario.Input input : inputs) {
            sb.append(", ").append(variable(input));
        }
        return sb.toString();
    }

    /** Replaces every {@code ${name}} with the PHP variable {@code $name}, which is the whole DSL. */
    private static String substitute(String fragment, List<Scenario.Input> inputs) {
        String result = fragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("${" + input.name() + "}", variable(input));
        }
        return result;
    }

    /** Indents a block of user-authored statements so it fits the generated method body. */
    private static String indent(String block, int spaces) {
        String prefix = " ".repeat(spaces);
        String normalized = block.replace("\r\n", "\n").replace('\r', '\n');
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == '\n') {
            end--;
        }
        String[] lines = normalized.substring(0, end).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].isBlank() ? lines[i] : prefix + lines[i]);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }
}

package dev.codewitness.pascal.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.pascal.PascalModule;
import dev.codewitness.pascal.PascalProjectDetector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a declared scenario into a Pascal test program that exercises a project's own unit.
 *
 * <p>A scenario says how to execute a requirement in the language it was written for: the setup, the
 * calls, the inputs to search over and the invariant to check. This generator supplies the mechanical
 * part - enumerating boundaries, searching randomly under a recorded seed, and reporting a violation in
 * the counterexample protocol - and emits it as a compilable program the adapter then builds and runs.
 *
 * <p>The scenario fragments are Pascal. {@code ${name}} is replaced by the declared input {@code name},
 * a step's {@code call} is a statement, {@code observation} is assigned an expression, and
 * {@code invariant} is the boolean expression that must hold. The generated program is deliberately
 * self-contained: it is a program, not a unit, so the project's build is untouched by a generation run.
 */
public final class PascalScenarioTestGenerator implements VerificationTools.TestGenerator {

    private static final int MAX_BOUNDARY_CASES = 12;
    private static final int MAX_RANDOM_CASES = 24;

    @Override
    public String id() {
        return "pascal-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return LanguageId.PASCAL.equals(project.language());
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context,
            Requirement requirement,
            List<VerificationObligation> obligations) {
        List<PascalModule> units = PascalProjectDetector.units(context.projectRoot());
        if (units.isEmpty()) {
            return List.of();
        }
        List<GeneratedTest> tests = new ArrayList<>();
        for (VerificationObligation obligation : obligations) {
            String scenarioId = obligation.parameter("scenario");
            if (scenarioId == null || scenarioId.isBlank()) {
                continue;
            }
            Scenario scenario = scenario(context, scenarioId);
            if (scenario == null || scenario.invariant() == null || scenario.invariant().isBlank()) {
                continue;
            }
            String className = classNameFor(scenario.id(), obligation.id());
            String source = render(context, className, scenario, requirement, obligation, units);
            if (source == null) {
                continue;
            }
            tests.add(new GeneratedTest(
                    className,
                    source,
                    className,
                    List.of(requirement.id()),
                    List.of(obligation.id()),
                    scenario.description(),
                    scenario.seed(),
                    null,
                    true));
        }
        return List.copyOf(tests);
    }

    private static Scenario scenario(VerificationContext context, String id) {
        for (Scenario scenario : context.config().scenarios()) {
            if (scenario.id().equals(id)) {
                return scenario;
            }
        }
        return null;
    }

    /** @return the program name for a scenario and obligation, for example {@code cw_sc_sc_debit_ob_001}. */
    static String classNameFor(String scenarioId, String obligationId) {
        return "cw_sc_" + sanitize(scenarioId) + "_" + sanitize(obligationId);
    }

    /** @return an identifier-safe, lower-case form of {@code value}. */
    static String sanitize(String value) {
        StringBuilder safe = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                safe.append(Character.toLowerCase(c));
            } else if (safe.length() > 0 && safe.charAt(safe.length() - 1) != '_') {
                safe.append('_');
            }
        }
        String result = safe.toString();
        while (result.startsWith("_")) {
            result = result.substring(1);
        }
        while (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "case" : result;
    }

    private static String render(
            VerificationContext context,
            String className,
            Scenario scenario,
            Requirement requirement,
            VerificationObligation obligation,
            List<PascalModule> units) {
        List<Scenario.Input> inputs = collectInputs(scenario);
        String observationType = observationType(inputs);
        List<Scenario.Input> searchable = searchableInputs(inputs);

        StringBuilder out = new StringBuilder();
        out.append("{ CodeWitness generated test - do not edit. }\n");
        out.append("{ generator: pascal-scenario-generator }\n");
        out.append("{ requirement: ").append(requirement.id()).append(" }\n");
        out.append("{ obligation: ").append(obligation.id()).append(" }\n");
        out.append("{ scenario: ").append(scenario.id()).append(" mode: ").append(scenario.mode())
                .append(" seed: ").append(scenario.seed()).append(" }\n");
        out.append("program ").append(className).append(";\n");
        out.append("{$mode objfpc}{$H+}\n");
        out.append("uses\n  ").append(String.join(", ", usesFor(scenario, units))).append(";\n");
        out.append("var\n");
        out.append("  cw_case: Integer;\n");
        out.append("  cw_seed: Integer;\n");
        out.append("  cw_fail: Integer;\n");
        out.append("  observation: ").append(observationType).append(";\n");
        for (Scenario.Input input : inputs) {
            out.append("  ").append(input.name()).append(": ").append(pascalType(input.type())).append(";\n");
        }
        out.append("begin\n");
        out.append("  cw_fail := 0;\n");
        out.append("  cw_seed := ").append(scenario.seed()).append(";\n");

        int index = 0;
        List<Map<String, String>> boundary = boundaryCases(inputs);
        for (Map<String, String> values : boundary) {
            if (index >= MAX_BOUNDARY_CASES) {
                break;
            }
            out.append("  cw_case := ").append(index).append(";\n");
            for (Map.Entry<String, String> value : values.entrySet()) {
                out.append("  ").append(value.getKey()).append(" := ").append(value.getValue()).append(";\n");
            }
            emitBody(out, scenario, observationType, index, false);
            index++;
        }

        if (scenario.mode() == Scenario.Mode.SEQUENCE && !searchable.isEmpty() && searchable.size() == inputs.size()) {
            int cases = Math.min(MAX_RANDOM_CASES, Math.max(1, scenario.cases()));
            out.append("  RandSeed := cw_seed;\n");
            out.append("  for cw_case := ").append(index).append(" to ").append(index + cases - 1).append(" do\n");
            out.append("  begin\n");
            for (Scenario.Input input : searchable) {
                out.append("    ").append(input.name()).append(" := ").append(input.min())
                        .append(" + Random(").append(range(input)).append(");\n");
            }
            emitBody(out, scenario, observationType, -1, true);
            out.append("  end;\n");
        }

        out.append("  if cw_fail <> 0 then\n");
        out.append("    Halt(1);\n");
        out.append("end.\n");
        return out.toString();
    }

    private static void emitBody(
            StringBuilder out, Scenario scenario, String observationType, int caseIndex, boolean indented) {
        String indent = indented ? "    " : "  ";
        for (String statement : statements(substitute(scenario.setup(), scenario))) {
            out.append(indent).append(statement).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            for (String statement : statements(substitute(step.call(), scenario))) {
                out.append(indent).append(statement).append('\n');
            }
        }
        out.append(indent).append("observation := ").append(substitute(scenario.observation(), scenario))
                .append(";\n");
        out.append(indent).append("if not (").append(substitute(scenario.invariant(), scenario)).append(") then\n");
        out.append(indent).append("begin\n");
        out.append(indent).append("  WriteLn(").append(counterexampleArguments(scenario, observationType)).append(");\n");
        out.append(indent).append("  cw_fail := 1;\n");
        out.append(indent).append("end;\n");
    }

    /**
     * @return the arguments to a {@code WriteLn} that prints a protocol line, with the input names and
     *     values written by the program rather than interpolated by this generator
     */
    private static String counterexampleArguments(Scenario scenario, String observationType) {
        StringBuilder arguments = new StringBuilder();
        arguments.append("'").append(CounterexampleProtocol.MARKER).append("|case=', cw_case, '|seed=', cw_seed");
        arguments.append(", '|inputs={");
        boolean first = true;
        for (Scenario.Input input : collectInputs(scenario)) {
            if (!first) {
                arguments.append(", ',");
            }
            first = false;
            arguments.append(" ").append(input.name()).append("=', ").append(input.name());
        }
        arguments.append(", '}'");
        arguments.append(", '|expected=").append(escape(substitute(scenario.invariant(), scenario))).append("'");
        arguments.append(", '|actual=");
        if ("Double".equals(observationType) || "Currency".equals(observationType)) {
            arguments.append("', FloatToStr(observation), '");
        } else if ("Boolean".equals(observationType)) {
            arguments.append("', BoolToStr(observation, True), '");
        } else if ("String".equals(observationType)) {
            arguments.append("', observation, '");
        } else {
            arguments.append("', IntToStr(observation), '");
        }
        arguments.append("'");
        arguments.append(", '|invariant=").append(escape(substitute(scenario.invariant(), scenario))).append("'");
        return arguments.toString();
    }

    private static List<Scenario.Input> collectInputs(Scenario scenario) {
        Map<String, Scenario.Input> inputs = new LinkedHashMap<>();
        for (Scenario.Step step : scenario.steps()) {
            for (Scenario.Input input : step.inputs()) {
                inputs.putIfAbsent(input.name(), input);
            }
        }
        return List.copyOf(inputs.values());
    }

    private static List<Scenario.Input> searchableInputs(List<Scenario.Input> inputs) {
        List<Scenario.Input> searchable = new ArrayList<>();
        for (Scenario.Input input : inputs) {
            if (isIntegerType(input.type()) && input.min() != null && input.max() != null && isInteger(input.min())
                    && isInteger(input.max())) {
                searchable.add(input);
            }
        }
        return searchable;
    }

    private static String range(Scenario.Input input) {
        long min = Long.parseLong(input.min().strip());
        long max = Long.parseLong(input.max().strip());
        return String.valueOf(Math.max(1, max - min + 1));
    }

    static List<Map<String, String>> boundaryCases(List<Scenario.Input> inputs) {
        List<Map<String, String>> cases = new ArrayList<>();
        cases.add(new LinkedHashMap<>());
        for (Scenario.Input input : inputs) {
            List<String> candidates = candidates(input);
            List<Map<String, String>> expanded = new ArrayList<>();
            for (Map<String, String> existing : cases) {
                for (String candidate : candidates) {
                    Map<String, String> next = new LinkedHashMap<>(existing);
                    next.put(input.name(), candidate);
                    expanded.add(next);
                    if (expanded.size() >= MAX_BOUNDARY_CASES) {
                        break;
                    }
                }
                if (expanded.size() >= MAX_BOUNDARY_CASES) {
                    break;
                }
            }
            cases = expanded;
        }
        return cases;
    }

    private static List<String> candidates(Scenario.Input input) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : input.values()) {
            if (value != null && !value.isBlank()) {
                values.add(value.strip());
            }
        }
        if (input.min() != null && !input.min().isBlank()) {
            values.add(input.min().strip());
        }
        if (input.max() != null && !input.max().isBlank()) {
            values.add(input.max().strip());
        }
        if (values.isEmpty()) {
            values.add("0");
        }
        return List.copyOf(values);
    }

    /** @return the unit names the generated program must link, derived from the scenario's fragments. */
    static List<String> usesFor(Scenario scenario, List<PascalModule> units) {
        String fragments = String.join(" ",
                scenario.setup() == null ? "" : scenario.setup(),
                scenario.observation() == null ? "" : scenario.observation(),
                scenario.invariant() == null ? "" : scenario.invariant(),
                scenario.steps().stream().map(Scenario.Step::call).reduce("", (a, b) -> a + " " + b));
        Set<String> uses = new LinkedHashSet<>();
        uses.add("SysUtils");
        for (PascalModule unit : units) {
            if (fragments.contains(unit.name())) {
                uses.add(unit.name());
            }
        }
        if (uses.size() == 1) {
            for (PascalModule unit : units) {
                uses.add(unit.name());
            }
        }
        return List.copyOf(uses);
    }

    static String pascalType(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (normalized.contains("string") || normalized.contains("char")) {
            return "String";
        }
        if (normalized.contains("bool")) {
            return "Boolean";
        }
        if (normalized.contains("currency") || normalized.contains("decimal")) {
            return "Currency";
        }
        if (normalized.contains("double") || normalized.contains("real") || normalized.contains("single")
                || normalized.contains("float") || normalized.contains("extended")) {
            return "Double";
        }
        if (normalized.contains("int64") || normalized.contains("longint") || normalized.contains("longword")
                || normalized.equals("long") || normalized.equals("int64")) {
            return "Int64";
        }
        return "Integer";
    }

    private static String observationType(List<Scenario.Input> inputs) {
        for (Scenario.Input input : inputs) {
            String type = pascalType(input.type());
            if ("Double".equals(type) || "Currency".equals(type)) {
                return type;
            }
        }
        for (Scenario.Input input : inputs) {
            String type = pascalType(input.type());
            if ("Boolean".equals(type) || "String".equals(type)) {
                return type;
            }
        }
        for (Scenario.Input input : inputs) {
            if ("Int64".equals(pascalType(input.type()))) {
                return "Int64";
            }
        }
        return "Integer";
    }

    private static boolean isIntegerType(String type) {
        String pascal = pascalType(type);
        return "Integer".equals(pascal) || "Int64".equals(pascal);
    }

    private static boolean isInteger(String value) {
        try {
            Long.parseLong(value.strip());
            return true;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /** @return {@code text} with each {@code ${name}} replaced by {@code name}. */
    static String substitute(String text, Scenario scenario) {
        if (text == null) {
            return "";
        }
        String result = text;
        for (Scenario.Input input : collectInputs(scenario)) {
            result = result.replace("${" + input.name() + "}", input.name());
        }
        return result;
    }

    /** @return the statement list of a fragment, one statement per line, each terminated with {@code ;}. */
    static List<String> statements(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        for (String line : fragment.split("\\R")) {
            String statement = line.strip();
            if (statement.isEmpty()) {
                continue;
            }
            statements.add(statement.endsWith(";") ? statement : statement + ";");
        }
        return statements;
    }

    /** @return {@code text} flattened to one line with the characters that would break a protocol line replaced. */
    static String escape(String text) {
        return text == null ? "" : text.strip().replace("\\", "/").replace("|", "/").replace("\"", "'")
                .replace("'", "''").replace("\n", " ").replace("\r", " ");
    }

    /** @return the path a generated program is written to during execution. */
    static Path generatedFile(Path projectRoot, String className) {
        return projectRoot.resolve("generated-tests").resolve(className + ".pas");
    }
}

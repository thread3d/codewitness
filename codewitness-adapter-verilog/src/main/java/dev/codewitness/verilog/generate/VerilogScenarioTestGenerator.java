package dev.codewitness.verilog.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.engine.VerificationEngine;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.verilog.VerilogModule;
import dev.codewitness.verilog.VerilogProjectDetector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns declared scenarios into executable Verilog testbenches.
 *
 * <p>A scenario supplies the oracle - the setup, the statements that drive the design, the observation
 * and the invariant, written in Verilog in {@code codewitness.yaml}; this generator supplies the
 * mechanical part: instantiating the design under test, declaring the scenario's inputs at the right
 * widths, enumerating the boundary values, optionally searching randomly over a declared range, and
 * printing a reproducible counterexample when the invariant is violated. CodeWitness never invents an
 * oracle, because an invented oracle is exactly the kind of assertion this tool exists to avoid.
 *
 * <p>The generated file is an ordinary testbench module compiled together with the project's own
 * sources and selected as the simulation root, so it needs no tool beyond the pair the adapter already
 * drives. Its fragments reference the design through the instance name {@code dut}: a scenario writes
 * {@code dut.balance_out} or {@code amount}, and the generator has already declared and connected
 * those signals.
 *
 * <p>One Verilog-specific fact shapes the file. A failing case cannot fail the whole simulation
 * without stopping the remaining cases, so each case prints its counterexample line and sets a flag,
 * and the simulation calls {@code $fatal} once at the end if the flag is set. The line is the same
 * {@code CODEWITNESS_COUNTEREXAMPLE|...} protocol every adapter uses, so the engine reconstructs the
 * same counterexample it would from any other language, and the testbench's non-zero exit is what a
 * mutation run reads as detection.
 */
public final class VerilogScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Upper bound on generated boundary cases per scenario, matching the other adapters' cap. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** Default number of randomised cases when a scenario declares more than one and a range to search. */
    private static final int MAX_RANDOM_CASES = 24;

    @Override
    public String id() {
        return "verilog-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VERILOG);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        VerilogModule top = VerilogProjectDetector.topModule(context.projectRoot());
        if (top == null) {
            return List.of();
        }
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
            if (scenario == null || scenario.invariant() == null || scenario.invariant().isBlank()) {
                continue;
            }
            String className = classNameFor(scenario.id(), obligation.id());
            String source = render(context, requirement, obligation, scenario, className, top);
            generated.add(new GeneratedTest(className, source, className,
                    List.of(requirement.id()), List.of(obligation.id()),
                    scenario.description(), scenario.seed(), null, true));
        }
        return generated;
    }

    /**
     * @return a valid Verilog module identifier for a scenario and obligation, for example
     *     {@code cw_sc_rolling_cw_rolling_boundary}. The testbench's module name is also the
     *     identifier the runner reports the case under, so the two never disagree.
     */
    static String classNameFor(String scenarioId, String obligationId) {
        return "cw_sc_" + sanitize(scenarioId) + "_" + sanitize(obligationId);
    }

    static String sanitize(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : (value == null ? "" : value).toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
        }
        while (sb.length() > 0 && sb.charAt(0) == '_') {
            sb.deleteCharAt(0);
        }
        return sb.isEmpty() ? "case" : sb.toString();
    }

    // ------------------------------------------------------------------ rendering

    private String render(
            VerificationContext context,
            Requirement requirement,
            VerificationObligation obligation,
            Scenario scenario,
            String className,
            VerilogModule top) {

        List<Scenario.Input> inputs = collectInputs(scenario);
        Map<String, String> scenarioSignals = new LinkedHashMap<>();
        for (Scenario.Input input : inputs) {
            scenarioSignals.put(input.name(), declarationFor(input, top));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by CodeWitness ").append(VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("// Requirement: ").append(requirement.id()).append(" - ")
                .append(oneLine(requirement.title())).append('\n');
        sb.append("// Obligation: ").append(obligation.id()).append(" - ")
                .append(oneLine(obligation.description())).append('\n');
        sb.append("// Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases()).append(")\n");
        sb.append("// This file is temporary: CodeWitness keeps it with the run so a counterexample can be replayed.\n");
        sb.append("// It instantiates the design under test as `dut`; scenario fragments reference that instance.\n\n");
        sb.append("`timescale 1ns/1ps\n\n");
        sb.append("module ").append(className).append(";\n");

        for (VerilogModule.Port port : top.ports()) {
            sb.append("    ").append(port.testbenchDeclaration()).append(' ').append(port.name()).append(";\n");
        }
        for (Map.Entry<String, String> signal : scenarioSignals.entrySet()) {
            boolean alreadyDeclared = top.ports().stream().anyMatch(p -> p.name().equals(signal.getKey()));
            if (!alreadyDeclared) {
                sb.append("    ").append(signal.getValue()).append(' ').append(signal.getKey()).append(";\n");
            }
        }
        sb.append("    reg [63:0] observation;\n");
        sb.append("    integer cw_case;\n");
        sb.append("    integer cw_fail;\n");
        sb.append("    integer cw_seed;\n\n");

        String connection = top.ports().stream()
                .map(port -> "." + port.name() + "(" + port.name() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
        sb.append("    ").append(top.name()).append(" dut (").append(connection).append(");\n\n");

        String clock = clockName(top, scenario);
        if (clock != null) {
            sb.append("    initial ").append(clock).append(" = 0;\n");
            sb.append("    always #5 ").append(clock).append(" = ~").append(clock).append(";\n\n");
        }

        sb.append("    initial begin\n");
        sb.append("        cw_fail = 0;\n");
        sb.append("        cw_seed = ").append(scenario.seed()).append(";\n");
        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            emitCase(sb, scenario, className, inputs, values, index, "        ");
            index++;
        }
        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            emitCase(sb, scenario, className, List.of(), Map.of(), 0, "        ");
        }
        boolean random = (scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1)
                && !inputs.isEmpty() && canSearch(inputs);
        if (random) {
            emitRandomSearch(sb, scenario, className, inputs, 0);
        }
        sb.append("        if (cw_fail) begin\n");
        sb.append("            $display(\"CODEWITNESS_GENERATED_TEST_FAILED|requirement=")
                .append(escape(requirement.id())).append("|obligation=").append(escape(obligation.id()))
                .append("\");\n");
        sb.append("            $fatal;\n");
        sb.append("        end else begin\n");
        sb.append("            $display(\"CODEWITNESS_GENERATED_TEST_PASSED|requirement=")
                .append(escape(requirement.id())).append("|obligation=").append(escape(obligation.id()))
                .append("\");\n");
        sb.append("            $finish;\n");
        sb.append("        end\n");
        sb.append("    end\n");
        sb.append("endmodule\n");
        return sb.toString();
    }

    private void emitCase(
            StringBuilder sb,
            Scenario scenario,
            String className,
            List<Scenario.Input> inputs,
            Map<String, String> values,
            int index,
            String indent) {
        sb.append(indent).append("cw_case = ").append(index).append(";\n");
        for (Scenario.Input input : inputs) {
            String value = values.getOrDefault(input.name(), "0");
            sb.append(indent).append(input.name()).append(" = ").append(value).append(";\n");
        }
        emitBody(sb, scenario, className, indent, index, false);
    }

    private void emitRandomSearch(
            StringBuilder sb, Scenario scenario, String className, List<Scenario.Input> inputs, int start) {
        int cases = Math.max(1, Math.min(scenario.cases(), MAX_RANDOM_CASES));
        String indent = "        ";
        sb.append(indent).append("$urandom(cw_seed);\n");
        sb.append(indent).append("for (cw_case = ").append(start).append("; cw_case < ")
                .append(start + cases).append("; cw_case = cw_case + 1) begin\n");
        String inner = indent + "    ";
        for (Scenario.Input input : inputs) {
            sb.append(inner).append(input.name()).append(" = ")
                    .append(input.min()).append(" + $urandom_range(")
                    .append(input.max()).append(" - ").append(input.min()).append(");\n");
        }
        emitBody(sb, scenario, className, inner, -1, true);
        sb.append(indent).append("end\n");
    }

    private void emitBody(
            StringBuilder sb, Scenario scenario, String className, String indent, int caseIndex, boolean random) {
        String indexExpression = random ? "cw_case" : String.valueOf(caseIndex);
        for (String line : statements(scenario.setup())) {
            sb.append(indent).append(line).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            for (String line : statements(step.call())) {
                sb.append(indent).append(line).append('\n');
            }
        }
        String observation = substitute(scenario.observation(), scenario);
        if (observation != null && !observation.isBlank()) {
            // A combinational design settles in a delta cycle after its inputs change, and the initial
            // block does not yield between the assignments above and this statement. Advancing time is
            // what guarantees the observation reads the settled output rather than the previous one.
            sb.append(indent).append("#1;\n");
            sb.append(indent).append("observation = ").append(ensureTerminated(observation)).append('\n');
        }
        String invariant = substitute(scenario.invariant(), scenario);
        sb.append(indent).append("if (!(").append(oneLine(invariant)).append(")) begin\n");
        sb.append(indent).append("    $display(\"").append(counterexampleFormat(scenario, caseIndex, random))
                .append("\", ").append(counterexampleArguments(scenario)).append(");\n");
        sb.append(indent).append("    cw_fail = 1;\n");
        sb.append(indent).append("end\n");
        sb.append(indent).append("// case ").append(indexExpression).append(" end\n");
    }

    /**
     * @return the {@code $display} format string for a violated invariant.
     *
     * <p>The protocol separates fields with {@code |}, so the invariant text - which may itself be a
     * bitwise-or expression - and the input names are rewritten to keep the line parseable. The engine
     * reads the same line every other adapter writes.
     */
    private String counterexampleFormat(Scenario scenario, int caseIndex, boolean random) {
        StringBuilder sb = new StringBuilder();
        sb.append(CounterexampleProtocol.MARKER);
        sb.append("|case=%0d");
        sb.append("|seed=%0d");
        List<Scenario.Input> inputs = collectInputs(scenario);
        if (!inputs.isEmpty()) {
            sb.append("|inputs={");
            boolean first = true;
            for (Scenario.Input input : inputs) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(escape(input.name())).append("=%0d");
            }
            sb.append('}');
        }
        sb.append("|expected=").append(formatText(oneLine(scenario.invariant())));
        sb.append("|actual=%0d");
        sb.append("|invariant=").append(formatText(oneLine(scenario.invariant())));
        return sb.toString();
    }

    private String counterexampleArguments(Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("cw_case, cw_seed");
        for (Scenario.Input input : collectInputs(scenario)) {
            sb.append(", ").append(input.name());
        }
        sb.append(", observation");
        return sb.toString();
    }

    // ------------------------------------------------------------------ scenario helpers

    /** @return every input declared by any step of a scenario, in declaration order and de-duplicated. */
    static List<Scenario.Input> collectInputs(Scenario scenario) {
        Map<String, Scenario.Input> inputs = new LinkedHashMap<>();
        for (Scenario.Step step : scenario.steps()) {
            for (Scenario.Input input : step.inputs()) {
                inputs.putIfAbsent(input.name(), input);
            }
        }
        return List.copyOf(inputs.values());
    }

    /** @return up to {@link #MAX_BOUNDARY_CASES} value combinations, the cartesian product of the candidates. */
    static List<Map<String, String>> boundaryCases(List<Scenario.Input> inputs) {
        // One implementation, in support: fourteen copies of this existed and three of them
        // truncated differently, which changes which experiments run and therefore what the
        // same scenario verifies.
        return dev.codewitness.support.generate.ScenarioCases.boundaryCases(inputs);
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

    /** @return {@code true} when every input declares a non-negative numeric range a search can draw from. */
    static boolean canSearch(List<Scenario.Input> inputs) {
        for (Scenario.Input input : inputs) {
            if (input.min() == null || input.max() == null) {
                return false;
            }
            try {
                if (Long.parseLong(input.min().strip()) < 0 || Long.parseLong(input.max().strip()) < 0) {
                    return false;
                }
            } catch (NumberFormatException _) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the declaration a scenario input needs: the design's own port type when it names one,
     *     otherwise the type the scenario declared, or a 64-bit register when that type is not one the
     *     generator can write.
     */
    private static String declarationFor(Scenario.Input input, VerilogModule top) {
        for (VerilogModule.Port port : top.ports()) {
            if (port.name().equals(input.name())) {
                return port.testbenchDeclaration();
            }
        }
        return declarationFromType(input.type());
    }

    /**
     * @return a Verilog declaration for a scenario input that is not one of the design's ports.
     *
     * <p>Only integral types are produced, because the counterexample line formats every input with
     * {@code %0d}: a {@code real} input would need a different conversion, so it falls back to a
     * 64-bit register rather than producing a testbench whose {@code $display} would fail.
     */
    static String declarationFromType(String type) {
        if (type == null || type.isBlank()) {
            return "reg [63:0]";
        }
        String normalized = type.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("integer") || normalized.equals("time")) {
            return normalized;
        }
        if (normalized.startsWith("reg") || normalized.startsWith("logic") || normalized.startsWith("bit")) {
            return normalized;
        }
        if (normalized.startsWith("[")) {
            return "reg " + normalized;
        }
        return "reg [63:0]";
    }

    /** @return the clock port name when the design has one and the scenario never mentions it, or null. */
    private static String clockName(VerilogModule top, Scenario scenario) {
        String mentioned = String.join(" ",
                scenario.setup() == null ? "" : scenario.setup(),
                scenario.observation() == null ? "" : scenario.observation(),
                scenario.invariant() == null ? "" : scenario.invariant(),
                scenario.steps().stream().map(Scenario.Step::call).reduce("", (a, b) -> a + " " + b));
        for (VerilogModule.Port port : top.ports()) {
            String lower = port.name().toLowerCase(Locale.ROOT);
            if (port.isInput() && (lower.equals("clk") || lower.equals("clock") || lower.equals("ck"))
                    && !mentioned.contains(port.name())) {
                return port.name();
            }
        }
        return null;
    }

    /** Replaces {@code ${name}} placeholders with the bare input names. */
    static String substitute(String fragment, Scenario scenario) {
        if (fragment == null) {
            return null;
        }
        String result = fragment;
        for (Scenario.Input input : collectInputs(scenario)) {
            result = result.replace("${" + input.name() + "}", input.name());
        }
        return result;
    }

    /** @return the fragment split into non-empty statements, each terminated with a semicolon. */
    static List<String> statements(String fragment) {
        List<String> statements = new ArrayList<>();
        if (fragment == null || fragment.isBlank()) {
            return statements;
        }
        for (String raw : fragment.split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                statements.add(ensureTerminated(line));
            }
        }
        return statements;
    }

    private static String ensureTerminated(String statement) {
        String trimmed = statement.strip();
        return trimmed.endsWith(";") || trimmed.endsWith("begin") || trimmed.endsWith("end")
                || trimmed.startsWith("//") ? trimmed : trimmed + ";";
    }

    /** @return the text collapsed to one line, for a declaration or a protocol field. */
    static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    /** @return the text safe to embed in the protocol line and in a Verilog string literal. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return oneLine(value)
                .replace("\\", "/")
                .replace("|", "/")
                .replace("\"", "'");
    }

    /**
     * @return the text safe to embed in a {@code $display} <em>format string</em>.
     *
     * <p>A format string is not a plain literal: a {@code %} in the invariant text - and an invariant
     * about arithmetic usually has one - would be read as the start of a conversion specifier and
     * consume an argument that was meant for {@code actual}. Doubling it makes {@code $display} print
     * the single character the scenario wrote.
     */
    static String formatText(String value) {
        return escape(value).replace("%", "%%");
    }
}

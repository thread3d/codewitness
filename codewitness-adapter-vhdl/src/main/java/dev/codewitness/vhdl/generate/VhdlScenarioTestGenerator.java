package dev.codewitness.vhdl.generate;

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
import dev.codewitness.vhdl.VhdlDesignUnit;
import dev.codewitness.vhdl.VhdlProjectDetector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns declared scenarios into executable VHDL testbenches.
 *
 * <p>A scenario supplies the oracle - the setup, the statements that drive the design, the observation
 * and the invariant, written in VHDL in {@code codewitness.yaml}; this generator supplies the
 * mechanical part: instantiating the design under test, declaring the scenario's inputs at the design's
 * own port subtypes, enumerating the boundary values, optionally searching randomly over a declared
 * range, and printing a reproducible counterexample when the invariant is violated. CodeWitness never
 * invents an oracle, because an invented oracle is exactly the kind of assertion this tool exists to
 * avoid.
 *
 * <p>Two VHDL facts shape the file. A component instance's ports cannot be selected by name - VHDL has
 * no {@code dut.balance} - so the testbench declares a signal named after each port and connects it;
 * scenario fragments therefore reference the signal names directly, exactly as a hand-written VHDL
 * testbench does. And the language is strongly typed, so an integer boundary value is converted into
 * the port's own subtype with {@code to_unsigned}, {@code to_signed} or a std_logic conditional before
 * it is assigned; a signal that kept its integer form would not elaborate.
 *
 * <p>The generated file is an ordinary testbench entity analysed together with the project's own
 * sources and simulated on its own, so it needs no tool beyond the {@code ghdl} the adapter already
 * drives. One VHDL-specific behaviour follows from the same choice: a failing case cannot stop the
 * simulation without stopping the remaining cases, so each case prints its counterexample line and sets
 * a flag, and the process calls an assertion at failure severity once at the end if the flag is set.
 */
public final class VhdlScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Upper bound on generated boundary cases per scenario, matching the other adapters' cap. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** Default number of randomised cases when a scenario declares more than one and a range to search. */
    private static final int MAX_RANDOM_CASES = 24;

    /** The largest product of the linear congruential search that stays inside a 32-bit VHDL integer. */
    private static final long LCG_MULTIPLIER = 25173L;
    private static final long LCG_INCREMENT = 13849L;
    private static final long LCG_MODULUS = 65536L;

    @Override
    public String id() {
        return "vhdl-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VHDL);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        VhdlDesignUnit top = VhdlProjectDetector.topEntity(context.projectRoot());
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
     * @return a valid VHDL identifier for a scenario and obligation, for example
     *     {@code cw_sc_rolling_cw_rolling_boundary}. The testbench's entity name is also the identifier
     *     the runner reports the case under, so the two never disagree.
     */
    static String classNameFor(String scenarioId, String obligationId) {
        return "cw_sc_" + sanitize(scenarioId) + "_" + sanitize(obligationId);
    }

    static String sanitize(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : (value == null ? "" : value).toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
        }
        while (!sb.isEmpty() && sb.charAt(0) == '_') {
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
            VhdlDesignUnit top) {

        List<Scenario.Input> inputs = collectInputs(scenario);

        StringBuilder sb = new StringBuilder();
        sb.append("-- Generated by CodeWitness ").append(VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("-- Requirement: ").append(requirement.id()).append(" - ")
                .append(oneLine(requirement.title())).append('\n');
        sb.append("-- Obligation: ").append(obligation.id()).append(" - ")
                .append(oneLine(obligation.description())).append('\n');
        sb.append("-- Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases()).append(")\n");
        sb.append("-- This file is temporary: CodeWitness keeps it with the run so a counterexample can be replayed.\n");
        sb.append("-- Scenario fragments reference the design's ports by name, as the signals below declare them.\n\n");
        sb.append("library ieee;\n");
        sb.append("use ieee.std_logic_1164.all;\n");
        sb.append("use ieee.numeric_std.all;\n");
        sb.append("use std.env.all;\n");
        sb.append("use work.all;\n\n");
        sb.append("entity ").append(className).append(" is\n");
        sb.append("end entity ").append(className).append(";\n\n");
        sb.append("architecture sim of ").append(className).append(" is\n");

        for (VhdlDesignUnit.Port port : top.ports()) {
            sb.append("  signal ").append(port.name()).append(" : ").append(port.signalDeclaration()).append(";\n");
        }
        // The instantiation and the processes are concurrent statements, so they belong in the
        // architecture's statement part: `begin` closes the declarative part the signals sit in.
        sb.append("begin\n\n");
        String connection = top.ports().stream()
                .map(port -> port.name() + " => " + port.name())
                .reduce((a, b) -> a + ", " + b).orElse("");
        sb.append("  dut : entity work.").append(top.name()).append(" port map (").append(connection).append(");\n\n");

        String clock = clockName(top, scenario);
        if (clock != null) {
            sb.append("  ").append(clock).append(" <= '0';\n");
            sb.append("  clock : process\n");
            sb.append("  begin\n");
            sb.append("    wait for 5 ns;\n");
            sb.append("    ").append(clock).append(" <= not ").append(clock).append(";\n");
            sb.append("  end process clock;\n\n");
        }

        sb.append("  stimulus : process\n");
        sb.append("    variable cw_case : integer;\n");
        sb.append("    variable cw_seed : integer;\n");
        sb.append("    variable observation : integer := 0;\n");
        sb.append("    variable cw_fail : boolean := false;\n");
        for (Scenario.Input input : inputs) {
            if (isPort(input, top)) {
                sb.append("    variable cw_").append(input.name()).append(" : integer;\n");
            } else {
                sb.append("    variable ").append(input.name()).append(" : integer;\n");
            }
        }
        sb.append("  begin\n");
        sb.append("    cw_fail := false;\n");
        sb.append("    cw_seed := ").append(scenario.seed()).append(";\n");

        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            emitCase(sb, scenario, top, inputs, values, index);
            index++;
        }
        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            emitBody(sb, scenario, top, "    ", false);
        }
        boolean random = (scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1)
                && !inputs.isEmpty() && canSearch(inputs) && boundaryCases.size() < MAX_BOUNDARY_CASES;
        if (random) {
            emitRandomSearch(sb, scenario, top, inputs);
        }
        sb.append("    if cw_fail then\n");
        sb.append("      assert false report \"CODEWITNESS_GENERATED_TEST_FAILED|requirement=")
                .append(escape(requirement.id())).append("|obligation=").append(escape(obligation.id()))
                .append("\" severity failure;\n");
        sb.append("    else\n");
        sb.append("      report \"CODEWITNESS_GENERATED_TEST_PASSED|requirement=")
                .append(escape(requirement.id())).append("|obligation=").append(escape(obligation.id()))
                .append("\" severity note;\n");
        sb.append("    end if;\n");
        sb.append("    std.env.finish;\n");
        sb.append("  end process stimulus;\n");
        sb.append("end architecture sim;\n");
        return sb.toString();
    }

    private void emitCase(
            StringBuilder sb,
            Scenario scenario,
            VhdlDesignUnit top,
            List<Scenario.Input> inputs,
            Map<String, String> values,
            int index) {
        sb.append("    -- case ").append(index).append('\n');
        sb.append("    cw_case := ").append(index).append(";\n");
        for (Scenario.Input input : inputs) {
            assignInput(sb, top, input, values.getOrDefault(input.name(), "0"), "    ");
        }
        emitBody(sb, scenario, top, "    ", false);
    }

    private void emitRandomSearch(StringBuilder sb, Scenario scenario, VhdlDesignUnit top, List<Scenario.Input> inputs) {
        int cases = Math.max(1, Math.min(scenario.cases(), MAX_RANDOM_CASES));
        sb.append("    cw_case := 0;\n");
        sb.append("    while cw_case < ").append(cases).append(" loop\n");
        sb.append("      cw_seed := (cw_seed * ").append(LCG_MULTIPLIER).append(" + ")
                .append(LCG_INCREMENT).append(") mod ").append(LCG_MODULUS).append(";\n");
        for (Scenario.Input input : inputs) {
            long min = Long.parseLong(input.min().strip());
            long max = Long.parseLong(input.max().strip());
            long span = max - min + 1;
            String expression = min + " + cw_seed mod " + span;
            assignInput(sb, top, input, expression, "      ");
        }
        emitBody(sb, scenario, top, "      ", true);
        sb.append("      cw_case := cw_case + 1;\n");
        sb.append("    end loop;\n");
    }

    /** Writes the declaration of one scenario input and the assignment of one case's value to it. */
    private void assignInput(StringBuilder sb, VhdlDesignUnit top, Scenario.Input input, String value, String indent) {
        VhdlDesignUnit.Port port = top.port(input.name());
        if (port != null) {
            sb.append(indent).append("cw_").append(input.name()).append(" := ").append(value).append(";\n");
            sb.append(indent).append(input.name()).append(" <= ")
                    .append(port.driveExpression("cw_" + input.name())).append(";\n");
        } else {
            sb.append(indent).append(input.name()).append(" := ").append(value).append(";\n");
        }
    }

    private void emitBody(StringBuilder sb, Scenario scenario, VhdlDesignUnit top, String indent, boolean random) {
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
            // A signal assignment does not update until the process suspends, and a combinational design
            // settles in a delta cycle after its inputs change. Waiting is what guarantees the observation
            // reads the settled output rather than the previous one.
            sb.append(indent).append("wait for 1 ns;\n");
            sb.append(indent).append("observation := ").append(oneLine(observation)).append(";\n");
        }
        String invariant = substitute(scenario.invariant(), scenario);
        sb.append(indent).append("if not (").append(oneLine(invariant)).append(") then\n");
        sb.append(indent).append("  report ").append(counterexampleReport(scenario, top)).append(" severity note;\n");
        sb.append(indent).append("  cw_fail := true;\n");
        sb.append(indent).append("end if;\n");
    }

    /**
     * @return the report expression for a violated invariant, as a concatenation of literals and
     *     {@code integer'image} conversions. The protocol separates fields with {@code |}, so the
     *     invariant text and the input names are rewritten to keep the line parseable, and the engine
     *     reads the same line every other adapter writes.
     */
    private String counterexampleReport(Scenario scenario, VhdlDesignUnit top) {
        List<Scenario.Input> inputs = collectInputs(scenario);
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(CounterexampleProtocol.MARKER).append('"');
        sb.append(" & \"|case=\" & integer'image(cw_case)");
        sb.append(" & \"|seed=\" & integer'image(cw_seed)");
        if (!inputs.isEmpty()) {
            sb.append(" & \"|inputs={");
            boolean first = true;
            for (Scenario.Input input : inputs) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(escape(input.name())).append('=');
                sb.append("\" & integer'image(").append(counterexampleValue(top, input)).append(") & \"");
            }
            sb.append("}\"");
        }
        String invariant = escape(oneLine(scenario.invariant()));
        sb.append(" & \"|expected=").append(invariant).append('"');
        sb.append(" & \"|actual=\" & integer'image(observation)");
        sb.append(" & \"|invariant=").append(invariant).append('"');
        return sb.toString();
    }

    /**
     * @return the integer variable that carries the value of one scenario input: the {@code cw_}-prefixed
     *     one for a design port, because the port signal itself is typed, and the input's own name for an
     *     auxiliary input, which is already an integer variable.
     */
    private String counterexampleValue(VhdlDesignUnit top, Scenario.Input input) {
        return isPort(input, top) ? "cw_" + input.name() : input.name();
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

    /** @return {@code true} when every input declares a bounded numeric range a search can draw from. */
    static boolean canSearch(List<Scenario.Input> inputs) {
        for (Scenario.Input input : inputs) {
            if (input.min() == null || input.max() == null) {
                return false;
            }
            try {
                long min = Long.parseLong(input.min().strip());
                long max = Long.parseLong(input.max().strip());
                if (min < 0 || max < min) {
                    return false;
                }
            } catch (NumberFormatException _) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPort(Scenario.Input input, VhdlDesignUnit top) {
        return top.port(input.name()) != null;
    }

    /** @return the clock port name when the design has one and the scenario never mentions it, or null. */
    private static String clockName(VhdlDesignUnit top, Scenario scenario) {
        String mentioned = String.join(" ",
                scenario.setup() == null ? "" : scenario.setup(),
                scenario.observation() == null ? "" : scenario.observation(),
                scenario.invariant() == null ? "" : scenario.invariant(),
                scenario.steps().stream().map(Scenario.Step::call).reduce("", (a, b) -> a + " " + b));
        boolean scenarioDrivesIt = collectInputs(scenario).stream()
                .anyMatch(input -> input.name().equalsIgnoreCase("clk")
                        || input.name().equalsIgnoreCase("clock") || input.name().equalsIgnoreCase("ck"));
        for (VhdlDesignUnit.Port port : top.ports()) {
            String lower = port.name().toLowerCase(Locale.ROOT);
            if (port.isInput() && port.isStdLogic()
                    && (lower.equals("clk") || lower.equals("clock") || lower.equals("ck"))
                    && !mentioned.contains(port.name()) && !scenarioDrivesIt) {
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
        return trimmed.endsWith(";") || trimmed.startsWith("--") ? trimmed : trimmed + ";";
    }

    /** @return the text collapsed to one line, for a declaration or a protocol field. */
    static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    /** @return the text safe to embed in the protocol line and in a VHDL string literal. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return oneLine(value)
                .replace("\\", "/")
                .replace("|", "/")
                .replace("\"", "'");
    }
}

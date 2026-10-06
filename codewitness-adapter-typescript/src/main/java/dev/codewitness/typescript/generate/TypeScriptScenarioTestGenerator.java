package dev.codewitness.typescript.generate;

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
import java.util.Set;

/**
 * Turns declared scenarios into executable {@code node:test} modules.
 *
 * <p>A scenario supplies the oracle — the setup, the calls and the invariant, written in TypeScript in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the
 * seeded random search, concurrent execution and the structured failure report. CodeWitness never
 * invents an oracle, because an invented oracle is exactly the kind of assertion this tool exists to
 * avoid.
 *
 * <p>The generated file is an ordinary test beside the project's own tests, so it runs with
 * {@code node --test}, executes TypeScript directly by stripping types, and needs no dependency beyond
 * the Node standard library. It is named {@code <class>.test.ts} so the runner's JUnit report names the
 * same class the engine maps the result back to. Each failing case throws an {@code Error} carrying a
 * single {@code CODEWITNESS_COUNTEREXAMPLE|...} line with the exact inputs, seed and case index, which
 * is what makes the counterexample reproducible.
 *
 * <p>Fragment imports ({@code import ...} lines in {@code setup}) are hoisted to the top of the module,
 * because an import cannot appear inside a function; everything else in {@code setup} runs inside each
 * case. A fragment therefore imports the project exactly as a hand-written test in the same directory
 * would, which is the convention the documentation states.
 */
public final class TypeScriptScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Number of concurrent workers used by CONCURRENT scenarios. */
    private static final int CONCURRENT_WORKERS = 8;

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** TypeScript primitive types the generator can draw values for. */
    private static final Set<String> SIMPLE_TYPES = Set.of("number", "bigint", "string", "boolean");

    @Override
    public String id() {
        return "typescript-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.TYPESCRIPT);
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
            String source = render(context, requirement, obligation, scenario);
            generated.add(new GeneratedTest(className, source, testMethod(scenario),
                    List.of(requirement.id()), List.of(obligation.id()),
                    scenario.description(), scenario.seed(), null));
        }
        return generated;
    }

    private static String testMethod(Scenario scenario) {
        return scenario.mode() == Scenario.Mode.CONCURRENT ? "cw_concurrent_search" : "cw_random_search";
    }

    private static String classNameFor(Scenario scenario, VerificationObligation obligation) {
        String suffix = scenario.mode() == Scenario.Mode.CONCURRENT ? "_concurrency" : "";
        return "cw_" + sanitize(scenario.id()) + "_" + sanitize(obligation.id()) + suffix;
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
            Scenario scenario) {

        List<Scenario.Input> inputs = collectInputs(scenario);
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
        sb.append("// It uses only Node's standard library, so it runs on any Node 22.6+ without a build step.\n\n");
        sb.append("import test from \"node:test\";\n");
        appendHoistedImports(sb, scenario.setup());
        sb.append('\n');
        sb.append("const CW_REQUIREMENT = ").append(tsString(requirement.id())).append(";\n");
        sb.append("const CW_OBLIGATION = ").append(tsString(obligation.id())).append(";\n");
        sb.append("const CW_INVARIANT = ")
                .append(tsString(oneLine(scenario.invariant() == null ? "(no invariant declared)" : scenario.invariant())))
                .append(";\n\n");

        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        int index = 0;
        if (!inputs.isEmpty() && boundaryCases.isEmpty()) {
            // Every input has a declared type but no boundary value: run the explicit/default values once.
            sb.append(caseFunction(scenario, index, inputs, defaultValues(inputs)));
            index++;
        } else {
            for (Map<String, String> values : boundaryCases) {
                sb.append(caseFunction(scenario, index, inputs, values));
                index++;
            }
        }
        if (inputs.isEmpty()) {
            sb.append(singleCase(scenario));
        }
        boolean random = scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1;
        if (random && !inputs.isEmpty()) {
            if (scenario.mode() == Scenario.Mode.CONCURRENT) {
                sb.append(concurrentSearch(inputs, scenario));
            } else {
                sb.append(randomSearch(inputs, scenario));
            }
        }
        sb.append(HELPERS);
        return sb.toString();
    }

    private static void appendHoistedImports(StringBuilder sb, String setup) {
        if (setup == null) {
            return;
        }
        for (String line : setup.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("import ") || stripped.startsWith("import{")) {
                sb.append(stripped).append('\n');
            }
        }
    }

    private static Map<String, String> defaultValues(List<Scenario.Input> inputs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Scenario.Input input : inputs) {
            values.put(input.name(), input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst());
        }
        return values;
    }

    // ------------------------------------------------------------------ test functions

    private String caseFunction(
            Scenario scenario, int index, List<Scenario.Input> inputs, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("test(\"cw_case").append(String.format(Locale.ROOT, "%04d", index)).append("\", () => {\n");
        sb.append("  const cwCase = ").append(index).append(";\n");
        sb.append("  const cwSeedValue: number | null = null;\n");
        for (Scenario.Input input : inputs) {
            String value = values.get(input.name());
            sb.append("  ").append(declaration(input, literal(input, value))).append('\n');
        }
        sb.append("  ").append(inputsVector(inputs)).append('\n');
        sb.append("  cwExecute(cwCase, cwSeedValue, cwInputs, () => {\n");
        sb.append(sequenceBody(scenario, inputs, 4));
        sb.append("  });\n");
        sb.append("});\n\n");
        return sb.toString();
    }

    private String singleCase(Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("test(\"cw_single_case\", () => {\n");
        sb.append("  const cwCase = 0;\n");
        sb.append("  const cwSeedValue: number | null = null;\n");
        sb.append("  const cwInputs: Array<[string, string]> = [];\n");
        sb.append("  cwExecute(cwCase, cwSeedValue, cwInputs, () => {\n");
        sb.append(sequenceBody(scenario, List.of(), 4));
        sb.append("  });\n");
        sb.append("});\n\n");
        return sb.toString();
    }

    private String randomSearch(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("test(\"cw_random_search\", () => {\n");
        sb.append("  const cwSeedValue: number | null = cwSeed(").append(scenario.seed()).append(");\n");
        sb.append("  const cwRngSeed = cwSeedValue ?? ").append(scenario.seed()).append(";\n");
        sb.append("  const cwCaseCount = cwCases(").append(scenario.cases()).append(");\n");
        sb.append("  const cwPinned = cwPinnedCase();\n");
        sb.append("  for (let cwCase = 0; cwCase < cwCaseCount; cwCase++) {\n");
        sb.append("    if (cwPinned >= 0 && cwCase !== cwPinned) continue;\n");
        sb.append("    const rnd = new CwRng(cwRngSeed + cwCase);\n");
        for (Scenario.Input input : inputs) {
            sb.append("    ").append(declaration(input, drawExpression(input))).append('\n');
        }
        sb.append("    ").append(inputsVector(inputs)).append('\n');
        sb.append("    cwExecute(cwCase, cwSeedValue, cwInputs, () => {\n");
        sb.append(sequenceBody(scenario, inputs, 6));
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("});\n\n");
        return sb.toString();
    }

    private String concurrentSearch(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("test(\"cw_concurrent_search\", async () => {\n");
        sb.append("  const cwSeedValue: number | null = cwSeed(").append(scenario.seed()).append(");\n");
        sb.append("  const cwRngSeed = cwSeedValue ?? ").append(scenario.seed()).append(";\n");
        sb.append("  const cwCaseCount = cwCases(").append(scenario.cases()).append(");\n");
        sb.append("  const cwPinned = cwPinnedCase();\n");
        sb.append("  for (let cwCase = 0; cwCase < cwCaseCount; cwCase++) {\n");
        sb.append("    if (cwPinned >= 0 && cwCase !== cwPinned) continue;\n");
        sb.append("    const rnd = new CwRng(cwRngSeed + cwCase);\n");
        for (Scenario.Input input : inputs) {
            sb.append("    ").append(declaration(input, drawExpression(input))).append('\n');
        }
        sb.append("    ").append(inputsVector(inputs)).append('\n');
        sb.append("    await cwExecuteAsync(cwCase, cwSeedValue, cwInputs, async () => {\n");
        if (scenario.setup() != null) {
            sb.append(indent(setupStatements(scenario.setup()), 6)).append('\n');
        }
        sb.append("      const cwWait = cwBarrier(").append(CONCURRENT_WORKERS).append(");\n");
        sb.append("      await Promise.all(Array.from({ length: ").append(CONCURRENT_WORKERS)
                .append(" }, async () => {\n");
        sb.append("        await cwWait();\n");
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(ensureStatement(substitute(step.call(), inputs)), 8)).append('\n');
        }
        sb.append("      }));\n");
        sb.append(invariantCheck(scenario, inputs, 6, "concurrent execution"));
        sb.append("    });\n");
        sb.append("  }\n");
        sb.append("});\n\n");
        return sb.toString();
    }

    /** The setup, steps and invariant checks for a sequential case, indented inside the closure. */
    private String sequenceBody(Scenario scenario, List<Scenario.Input> inputs, int indent) {
        StringBuilder sb = new StringBuilder();
        if (scenario.setup() != null) {
            sb.append(indent(setupStatements(scenario.setup()), indent)).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(ensureStatement(substitute(step.call(), inputs)), indent)).append('\n');
            sb.append(invariantCheck(scenario, inputs, indent, oneLine(step.description())));
        }
        return sb.toString();
    }

    /** @return the setup fragment without the import lines, which are hoisted to the module top. */
    private static String setupStatements(String setup) {
        StringBuilder sb = new StringBuilder();
        for (String line : setup.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("import ") || stripped.startsWith("import{")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Renders the observation and invariant check that follows a step, or nothing when none is declared. */
    private String invariantCheck(
            Scenario scenario, List<Scenario.Input> inputs, int indent, String stepDescription) {
        if (scenario.observation() == null || scenario.invariant() == null) {
            return "";
        }
        String pad = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        // The check is wrapped in a block so that a scenario with several steps can declare its own
        // `observation` each time: unlike Rust, JavaScript has no `let` shadowing, and a second
        // `const observation` in the same block would not even parse.
        sb.append(pad).append("{\n");
        sb.append(pad).append("  const observation = ")
                .append(substitute(scenario.observation(), inputs)).append(";\n");
        sb.append(pad).append("  if (!(").append(substitute(scenario.invariant(), inputs)).append(")) {\n");
        sb.append(pad).append("    cwFail(cwCase, cwSeedValue, cwInputs, ")
                .append(tsString(oneLine(scenario.invariant())))
                .append(", cwText(observation), ")
                .append(tsString(stepDescription)).append(");\n");
        sb.append(pad).append("  }\n");
        sb.append(pad).append("}\n");
        return sb.toString();
    }

    private static String inputsVector(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder("const cwInputs: Array<[string, string]> = [");
        for (int i = 0; i < inputs.size(); i++) {
            Scenario.Input input = inputs.get(i);
            sb.append(i == 0 ? "" : ", ").append('[').append(tsString(input.name())).append(", cwText(")
                    .append(input.name()).append(")]");
        }
        return sb.append("];").toString();
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

    private static String declaration(Scenario.Input input, String expression) {
        String type = tsType(input);
        if (type != null) {
            return "const " + input.name() + ": " + type + " = " + expression + ";";
        }
        return "const " + input.name() + " = " + expression + ";";
    }

    /** @return the declared TypeScript type when the generator can produce it, otherwise {@code null}. */
    private static String tsType(Scenario.Input input) {
        String declared = input.type();
        if (declared != null && SIMPLE_TYPES.contains(declared)) {
            return declared;
        }
        return switch (input.domain()) {
            case BOOLEAN -> "boolean";
            case STRING -> "string";
            case DECIMAL, DOUBLE, INTEGER, LONG, INSTANT_MILLIS -> "number";
            case NULL, RAW -> null;
        };
    }

    private static boolean isBigInt(Scenario.Input input) {
        return "bigint".equals(input.type());
    }

    private static String literal(Scenario.Input input, String rawValue) {
        String value = rawValue;
        if (value == null) {
            value = input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst();
        }
        return switch (input.domain()) {
            case DECIMAL -> "cwDecimalValue(" + tsString(value) + ")";
            case INTEGER, LONG, INSTANT_MILLIS -> numberLiteral(input, value, "0");
            case DOUBLE -> "cwDecimalValue(" + tsString(value) + ")";
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(value));
            case STRING -> tsString(value);
            case NULL -> "null";
            case RAW -> value.isBlank() ? "undefined" : value;
        };
    }

    private static String numberLiteral(Scenario.Input input, String value, String fallback) {
        String text = value.isBlank() ? fallback : value;
        return isBigInt(input) ? "BigInt(" + text + ")" : text;
    }

    private static String defaultLiteral(Scenario.Input input) {
        return switch (input.domain()) {
            case DECIMAL, DOUBLE, STRING -> "";
            case INTEGER, LONG, INSTANT_MILLIS -> "0";
            case BOOLEAN -> "false";
            case NULL, RAW -> "undefined";
        };
    }

    private static String drawExpression(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        String expression = switch (input.domain()) {
            case DECIMAL -> "cwDecimal(rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "10000" : max) + ", " + Math.max(0, input.scale()) + ")";
            case INTEGER, LONG -> "cwInt(rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "10000" : max) + ")";
            case DOUBLE -> "cwNumber(rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "1000" : max) + ")";
            case BOOLEAN -> "cwBoolean(rnd)";
            case STRING -> "cwString(rnd)";
            case INSTANT_MILLIS -> "cwInt(rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "86400000" : max) + ")";
            case NULL -> "null";
            case RAW -> input.values().isEmpty() ? "undefined" : input.values().getFirst();
        };
        return isBigInt(input) && (input.domain() == Scenario.Domain.INTEGER || input.domain() == Scenario.Domain.LONG)
                ? "BigInt(" + expression + ")" : expression;
    }

    /**
     * Substitutes scenario input references.
     *
     * <p>The placeholder is {@code #{name}} rather than {@code ${name}}: {@code ${...}} is template
     * literal interpolation in TypeScript, so a fragment that builds a string from an input would be
     * silently rewritten. {@code #{...}} is not valid TypeScript anywhere, so it cannot collide.
     */
    private static String substitute(String fragment, List<Scenario.Input> inputs) {
        String result = fragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("#{" + input.name() + "}", input.name());
        }
        return result;
    }

    private static String ensureStatement(String fragment) {
        String text = fragment.stripTrailing();
        if (text.endsWith(";") || text.endsWith("}")) {
            return text;
        }
        return text + ";";
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

    /** Renders a JavaScript/TypeScript string literal, including the surrounding quotes. */
    private static String tsString(String value) {
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

    /** The runtime helpers every generated test uses. Node standard library only, deterministic by seed. */
    static final String HELPERS = """

            class CwRng {
              state: bigint;

              constructor(seed: number) {
                this.state = BigInt.asUintN(64, BigInt(Math.trunc(seed)) * 6364136223846793005n + 1442695040888963407n) | 1n;
              }

              next(): bigint {
                let x = this.state;
                x = BigInt.asUintN(64, x ^ (x << 13n));
                x = x ^ (x >> 7n);
                x = BigInt.asUintN(64, x ^ (x << 17n));
                this.state = x;
                return x;
              }

              int(minimum: number, maximum: number): number {
                if (maximum <= minimum) return minimum;
                const span = BigInt(maximum - minimum + 1);
                return minimum + Number(this.next() % span);
              }

              number(minimum: number, maximum: number): number {
                if (maximum <= minimum) return minimum;
                return minimum + (Number(this.next() % 1000000n) / 1000000) * (maximum - minimum);
              }

              boolean(): boolean {
                return (this.next() & 1n) === 1n;
              }

              string(): string {
                return `cw-${this.next() % 10000n}`;
              }

              decimal(minimum: number, maximum: number, scale: number): number {
                const factor = Math.pow(10, scale);
                return Math.round(this.number(minimum, maximum) * factor) / factor;
              }
            }

            function cwSeed(fallback: number): number | null {
              const raw = process.env.CODEWITNESS_SEED;
              if (raw === undefined || raw.trim() === "") return fallback;
              const value = Number.parseInt(raw, 10);
              return Number.isFinite(value) ? value : fallback;
            }

            function cwCases(fallback: number): number {
              const raw = process.env.CODEWITNESS_CASES;
              if (raw === undefined || raw.trim() === "") return fallback;
              const value = Number.parseInt(raw, 10);
              return Number.isFinite(value) && value > 0 ? value : fallback;
            }

            function cwPinnedCase(): number {
              const raw = process.env.CODEWITNESS_CASE;
              if (raw === undefined || raw.trim() === "") return -1;
              const value = Number.parseInt(raw, 10);
              return Number.isFinite(value) ? value : -1;
            }

            function cwInt(rnd: CwRng, minimum: number, maximum: number): number {
              return rnd.int(minimum, maximum);
            }

            function cwNumber(rnd: CwRng, minimum: number, maximum: number): number {
              return rnd.number(minimum, maximum);
            }

            function cwDecimal(rnd: CwRng, minimum: number, maximum: number, scale: number): number {
              return rnd.decimal(minimum, maximum, scale);
            }

            function cwBoolean(rnd: CwRng): boolean {
              return rnd.boolean();
            }

            function cwString(rnd: CwRng): string {
              return rnd.string();
            }

            function cwDecimalValue(text: string): number {
              const value = Number.parseFloat(text);
              return Number.isFinite(value) ? value : 0;
            }

            function cwText(value: unknown): string {
              if (typeof value === "bigint") return value.toString();
              if (typeof value === "object" && value !== null) {
                try {
                  return JSON.stringify(value) ?? String(value);
                } catch {
                  return String(value);
                }
              }
              return String(value);
            }

            function cwSanitize(value: string): string {
              return value.replace(/\\|/g, "/").replace(/\\s+/g, " ").trim();
            }

            function cwBarrier(count: number): () => Promise<void> {
              let arrived = 0;
              let release: (() => void) | null = null;
              const ready = new Promise<void>((resolve) => {
                release = resolve;
              });
              return async () => {
                arrived += 1;
                if (arrived >= count && release !== null) release();
                await ready;
              };
            }

            function cwFail(
              cwCase: number,
              cwSeedValue: number | null,
              cwInputs: Array<[string, string]>,
              expected: string,
              actual: string,
              step: string,
            ): never {
              const rendered = cwInputs.map(([key, value]) => `${key}=${value}`).join(", ");
              const parts = [
                "CODEWITNESS_COUNTEREXAMPLE",
                `case=${cwCase}`,
                `seed=${cwSeedValue === null ? "" : cwSeedValue}`,
                `inputs={${rendered}}`,
                `expected=${cwSanitize(expected)}`,
                `actual=${cwSanitize(actual)}`,
                `invariant=${cwSanitize(CW_INVARIANT)}`,
                `step=${cwSanitize(step)}`,
                `requirement=${CW_REQUIREMENT}`,
                `obligation=${CW_OBLIGATION}`,
              ];
              throw new Error(parts.join("|"));
            }

            function cwExecute(
              cwCase: number,
              cwSeedValue: number | null,
              cwInputs: Array<[string, string]>,
              body: () => void,
            ): void {
              try {
                body();
              } catch (error) {
                const message = error instanceof Error ? error.message : String(error);
                if (message.includes("CODEWITNESS_COUNTEREXAMPLE")) throw error;
                cwFail(cwCase, cwSeedValue, cwInputs, "the declared steps must not throw", message, "step execution");
              }
            }

            async function cwExecuteAsync(
              cwCase: number,
              cwSeedValue: number | null,
              cwInputs: Array<[string, string]>,
              body: () => Promise<void>,
            ): Promise<void> {
              try {
                await body();
              } catch (error) {
                const message = error instanceof Error ? error.message : String(error);
                if (message.includes("CODEWITNESS_COUNTEREXAMPLE")) throw error;
                cwFail(cwCase, cwSeedValue, cwInputs, "the declared steps must not throw", message, "step execution");
              }
            }
            """;
}

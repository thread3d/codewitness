package dev.codewitness.rust.generate;

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
 * Turns declared scenarios into executable Rust integration tests.
 *
 * <p>A scenario supplies the oracle — the setup, the calls and the invariant, written in Rust in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the
 * seeded random search, concurrent execution and the structured failure report. CodeWitness never
 * invents an oracle, because an invented oracle is exactly the kind of assertion this tool exists to
 * avoid.
 *
 * <p>The generated file is an ordinary Cargo integration test under {@code tests/}, so it is
 * compiled and run by Cargo itself, links the crate under test by name, and needs no dependency
 * beyond the standard library. Each failing case panics with a single
 * {@code CODEWITNESS_COUNTEREXAMPLE|...} line carrying the exact inputs, seed and case index, which
 * is what makes the counterexample reproducible by {@code cargo test} with the recorded environment.
 *
 * <p>Rust has no standard decimal type, so the {@code decimal} domain generates {@code f64}; a
 * project that models money with integer minor units should use an integer domain, which is what the
 * fixture does. This limitation is stated in the documentation rather than hidden.
 */
public final class RustScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Default number of threads used by CONCURRENT scenarios. */
    private static final int CONCURRENT_THREADS = 8;

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    private static final Set<String> INTEGER_PRIMITIVES = Set.of(
            "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize");
    private static final Set<String> PRIMITIVES = Set.of(
            "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize",
            "f32", "f64", "bool", "String", "char");

    @Override
    public String id() {
        return "rust-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUST);
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
                    scenario.description(), scenario.seed(), null,
                    scenario.observation() != null && scenario.invariant() != null));
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
        sb.append("// It uses only the standard library, so it compiles on any stable toolchain.\n\n");
        sb.append("#![allow(dead_code, unused_imports, unused_mut, unused_variables, non_snake_case)]\n\n");
        sb.append("const CW_REQUIREMENT: &str = ").append(rustString(requirement.id())).append(";\n");
        sb.append("const CW_OBLIGATION: &str = ").append(rustString(obligation.id())).append(";\n");
        sb.append("const CW_INVARIANT: &str = ")
                .append(rustString(oneLine(scenario.invariant() == null ? "(no invariant declared)" : scenario.invariant())))
                .append(";\n\n");

        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            sb.append(boundaryCase(scenario, index, inputs, values));
            index++;
        }
        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input has a declared type but none declares a boundary value, so there is nothing
            // to enumerate. Run the declared defaults once instead of emitting a file with no test
            // functions at all: an empty test file reports nothing, and a scenario that reports
            // nothing must not be mistaken for one that ran and passed.
            sb.append(boundaryCase(scenario, 0, inputs, defaultValues(inputs)));
        }
        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
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

    // ------------------------------------------------------------------ test functions

    private String boundaryCase(
            Scenario scenario, int index, List<Scenario.Input> inputs, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("#[test]\n");
        sb.append("fn cw_case").append(String.format(Locale.ROOT, "%04d", index)).append("() {\n");
        sb.append("    let cw_case: i64 = ").append(index).append(";\n");
        sb.append("    let cw_seed_value: Option<u64> = None;\n");
        for (Scenario.Input input : inputs) {
            String value = values.get(input.name());
            sb.append("    ").append(declaration(input, literal(input, value))).append('\n');
        }
        sb.append("    ").append(inputsVector(inputs)).append('\n');
        sb.append("    cw_execute(cw_case, cw_seed_value, &cw_inputs, || {\n");
        sb.append(sequenceBody(scenario, inputs, 8));
        sb.append("    });\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String singleCase(Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("#[test]\n");
        sb.append("fn cw_single_case() {\n");
        sb.append("    let cw_case: i64 = 0;\n");
        sb.append("    let cw_seed_value: Option<u64> = None;\n");
        sb.append("    let cw_inputs: Vec<(&str, String)> = Vec::new();\n");
        sb.append("    cw_execute(cw_case, cw_seed_value, &cw_inputs, || {\n");
        sb.append(sequenceBody(scenario, List.of(), 8));
        sb.append("    });\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String randomSearch(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("#[test]\n");
        sb.append("fn cw_random_search() {\n");
        sb.append("    let cw_seed_value: Option<u64> = Some(cw_seed(").append(scenario.seed()).append("));\n");
        sb.append("    let cw_rng_seed: u64 = cw_seed_value.unwrap_or(").append(scenario.seed()).append(");\n");
        sb.append("    let cw_case_count: u64 = cw_cases(").append(scenario.cases()).append(");\n");
        sb.append("    let cw_pinned: i64 = cw_pinned_case();\n");
        sb.append("    let mut cw_case: i64 = 0;\n");
        sb.append("    while (cw_case as u64) < cw_case_count {\n");
        sb.append("        if cw_pinned >= 0 && cw_case != cw_pinned {\n");
        sb.append("            cw_case += 1;\n");
        sb.append("            continue;\n");
        sb.append("        }\n");
        sb.append("        let mut rnd = CwRng::new(cw_rng_seed.wrapping_add(cw_case as u64));\n");
        for (Scenario.Input input : inputs) {
            sb.append("        ").append(declaration(input, drawExpression(input))).append('\n');
        }
        sb.append("        ").append(inputsVector(inputs)).append('\n');
        sb.append("        cw_execute(cw_case, cw_seed_value, &cw_inputs, || {\n");
        sb.append(sequenceBody(scenario, inputs, 12));
        sb.append("        });\n");
        sb.append("        cw_case += 1;\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String concurrentSearch(List<Scenario.Input> inputs, Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("#[test]\n");
        sb.append("fn cw_concurrent_search() {\n");
        sb.append("    let cw_seed_value: Option<u64> = Some(cw_seed(").append(scenario.seed()).append("));\n");
        sb.append("    let cw_rng_seed: u64 = cw_seed_value.unwrap_or(").append(scenario.seed()).append(");\n");
        sb.append("    let cw_case_count: u64 = cw_cases(").append(scenario.cases()).append(");\n");
        sb.append("    let cw_pinned: i64 = cw_pinned_case();\n");
        sb.append("    let mut cw_case: i64 = 0;\n");
        sb.append("    while (cw_case as u64) < cw_case_count {\n");
        sb.append("        if cw_pinned >= 0 && cw_case != cw_pinned {\n");
        sb.append("            cw_case += 1;\n");
        sb.append("            continue;\n");
        sb.append("        }\n");
        sb.append("        let mut rnd = CwRng::new(cw_rng_seed.wrapping_add(cw_case as u64));\n");
        for (Scenario.Input input : inputs) {
            sb.append("        ").append(declaration(input, drawExpression(input))).append('\n');
        }
        sb.append("        ").append(inputsVector(inputs)).append('\n');
        sb.append("        cw_execute(cw_case, cw_seed_value, &cw_inputs, || {\n");
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), 12)).append('\n');
        }
        sb.append("            let cw_barrier = std::sync::Barrier::new(")
                .append(CONCURRENT_THREADS).append(");\n");
        sb.append("            std::thread::scope(|cw_scope| {\n");
        sb.append("                for _ in 0..").append(CONCURRENT_THREADS).append(" {\n");
        sb.append("                    cw_scope.spawn(|| {\n");
        sb.append("                        cw_barrier.wait();\n");
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(ensureStatement(substitute(step.call(), inputs)), 24)).append('\n');
        }
        sb.append("                    });\n");
        sb.append("                }\n");
        sb.append("            });\n");
        sb.append(invariantCheck(scenario, inputs, 12, "concurrent execution"));
        sb.append("        });\n");
        sb.append("        cw_case += 1;\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    /** The setup, steps and invariant checks for a sequential case, indented inside the closure. */
    private String sequenceBody(Scenario scenario, List<Scenario.Input> inputs, int indent) {
        StringBuilder sb = new StringBuilder();
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), indent)).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(ensureStatement(substitute(step.call(), inputs)), indent)).append('\n');
            sb.append(invariantCheck(scenario, inputs, indent, oneLine(step.description())));
        }
        return sb.toString();
    }

    /** Renders the observation and invariant check that follows a step, or nothing when none is declared. */
    private String invariantCheck(
            Scenario scenario, List<Scenario.Input> inputs, int indent, String stepDescription) {
        if (scenario.observation() == null || scenario.invariant() == null) {
            return "";
        }
        String pad = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        sb.append(pad).append("let observation = ")
                .append(substitute(scenario.observation(), inputs)).append(";\n");
        sb.append(pad).append("if !(").append(substitute(scenario.invariant(), inputs)).append(") {\n");
        sb.append(pad).append("    cw_fail(cw_case, cw_seed_value, &cw_inputs, ")
                .append(rustString(oneLine(scenario.invariant())))
                .append(", &format!(\"{:?}\", observation), ")
                .append(rustString(stepDescription)).append(");\n");
        sb.append(pad).append("}\n");
        return sb.toString();
    }

    private static String inputsVector(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder("let cw_inputs: Vec<(&str, String)> = vec![");
        for (int i = 0; i < inputs.size(); i++) {
            Scenario.Input input = inputs.get(i);
            sb.append(i == 0 ? "" : ", ").append('(').append(rustString(input.name())).append(", ")
                    .append(inputToText(input)).append(')');
        }
        return sb.append("];").toString();
    }

    private static String inputToText(Scenario.Input input) {
        String name = input.name();
        String type = input.type();
        if (isPrimitive(type) || "String".equals(type)) {
            return name + ".to_string()";
        }
        // A custom or raw type must implement Debug; the documentation states this requirement.
        return "format!(\"{:?}\", " + name + ")";
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
        String type = rustType(input);
        if (type != null) {
            return "let " + input.name() + ": " + type + " = " + expression + ";";
        }
        return "let " + input.name() + " = " + expression + ";";
    }

    /** @return the declared Rust type when it can be trusted for an annotation, otherwise {@code null}. */
    private static String rustType(Scenario.Input input) {
        String declared = input.type();
        if (isPrimitive(declared) || (declared != null && declared.startsWith("Option<"))) {
            return declared;
        }
        return switch (input.domain()) {
            case DECIMAL, DOUBLE -> "f64";
            case INTEGER, LONG, INSTANT_MILLIS -> "i64";
            case BOOLEAN -> "bool";
            case STRING -> "String";
            case NULL -> "Option<i64>";
            case RAW -> null;
        };
    }

    private static boolean isPrimitive(String type) {
        return type != null && PRIMITIVES.contains(type);
    }

    private static String literal(Scenario.Input input, String rawValue) {
        String value = rawValue;
        if (value == null) {
            value = input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst();
        }
        return switch (input.domain()) {
            case DECIMAL -> "cw_f64(" + rustString(value) + ")";
            case INTEGER, LONG, INSTANT_MILLIS -> value.isBlank() ? "0" : value;
            case DOUBLE -> value.isBlank() ? "0.0" : value;
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(value));
            case STRING -> rustString(value);
            case NULL -> "None";
            case RAW -> value.isBlank() ? "None" : value;
        };
    }

    private static String defaultLiteral(Scenario.Input input) {
        return switch (input.domain()) {
            case DECIMAL, STRING -> "";
            case INTEGER, LONG, INSTANT_MILLIS, DOUBLE -> "0";
            case BOOLEAN -> "false";
            case NULL, RAW -> "null";
        };
    }

    private static String drawExpression(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (input.domain()) {
            case DECIMAL -> coerce(input, "cw_decimal(&mut rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "10000" : max) + ", " + Math.max(0, input.scale()) + ")");
            case INTEGER, LONG -> coerce(input, "cw_int(&mut rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "10000" : max) + ")");
            case DOUBLE -> coerce(input, "cw_double(&mut rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "1000" : max) + ")");
            case BOOLEAN -> "cw_boolean(&mut rnd)";
            case STRING -> "cw_string(&mut rnd)";
            case INSTANT_MILLIS -> coerce(input, "cw_int(&mut rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "86400000" : max) + ")");
            case NULL -> "None";
            case RAW -> input.values().isEmpty() ? "None" : input.values().getFirst();
        };
    }

    /** Casts a generated i64/f64 value to the input's declared primitive type when they differ. */
    private static String coerce(Scenario.Input input, String expression) {
        String type = input.type();
        if (type == null) {
            return expression;
        }
        return switch (input.domain()) {
            case INTEGER, LONG, INSTANT_MILLIS -> INTEGER_PRIMITIVES.contains(type) && !type.equals("i64")
                    ? "(" + expression + " as " + type + ")" : expression;
            case DOUBLE -> type.equals("f32") ? "(" + expression + " as f32)" : expression;
            case DECIMAL -> type.equals("f32") ? "(" + expression + " as f32)" : expression;
            default -> expression;
        };
    }

    private static String substitute(String fragment, List<Scenario.Input> inputs) {
        String result = fragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("${" + input.name() + "}", input.name());
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

    /** Renders a Rust string literal, including the surrounding quotes. */
    private static String rustString(String value) {
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

    /** The runtime helpers every generated test uses. Standard library only, deterministic by seed. */
    static final String HELPERS = """

            struct CwRng {
                state: u64,
            }

            impl CwRng {
                fn new(seed: u64) -> CwRng {
                    CwRng {
                        state: seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407) | 1,
                    }
                }

                fn next_u64(&mut self) -> u64 {
                    let mut x = self.state;
                    x ^= x << 13;
                    x ^= x >> 7;
                    x ^= x << 17;
                    self.state = x;
                    x
                }

                fn int(&mut self, minimum: i64, maximum: i64) -> i64 {
                    if maximum <= minimum {
                        return minimum;
                    }
                    let span = (maximum - minimum + 1) as u64;
                    minimum + (self.next_u64() % span) as i64
                }

                fn double(&mut self, minimum: f64, maximum: f64) -> f64 {
                    if maximum <= minimum {
                        return minimum;
                    }
                    minimum + (self.next_u64() as f64 / u64::MAX as f64) * (maximum - minimum)
                }

                fn boolean(&mut self) -> bool {
                    self.next_u64() & 1 == 1
                }

                fn string(&mut self) -> String {
                    format!("cw-{}", self.next_u64() % 10000)
                }

                fn decimal(&mut self, minimum: f64, maximum: f64, scale: u32) -> f64 {
                    let value = self.double(minimum, maximum);
                    let factor = 10f64.powi(scale as i32);
                    (value * factor).round() / factor
                }
            }

            fn cw_seed(fallback: u64) -> u64 {
                std::env::var("CODEWITNESS_SEED")
                    .ok()
                    .and_then(|value| value.trim().parse::<u64>().ok())
                    .unwrap_or(fallback)
            }

            fn cw_cases(fallback: u64) -> u64 {
                std::env::var("CODEWITNESS_CASES")
                    .ok()
                    .and_then(|value| value.trim().parse::<u64>().ok())
                    .unwrap_or(fallback)
            }

            fn cw_pinned_case() -> i64 {
                std::env::var("CODEWITNESS_CASE")
                    .ok()
                    .and_then(|value| value.trim().parse::<i64>().ok())
                    .unwrap_or(-1)
            }

            fn cw_int(rnd: &mut CwRng, minimum: i64, maximum: i64) -> i64 {
                rnd.int(minimum, maximum)
            }

            fn cw_double(rnd: &mut CwRng, minimum: f64, maximum: f64) -> f64 {
                rnd.double(minimum, maximum)
            }

            fn cw_decimal(rnd: &mut CwRng, minimum: f64, maximum: f64, scale: u32) -> f64 {
                rnd.decimal(minimum, maximum, scale)
            }

            fn cw_boolean(rnd: &mut CwRng) -> bool {
                rnd.boolean()
            }

            fn cw_string(rnd: &mut CwRng) -> String {
                rnd.string()
            }

            fn cw_f64(text: &str) -> f64 {
                text.parse::<f64>().unwrap_or(0.0)
            }

            fn cw_sanitize(value: &str) -> String {
                value.replace('|', "/").replace('\\n', " ")
            }

            fn cw_panic_message(payload: &Box<dyn std::any::Any + Send>) -> String {
                if let Some(text) = payload.downcast_ref::<&str>() {
                    (*text).to_string()
                } else if let Some(text) = payload.downcast_ref::<String>() {
                    text.clone()
                } else {
                    "panic with a non-text payload".to_string()
                }
            }

            fn cw_fail(
                case: i64,
                seed: Option<u64>,
                inputs: &[(&str, String)],
                expected: &str,
                actual: &str,
                step: &str,
            ) -> ! {
                let mut text = String::from("CODEWITNESS_COUNTEREXAMPLE");
                text.push_str(&format!("|case={}", case));
                text.push_str(&format!(
                    "|seed={}",
                    seed.map(|value| value.to_string()).unwrap_or_default()
                ));
                let rendered = inputs
                    .iter()
                    .map(|(key, value)| format!("{}={}", key, value))
                    .collect::<Vec<String>>()
                    .join(", ");
                text.push_str(&format!("|inputs={{{}}}", rendered));
                text.push_str(&format!("|expected={}", cw_sanitize(expected)));
                text.push_str(&format!("|actual={}", cw_sanitize(actual)));
                text.push_str(&format!("|invariant={}", cw_sanitize(CW_INVARIANT)));
                text.push_str(&format!("|step={}", cw_sanitize(step)));
                text.push_str(&format!("|requirement={}", CW_REQUIREMENT));
                text.push_str(&format!("|obligation={}", CW_OBLIGATION));
                panic!("{}", text);
            }

            fn cw_execute<F>(case: i64, seed: Option<u64>, inputs: &[(&str, String)], body: F)
            where
                F: FnOnce(),
            {
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(body));
                if let Err(payload) = outcome {
                    let message = cw_panic_message(&payload);
                    if message.contains("CODEWITNESS_COUNTEREXAMPLE") {
                        std::panic::resume_unwind(payload);
                    }
                    cw_fail(
                        case,
                        seed,
                        inputs,
                        "the declared steps must not panic",
                        &message,
                        "step execution",
                    );
                }
            }
            """;
}

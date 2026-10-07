package dev.codewitness.cpp.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.VerificationEngine;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.cpp.CppProjectDetector;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns declared scenarios into executable C++ programs.
 *
 * <p>A scenario supplies the oracle - the setup, the calls and the invariant, written in C++ in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the seeded
 * random search, concurrent execution and the structured failure report. CodeWitness never invents an
 * oracle, because an invented oracle is exactly the kind of assertion this tool exists to avoid.
 *
 * <p>What is generated is a single self-contained translation unit with a {@code main}, not a test case in
 * somebody's framework. C and C++ have no test framework that a project is guaranteed to have, and requiring
 * one would make the adapter need an installation it does not have; a program that runs the declared
 * experiments, prints a counterexample and exits non-zero is what CTest reads anyway, and it is the whole of
 * what the generated test needs.
 *
 * <p>The generated file does not become part of the project's build. It is written into the run's artefact
 * store and compiled by {@link CppGeneratedTestRunner} with the compiler and the flags the project's own
 * build recorded in {@code compile_commands.json}, which is why this adapter, unlike the Java and C#
 * adapters, never edits the tree it is verifying: there is no build file to add a test to, because the test
 * is compiled the way the project compiles itself.
 *
 * <p>The file includes every header the project has. A scenario fragment may name any of the project's
 * types and the generator cannot read a C++ declaration to know which, so the alternative would be to
 * require the scenario to spell out its includes in a configuration key that does not exist. The limit is
 * stated rather than hidden: a project whose headers cannot all be included together gets a generated test
 * that does not compile, which is reported as such and leaves the requirement {@code UNVERIFIED}, never as a
 * code defect.
 *
 * <p>Every failing case reports through a single {@code CODEWITNESS_COUNTEREXAMPLE|...} line, carrying the
 * exact inputs, the seed, the case index and the invariant; it is printed to standard output and the process
 * exits non-zero, which is how the message reaches the report the engine reads it from.
 */
public final class CppScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** Number of workers a CONCURRENT scenario runs its cases from. */
    private static final int CONCURRENT_WORKERS = 8;

    /** A declared C++ type that can be named in a declaration exactly as the scenario declared it. */
    private static final String TYPE_PATTERN = "[A-Za-z_][A-Za-z0-9_:<> ,*&]*";

    /** The header extensions a generated test includes, so that a scenario's types resolve. */
    private static final Set<String> HEADER_EXTENSIONS = Set.of("h", "hpp", "hh", "hxx", "ipp", "tpp");

    @Override
    public String id() {
        return "cpp-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CPP);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        if (CppProjectDetector.buildRoot(context.projectRoot()) == null) {
            // Nothing to compile or link a generated test with; see the class javadoc.
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
            if (scenario == null) {
                continue;
            }
            String className = classNameFor(scenario, obligation);
            String source = render(context, requirement, obligation, scenario, className);
            generated.add(new GeneratedTest(className, source, className,
                    List.of(requirement.id()), List.of(obligation.id()),
                    scenario.description(), scenario.seed(), null,
                    scenario.observation() != null && scenario.invariant() != null));
        }
        return List.copyOf(generated);
    }

    /**
     * @return the identifier of the generated program for a scenario obligation.
     *
     * <p>The name is a C++ identifier and, more importantly, a stable one: it is derived from the scenario
     *     and obligation ids only, so a re-run of the same scenario produces the same name and a reported
     *     failure can be paired with the obligation that declared it.
     */
    public static String classNameFor(Scenario scenario, VerificationObligation obligation) {
        String suffix = scenario.mode() == Scenario.Mode.CONCURRENT ? "Concurrent" : "";
        return "Cw" + camel(scenario.id()) + camel(obligation.id()) + suffix;
    }

    /**
     * @return a PascalCase identifier for an arbitrary id such as {@code SC-ROLLING}.
     *
     * <p>Each separator starts a new word and the rest of a word is lower-cased, so {@code SC-ROLLING}
     *     becomes {@code ScRolling} rather than a run of capitals.
     */
    public static String camel(String value) {
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
                start = false;
            } else {
                start = true;
            }
        }
        return sb.isEmpty() ? "Case" : sb.toString();
    }

    /**
     * @return every header of the project, relative to the project root, in path order.
     *
     * <p>See the class javadoc for why the generated file includes all of them.
     */
    public static List<String> projectHeaders(VerificationContext context) {
        List<String> headers = new ArrayList<>();
        for (java.nio.file.Path file : CppProjectDetector.sourceFiles(context.projectRoot())) {
            if (HEADER_EXTENSIONS.contains(CppProjectDetector.extensionOf(file))) {
                headers.add(context.projectRoot().relativize(file).toString());
            }
        }
        return List.copyOf(headers);
    }

    // ------------------------------------------------------------------ rendering

    private String render(
            VerificationContext context,
            Requirement requirement,
            VerificationObligation obligation,
            Scenario scenario,
            String className) {

        List<Scenario.Input> inputs = collectInputs(scenario);
        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        boolean random = scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1;

        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by CodeWitness ").append(VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("// Requirement: ").append(requirement.id()).append(" - ")
                .append(oneLine(requirement.title())).append('\n');
        sb.append("// Obligation: ").append(obligation.id()).append(" - ")
                .append(oneLine(obligation.description())).append('\n');
        sb.append("// Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases())
                .append(")\n");
        sb.append("// This file is temporary: CodeWitness keeps a copy with the run so a counterexample can "
                + "be replayed.\n\n");
        sb.append("#include <chrono>\n#include <cstdio>\n#include <cstdlib>\n#include <cstring>\n")
                .append("#include <random>\n#include <string>\n#include <vector>\n");
        if (scenario.mode() == Scenario.Mode.CONCURRENT) {
            sb.append("#include <atomic>\n#include <thread>\n");
        }
        sb.append('\n');
        for (String header : projectHeaders(context)) {
            sb.append("#include ").append(cppString(header)).append('\n');
        }
        sb.append("const char* const CwRequirement = ").append(cppString(requirement.id())).append(";\n");
        sb.append("const char* const CwObligation = ").append(cppString(obligation.id())).append(";\n");
        sb.append("const char* const CwInvariant = ")
                .append(cppString(oneLine(scenario.invariant() == null
                        ? "(no invariant declared)" : scenario.invariant())))
                .append(";\n");
        sb.append("const long long CwScenarioCases = ").append(scenario.cases()).append("LL;\n");
        sb.append("const long long CwScenarioSeed = ").append(scenario.seed()).append("LL;\n\n");
        sb.append(HELPERS);

        sb.append("int main(int argc, char** argv) {\n");
        sb.append("    long long seed = CwScenarioSeed;\n");
        sb.append("    const bool hasSeed = true;\n");
        sb.append("    long long pinned = -1;\n");
        sb.append("    for (int i = 1; i < argc; ++i) {\n");
        sb.append("        const char* argument = argv[i];\n");
        sb.append("        if (std::strncmp(argument, \"--codewitness-seed=\", 19) == 0) {\n");
        sb.append("            seed = std::strtoll(argument + 19, nullptr, 10);\n");
        sb.append("        } else if (std::strncmp(argument, \"--codewitness-case=\", 19) == 0) {\n");
        sb.append("            pinned = std::strtoll(argument + 19, nullptr, 10);\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    if (const char* value = std::getenv(\"CODEWITNESS_SEED\")) {\n");
        sb.append("        seed = std::strtoll(value, nullptr, 10);\n");
        sb.append("    }\n");
        sb.append("    if (const char* value = std::getenv(\"CODEWITNESS_CASE\")) {\n");
        sb.append("        pinned = std::strtoll(value, nullptr, 10);\n");
        sb.append("    }\n");
        sb.append("    long long caseIndex = 0;\n\n");

        for (Map<String, String> values : boundaryCases) {
            sb.append(caseBody(scenario, inputs, values, "        ", "caseIndex"));
            sb.append("    ++caseIndex;\n");
        }
        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input declares a type but none declares a boundary value, so there is nothing to
            // enumerate. Run the declared defaults once instead of emitting a program with no case: a
            // program that checks nothing must not be mistaken for one that ran and passed.
            sb.append(caseBody(scenario, inputs, defaultValues(inputs), "        ", "caseIndex"));
            sb.append("    ++caseIndex;\n");
        }
        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            sb.append(caseBody(scenario, List.of(), Map.of(), "        ", "caseIndex"));
            sb.append("    ++caseIndex;\n");
        }
        if (random) {
            sb.append(scenario.mode() == Scenario.Mode.CONCURRENT
                    ? concurrentCases(scenario, inputs)
                    : randomCases(scenario, inputs));
        }
        sb.append("    return 0;\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String caseBody(
            Scenario scenario,
            List<Scenario.Input> inputs,
            Map<String, String> values,
            String pad,
            String caseVariable) {

        StringBuilder sb = new StringBuilder();
        sb.append(pad).append("if (pinned < 0 || pinned == ").append(caseVariable).append(") {\n");
        String inner = pad + "    ";
        for (Scenario.Input input : inputs) {
            sb.append(inner).append(declaration(input, literal(input, values.get(input.name())))).append('\n');
        }
        sb.append(inputsExpression(inputs, inner)).append('\n');
        sb.append(sequenceBody(scenario, inputs, inner));
        sb.append(pad).append("}\n");
        return sb.toString();
    }

    private String randomCases(Scenario scenario, List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder();
        // The loop variable is not called "case": that is a C++ keyword, and a program that used it would
        // not compile at all - which is exactly the failure the generated test's own compile step reports,
        // and which the fixture found once.
        sb.append("    for (long long draw = 0; draw < CwScenarioCases; ++draw, ++caseIndex) {\n");
        sb.append("        if (pinned >= 0 && pinned != caseIndex) {\n");
        sb.append("            continue;\n");
        sb.append("        }\n");
        sb.append("        std::mt19937_64 rng(static_cast<unsigned long long>(seed) ");
        sb.append("+ static_cast<unsigned long long>(draw));\n");
        for (Scenario.Input input : inputs) {
            sb.append("        ").append(declaration(input, drawExpression(input))).append('\n');
        }
        sb.append(inputsExpression(inputs, "        ")).append('\n');
        sb.append(sequenceBody(scenario, inputs, "        "));
        sb.append("    }\n");
        return sb.toString();
    }

    private String concurrentCases(Scenario scenario, List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("        std::atomic<long long> next(0);\n");
        sb.append("        std::atomic<bool> stopped(false);\n");
        sb.append("        const long long first = caseIndex;\n");
        sb.append("        auto worker = [&]() {\n");
        sb.append("            for (;;) {\n");
        sb.append("                const long long draw = next.fetch_add(1);\n");
        sb.append("                if (draw >= CwScenarioCases || stopped.load()) {\n");
        sb.append("                    return;\n");
        sb.append("                }\n");
        sb.append("                const long long caseIndex = first + draw;\n");
        sb.append("                if (pinned >= 0 && pinned != caseIndex) {\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                std::mt19937_64 rng(static_cast<unsigned long long>(seed) ");
        sb.append("+ static_cast<unsigned long long>(draw));\n");
        for (Scenario.Input input : inputs) {
            sb.append("                ").append(declaration(input, drawExpression(input))).append('\n');
        }
        sb.append(inputsExpression(inputs, "                ")).append('\n');
        sb.append(sequenceBody(scenario, inputs, "                "));
        sb.append("            }\n");
        sb.append("        };\n");
        sb.append("        std::vector<std::thread> workers;\n");
        sb.append("        for (int worker = 0; worker < ").append(CONCURRENT_WORKERS).append("; ++worker) {\n");
        sb.append("            workers.emplace_back(worker);\n");
        sb.append("        }\n");
        sb.append("        for (auto& worker : workers) {\n");
        sb.append("            worker.join();\n");
        sb.append("        }\n");
        sb.append("        caseIndex = first + CwScenarioCases;\n");
        sb.append("    }\n");
        return sb.toString();
    }

    /**
     * @return the setup, the step calls and the invariant check for one case.
     *
     * <p>The invariant is checked after every step that declares an observation, so a scenario that
     *     describes an accumulating state is checked as it grows rather than only at the end. The check is
     *     wrapped in its own block because the observation has a fixed name that each step re-declares.
     */
    private String sequenceBody(Scenario scenario, List<Scenario.Input> inputs, String pad) {
        StringBuilder sb = new StringBuilder();
        if (scenario.setup() != null && !scenario.setup().isBlank()) {
            sb.append(indent(scenario.setup(), pad)).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append(pad).append(substitute(ensureStatement(step.call()), inputs)).append('\n');
            if (scenario.observation() != null && scenario.invariant() != null) {
                sb.append(pad).append("{\n");
                sb.append(pad).append("    const auto observation = ")
                        .append(ensureExpression(substitute(scenario.observation(), inputs))).append(";\n");
                sb.append(pad).append("    if (!(")
                        .append(ensureExpression(substitute(scenario.invariant(), inputs))).append(")) {\n");
                sb.append(pad).append("        cwFail(caseIndex, hasSeed, seed, cwInputs, CwInvariant, ")
                        .append("cwFormat(observation), ").append(cppString(oneLine(step.description())))
                        .append(");\n");
                sb.append(pad).append("    }\n");
                sb.append(pad).append("}\n");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ declarations and values

    /** @return the declaration of one input, with the value it should hold for this case. */
    static String declaration(Scenario.Input input, String value) {
        return "const " + type(input) + " " + input.name() + " = " + value + ";";
    }

    /** @return the C++ type an input declares, defaulted when the declaration is not a type at all. */
    static String type(Scenario.Input input) {
        String declared = input.type() == null ? "" : input.type().strip();
        return declared.matches(TYPE_PATTERN) && !declared.isEmpty() ? declared : "long long";
    }

    /**
     * @return the initialiser for an explicit boundary value.
     *
     * <p>The value is emitted as the scenario wrote it, because the scenario author wrote it in the
     *     language's own syntax: {@code 85800000}, {@code -10000}, {@code 0.50}, {@code true},
     *     {@code "text"}. A value that does not fit the declared type is a compile error, which the run
     *     reports as a generated test that did not compile rather than as a code defect.
     */
    static String literal(Scenario.Input input, String value) {
        String text = value == null ? "0" : value.strip();
        if (text.isEmpty()) {
            return "0";
        }
        return switch (input.domain()) {
            case BOOLEAN -> normaliseBoolean(text);
            case STRING -> cppString(text);
            default -> text;
        };
    }

    private static String normaliseBoolean(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("false")) {
            return lower;
        }
        if (lower.equals("1")) {
            return "true";
        }
        if (lower.equals("0")) {
            return "false";
        }
        return "true";
    }

    /** @return a bounded expression that draws one random value for the input from its own stream. */
    static String drawExpression(Scenario.Input input) {
        return switch (input.domain()) {
            case BOOLEAN -> "(rng() % 2ULL) == 1ULL";
            case STRING -> "std::string(\"value\")";
            case DOUBLE, DECIMAL -> {
                BigDecimal min = input.minDecimal();
                BigDecimal max = input.maxDecimal();
                String low = min == null ? "0.0" : min.toPlainString();
                String high = max == null ? "1000.0" : max.toPlainString();
                yield low + " + (static_cast<double>(rng() % 1000001ULL) / 1000000.0) * ("
                        + high + " - " + low + ")";
            }
            default -> {
                BigDecimal min = input.minDecimal();
                BigDecimal max = input.maxDecimal();
                long low = min == null ? 0L : min.longValue();
                long high = max == null ? 1000L : max.longValue();
                long span = Math.max(1L, high - low + 1L);
                String offset = "static_cast<long long>(rng() % " + span + "ULL)";
                yield low == 0L ? offset : low + "LL + " + offset;
            }
        };
    }

    /**
     * @return the statement that builds the {@code {name=value, name=value}} string this case reports,
     *     so a counterexample carries the exact inputs that reproduced the violation
     */
    static String inputsExpression(List<Scenario.Input> inputs, String pad) {
        StringBuilder sb = new StringBuilder(pad).append("std::string cwInputs = \"{\";\n");
        for (int i = 0; i < inputs.size(); i++) {
            Scenario.Input input = inputs.get(i);
            sb.append(pad).append("cwInputs += ").append(i == 0 ? "\"\"" : "\", \"")
                    .append("; cwInputs += ").append(cppString(input.name() + "="))
                    .append("; cwInputs += cwFormat(").append(input.name()).append(");\n");
        }
        return sb.append(pad).append("cwInputs += \"}\";").toString();
    }

    /** @return the inputs of every step, deduplicated by name in first-seen order. */
    static List<Scenario.Input> collectInputs(Scenario scenario) {
        Map<String, Scenario.Input> byName = new LinkedHashMap<>();
        for (Scenario.Step step : scenario.steps()) {
            for (Scenario.Input input : step.inputs()) {
                byName.putIfAbsent(input.name(), input);
            }
        }
        return List.copyOf(byName.values());
    }

    private static Map<String, String> defaultValues(List<Scenario.Input> inputs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Scenario.Input input : inputs) {
            values.put(input.name(), input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst());
        }
        return values;
    }

    private static String defaultLiteral(Scenario.Input input) {
        return switch (input.domain()) {
            case BOOLEAN -> "true";
            case STRING -> cppString(input.name());
            default -> "0";
        };
    }

    /**
     * @return the cartesian product of every input's boundary values, capped, or an empty list when any
     *     input declares none
     *
     * <p>A single combination per input is the common case; the product is what lets two boundary values
     *     be tried against each other without the scenario enumerating the pairs itself. The cap keeps a
     *     scenario with many inputs from generating an exponential number of programs to compile.
     */
    static List<Map<String, String>> boundaryCases(List<Scenario.Input> inputs) {
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
                }
            }
            accumulation = next;
        }
        return List.copyOf(accumulation.subList(0, Math.min(MAX_BOUNDARY_CASES, accumulation.size())));
    }

    // ------------------------------------------------------------------ text helpers

    /** @return the fragment with every {@code ${name}} replaced by the input's own identifier. */
    static String substitute(String fragment, List<Scenario.Input> inputs) {
        if (fragment == null) {
            return "";
        }
        String result = fragment;
        Set<String> names = new LinkedHashSet<>();
        inputs.forEach(input -> names.add(input.name()));
        for (String name : names) {
            result = result.replace("${" + name + "}", name);
        }
        return result;
    }

    /**
     * @return the fragment as a statement: a call the scenario wrote without a semicolon gets one, and one
     *     that already ends a statement - with a semicolon or a closing brace - is left alone, so a step that
     *     is several statements reads the way its author wrote it
     */
    static String ensureStatement(String fragment) {
        String text = fragment == null ? "" : fragment.strip();
        if (text.isEmpty() || text.endsWith(";") || text.endsWith("}")) {
            return text;
        }
        return text + ";";
    }

    /** @return the fragment without a trailing semicolon, so it can be embedded in an expression. */
    static String ensureExpression(String fragment) {
        String text = fragment == null ? "" : fragment.strip();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text;
    }

    /** @return a C++ string literal for arbitrary text, escapes included. */
    static String cppString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : (value == null ? "" : value).toCharArray()) {
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

    /** @return a fragment re-indented so that every line of it starts at the given prefix. */
    static String indent(String fragment, String pad) {
        StringBuilder sb = new StringBuilder();
        String[] lines = (fragment == null ? "" : fragment).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i].strip();
            sb.append(line.isEmpty() ? pad.stripTrailing() : pad + line);
        }
        return sb.toString();
    }

    /** @return the text on one line, with every run of whitespace collapsed to a single space. */
    static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    /**
     * The helpers every generated program carries.
     *
     * <p>They are emitted into each file rather than shared between the generated programs, so that two
     * scenarios compiled at once cannot collide and a counterexample can be read from a file that is
     * complete on its own.
     */
    private static final String HELPERS = """
            namespace {

            /// Replaces the two characters the protocol uses as separators, so a value cannot forge one.
            std::string cwSanitize(std::string value) {
                for (char& c : value) {
                    if (c == '|' || c == '\\n' || c == '\\r') {
                        c = ' ';
                    }
                }
                return value;
            }

            std::string cwFormat(long long value) {
                return std::to_string(value);
            }

            std::string cwFormat(double value) {
                char buffer[64];
                std::snprintf(buffer, sizeof(buffer), "%.6f", value);
                return std::string(buffer);
            }

            std::string cwFormat(bool value) {
                return value ? "true" : "false";
            }

            std::string cwFormat(const std::string& value) {
                return value;
            }

            /// Reports the counterexample and ends the run, which is how a failing case reaches the report.
            void cwFail(long long caseIndex, bool hasSeed, long long seed, const std::string& inputs,
                        const std::string& expected, const std::string& actual, const char* step) {
                std::string text = "CODEWITNESS_COUNTEREXAMPLE";
                text += "|case=" + std::to_string(caseIndex);
                text += "|seed=";
                if (hasSeed) {
                    text += std::to_string(seed);
                }
                text += "|inputs=" + cwSanitize(inputs);
                text += "|expected=" + cwSanitize(expected);
                text += "|actual=" + cwSanitize(actual);
                text += "|invariant=" + cwSanitize(CwInvariant);
                text += "|step=" + cwSanitize(step);
                text += "|requirement=" + cwSanitize(CwRequirement);
                text += "|obligation=" + cwSanitize(CwObligation);
                std::printf("%s\\n", text.c_str());
                std::fflush(stdout);
                std::exit(1);
            }

            }  // namespace

            """;
}

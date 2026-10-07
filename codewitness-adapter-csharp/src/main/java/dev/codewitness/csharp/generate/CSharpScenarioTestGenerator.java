package dev.codewitness.csharp.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.VerificationEngine;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.csharp.CSharpProjectDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns declared scenarios into executable xUnit tests.
 *
 * <p>A scenario supplies the oracle - the setup, the calls and the invariant, written in C# in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the
 * seeded random search, concurrent execution and the structured failure report. CodeWitness never
 * invents an oracle, because an invented oracle is exactly the kind of assertion this tool exists to
 * avoid.
 *
 * <p>A generated test must live in a test project, because that is the only place the .NET SDK will
 * compile and run it. A project with no test project therefore gets no generated test at all: the
 * requirement stays {@code UNVERIFIED}, which is the truthful answer, rather than {@code BLOCKED}, which
 * would suggest something was attempted and failed.
 *
 * <p>The file is placed in the test project's own directory, in the namespace that project's tests
 * already use, and it imports every namespace the project declares. That is what lets a scenario write
 * {@code new WithdrawalLedger()} without spelling out where the type lives. Two production namespaces that
 * declare the same type name would make an unqualified mention ambiguous and the file would not compile;
 * the generated test then fails to build, which the run reports as such rather than as a code defect.
 *
 * <p>Each generated class carries its own private helpers rather than sharing them with its siblings, so
 * two scenarios generated into one project cannot collide. Every failing case reports through a single
 * {@code CODEWITNESS_COUNTEREXAMPLE|...} line, carrying the exact inputs, the seed and the case index;
 * it is thrown as an {@code XunitException}, which is how the message reaches the TRX report the engine
 * reads it from.
 */
public final class CSharpScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** Number of workers a CONCURRENT scenario runs its steps from. */
    private static final int CONCURRENT_WORKERS = 8;

    /** C# types that can be named in a declaration exactly as the scenario declared them. */
    private static final Set<String> KEYWORDS = Set.of(
            "long", "int", "short", "byte", "sbyte", "uint", "ulong", "ushort",
            "double", "float", "decimal", "bool", "string", "object", "char");

    @Override
    public String id() {
        return "csharp-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CSHARP);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        Path directory = testDirectory(context, requirement.id());
        if (directory == null) {
            // Nothing to compile or run the generated test in; see the class javadoc.
            return List.of();
        }
        String namespace = namespaceFor(directory);
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
            String simpleName = classNameFor(scenario, obligation);
            String className = namespace.isEmpty() ? simpleName : namespace + "." + simpleName;
            String source = render(context, requirement, obligation, scenario, namespace, simpleName);
            generated.add(new GeneratedTest(className, source, className,
                    List.of(requirement.id()), List.of(obligation.id()),
                    scenario.description(), scenario.seed(), null,
                    scenario.observation() != null && scenario.invariant() != null));
        }
        return List.copyOf(generated);
    }

    /**
     * @return the directory a generated test should be written into: the test project whose sources
     *     mention the requirement when one does, otherwise the first test project, or {@code null} when the
     *     project has no test project at all.
     */
    public static Path testDirectory(VerificationContext context, String requirementId) {
        List<Path> testProjects = CSharpProjectDetector.testProjects(context.projectRoot());
        if (testProjects.isEmpty()) {
            return null;
        }
        if (requirementId != null && !requirementId.isBlank()) {
            String upper = requirementId.toUpperCase(Locale.ROOT);
            for (Path project : testProjects) {
                Path directory = project.getParent();
                if (directory == null) {
                    continue;
                }
                for (Path file : CSharpProjectDetector.sourceFiles(directory)) {
                    if (read(file).toUpperCase(Locale.ROOT).contains(upper)) {
                        return directory;
                    }
                }
            }
        }
        return testProjects.getFirst().getParent();
    }

    /**
     * @return the namespace generated tests are declared in: the first namespace the test project's own
     *     sources declare, so the generated class reads as part of that project, or a CodeWitness one when
     *     the project's tests sit in the global namespace.
     */
    public static String namespaceFor(Path testDirectory) {
        for (Path file : CSharpProjectDetector.sourceFiles(testDirectory)) {
            List<String> namespaces = CSharpProjectDetector.namespacesOf(read(file));
            if (!namespaces.isEmpty()) {
                return namespaces.getFirst();
            }
        }
        return "CodeWitness.Generated";
    }

    /**
     * @return the fully qualified name of the generated class for a scenario obligation.
     *
     * <p>The name is a C# identifier and, more importantly, a stable one: it is derived from the scenario
     * and obligation ids only, so a re-run of the same scenario produces the same class and a reported
     * failure can be paired with the obligation that declared it.
     */
    static String classNameFor(Scenario scenario, VerificationObligation obligation) {
        String suffix = scenario.mode() == Scenario.Mode.CONCURRENT ? "Concurrent" : "";
        return "Cw" + camel(scenario.id()) + camel(obligation.id()) + suffix;
    }

    /**
     * @return a C# PascalCase identifier for an arbitrary id such as {@code SC-ROLLING}.
     *
     * <p>Each separator starts a new word and the rest of a word is lower-cased, so {@code SC-ROLLING}
     * becomes {@code ScRolling} rather than a run of capitals.
     */
    static String camel(String value) {
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

    // ------------------------------------------------------------------ rendering

    private String render(
            VerificationContext context,
            Requirement requirement,
            VerificationObligation obligation,
            Scenario scenario,
            String namespace,
            String className) {

        List<Scenario.Input> inputs = collectInputs(scenario);
        List<Map<String, String>> boundaryCases = boundaryCases(inputs);

        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by CodeWitness ").append(VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("// Requirement: ").append(requirement.id()).append(" - ")
                .append(oneLine(requirement.title())).append('\n');
        sb.append("// Obligation: ").append(obligation.id()).append(" - ")
                .append(oneLine(obligation.description())).append('\n');
        sb.append("// Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases()).append(")\n");
        sb.append("// This file is temporary: CodeWitness keeps a copy with the run so a counterexample can be "
                + "replayed.\n\n");
        for (String using : usings(context, namespace)) {
            sb.append("using ").append(using).append(";\n");
        }
        sb.append('\n');
        if (!namespace.isEmpty()) {
            sb.append("namespace ").append(namespace).append(";\n\n");
        }
        sb.append("public sealed class ").append(className).append("\n{\n");
        sb.append("    private const string CwRequirement = ").append(csharpString(requirement.id())).append(";\n");
        sb.append("    private const string CwObligation = ").append(csharpString(obligation.id())).append(";\n");
        sb.append("    private const string CwInvariant = ")
                .append(csharpString(oneLine(scenario.invariant() == null
                        ? "(no invariant declared)" : scenario.invariant()))).append(";\n\n");

        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            sb.append(boundaryCase(scenario, className, index, inputs, values));
            index++;
        }
        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input has a declared type but none declares a boundary value, so there is nothing to
            // enumerate. Run the declared defaults once instead of emitting a class with no test methods:
            // a class with no test asserts nothing, and a scenario that reports nothing must not be
            // mistaken for one that ran and passed.
            sb.append(boundaryCase(scenario, className, 0, inputs, defaultValues(inputs)));
        }
        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            sb.append(singleCase(scenario, className));
        }
        boolean random = scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1;
        if (random && !inputs.isEmpty()) {
            sb.append(scenario.mode() == Scenario.Mode.CONCURRENT
                    ? concurrentSearch(scenario, className, inputs)
                    : randomSearch(scenario, className, inputs));
        }
        sb.append(HELPERS);
        sb.append("}\n");
        return sb.toString();
    }

    /** @return the {@code using} directives the generated file needs, in a stable order. */
    private static List<String> usings(VerificationContext context, String generatedNamespace) {
        Set<String> usings = new LinkedHashSet<>();
        usings.add("System");
        usings.add("System.Collections.Generic");
        usings.add("System.Globalization");
        usings.add("System.Linq");
        usings.add("System.Threading.Tasks");
        usings.add("Xunit");
        // Every namespace the project declares, so a scenario may name the project's own types without
        // spelling out where they live. See the class javadoc for the ambiguity this can produce.
        usings.addAll(CSharpProjectDetector.namespaces(context.projectRoot()));
        usings.remove(generatedNamespace);
        return List.copyOf(usings);
    }

    private String boundaryCase(
            Scenario scenario, String className, int index,
            List<Scenario.Input> inputs, Map<String, String> values) {

        StringBuilder sb = new StringBuilder();
        sb.append("    [Fact]\n");
        sb.append("    public void Case").append(String.format(Locale.ROOT, "%04d", index)).append("()\n    {\n");
        sb.append("        var caseIndex = ").append(index).append("L;\n");
        sb.append("        var hasSeed = false;\n");
        sb.append("        var seedValue = 0UL;\n");
        for (Scenario.Input input : inputs) {
            sb.append("        ").append(declaration(input, literal(input, values.get(input.name())))).append('\n');
        }
        appendBlankAssignments(sb, inputs);
        sb.append("        ").append(inputsArray(inputs)).append('\n');
        sb.append("        CwRun(caseIndex, hasSeed, seedValue, cwInputs, () =>\n        {\n");
        sb.append(sequenceBody(scenario, inputs, 12));
        sb.append("        });\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private String singleCase(Scenario scenario, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("    [Fact]\n");
        sb.append("    public void Single()\n    {\n");
        sb.append("        var caseIndex = 0L;\n");
        sb.append("        var hasSeed = false;\n");
        sb.append("        var seedValue = 0UL;\n");
        sb.append("        CwInput[] cwInputs = new CwInput[0];\n");
        sb.append("        CwRun(caseIndex, hasSeed, seedValue, cwInputs, () =>\n        {\n");
        sb.append(sequenceBody(scenario, List.of(), 12));
        sb.append("        });\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private String randomSearch(Scenario scenario, String className, List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("    [Fact]\n");
        sb.append("    public void RandomSearch()\n    {\n");
        sb.append("        var seedValue = CwSeed(").append(scenario.seed()).append("UL);\n");
        sb.append("        var hasSeed = true;\n");
        sb.append("        var caseCount = CwCases(").append(scenario.cases()).append("L);\n");
        sb.append("        var pinned = CwPinnedCase();\n");
        sb.append("        cwFailed = false;\n");
        sb.append("        for (var caseIndex = 0L; caseIndex < caseCount; caseIndex++)\n        {\n");
        sb.append("            if (pinned >= 0 && caseIndex != pinned)\n            {\n");
        sb.append("                continue;\n            }\n");
        sb.append("            var rng = new CwRng(seedValue + (ulong)caseIndex);\n");
        for (Scenario.Input input : inputs) {
            sb.append("            ").append(declaration(input, drawExpression(input))).append('\n');
        }
        appendBlankAssignments(sb, inputs, "            ");
        sb.append("            ").append(inputsArray(inputs)).append('\n');
        sb.append("            CwRun(caseIndex, hasSeed, seedValue, cwInputs, () =>\n            {\n");
        sb.append(sequenceBody(scenario, inputs, 16));
        sb.append("            });\n");
        sb.append("            if (cwFailed)\n            {\n");
        sb.append("                break;\n            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    private String concurrentSearch(Scenario scenario, String className, List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("    [Fact]\n");
        sb.append("    public void ConcurrentSearch()\n    {\n");
        sb.append("        var seedValue = CwSeed(").append(scenario.seed()).append("UL);\n");
        sb.append("        var hasSeed = true;\n");
        sb.append("        var caseCount = CwCases(").append(scenario.cases()).append("L);\n");
        sb.append("        var pinned = CwPinnedCase();\n");
        sb.append("        cwFailed = false;\n");
        sb.append("        for (var caseIndex = 0L; caseIndex < caseCount; caseIndex++)\n        {\n");
        sb.append("            if (pinned >= 0 && caseIndex != pinned)\n            {\n");
        sb.append("                continue;\n            }\n");
        sb.append("            var rng = new CwRng(seedValue + (ulong)caseIndex);\n");
        for (Scenario.Input input : inputs) {
            sb.append("            ").append(declaration(input, drawExpression(input))).append('\n');
        }
        appendBlankAssignments(sb, inputs, "            ");
        sb.append("            ").append(inputsArray(inputs)).append('\n');
        sb.append("            CwRun(caseIndex, hasSeed, seedValue, cwInputs, () =>\n            {\n");
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), 16)).append('\n');
        }
        sb.append("                Parallel.For(0, ").append(CONCURRENT_WORKERS).append(", _ =>\n                {\n");
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(ensureStatement(substitute(step.call(), inputs)), 20)).append('\n');
        }
        sb.append("                });\n");
        sb.append(invariantCheck(scenario, inputs, 16, "concurrent execution"));
        sb.append("            });\n");
        sb.append("            if (cwFailed)\n            {\n");
        sb.append("                break;\n            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        return sb.toString();
    }

    /** The setup, steps and invariant checks for one case, indented inside the closure. */
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

    /**
     * Renders the observation and invariant check that follows a step, or nothing when none is declared.
     *
     * <p>Each check is wrapped in its own block so that a scenario with several steps can declare
     * {@code observation} once per step: C# rejects a second declaration of the same name in one scope, and
     * the invariant refers to the observation by that fixed name. A violated case reports through
     * {@code CwFail} - which records that the case failed - and then returns, so it stops at the step that
     * broke it while the other cases still run.
     */
    private String invariantCheck(Scenario scenario, List<Scenario.Input> inputs, int indent, String stepDescription) {
        if (scenario.observation() == null || scenario.invariant() == null) {
            return "";
        }
        String pad = " ".repeat(indent);
        String inner = " ".repeat(indent + 4);
        StringBuilder sb = new StringBuilder();
        sb.append(pad).append("{\n");
        sb.append(inner).append("var observation = ")
                .append(ensureExpression(substitute(scenario.observation(), inputs))).append(";\n");
        sb.append(inner).append("if (!(").append(ensureExpression(substitute(scenario.invariant(), inputs)))
                .append("))\n");
        sb.append(inner).append("{\n");
        sb.append(inner).append("    CwFail(caseIndex, hasSeed, seedValue, cwInputs, ")
                .append(csharpString(oneLine(scenario.invariant())))
                .append(", CwFormat(observation), ")
                .append(csharpString(stepDescription)).append(");\n");
        sb.append(inner).append("    return;\n");
        sb.append(inner).append("}\n");
        sb.append(pad).append("}\n");
        return sb.toString();
    }

    private static String inputsArray(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder("CwInput[] cwInputs = new CwInput[] {");
        for (int i = 0; i < inputs.size(); i++) {
            Scenario.Input input = inputs.get(i);
            sb.append(i == 0 ? "" : ", ").append("new CwInput(").append(csharpString(input.name()))
                    .append(", CwFormat(").append(input.name()).append("))");
        }
        return sb.append("};").toString();
    }

    private static void appendBlankAssignments(StringBuilder sb, List<Scenario.Input> inputs) {
        appendBlankAssignments(sb, inputs, "        ");
    }

    private static void appendBlankAssignments(StringBuilder sb, List<Scenario.Input> inputs, String indent) {
        for (Scenario.Input input : inputs) {
            // A fragment is free not to mention an input; discarding it here keeps a project that treats
            // warnings as errors from failing on a generated file.
            sb.append(indent).append("_ = ").append(input.name()).append(";\n");
        }
    }

    // ------------------------------------------------------------------ values

    private static List<Scenario.Input> collectInputs(Scenario scenario) {
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
        String type = csharpType(input);
        if (type != null) {
            return type + " " + input.name() + " = " + expression + ";";
        }
        return "var " + input.name() + " = " + expression + ";";
    }

    /** @return the declared C# type when it can be trusted for a declaration, otherwise {@code null}. */
    static String csharpType(Scenario.Input input) {
        String declared = input.type();
        if (declared != null && KEYWORDS.contains(declared)) {
            return declared;
        }
        return switch (input.domain()) {
            case DECIMAL -> "decimal";
            case DOUBLE -> "double";
            case BOOLEAN -> "bool";
            case STRING -> "string";
            case NULL -> null;
            case INTEGER, LONG, INSTANT_MILLIS -> "long";
            case RAW -> null;
        };
    }

    private static String literal(Scenario.Input input, String rawValue) {
        String value = rawValue;
        if (value == null) {
            value = input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst();
        }
        return switch (input.domain()) {
            case DECIMAL -> "CwDecimalLiteral(" + csharpString(value) + ")";
            case INTEGER, LONG, INSTANT_MILLIS -> longLiteral(value);
            case DOUBLE -> doubleLiteral(value);
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(value.strip()));
            case STRING -> csharpString(value);
            case NULL -> "null";
            case RAW -> value.isBlank() ? "null" : value;
        };
    }

    private static String defaultLiteral(Scenario.Input input) {
        return switch (input.domain()) {
            case DECIMAL, DOUBLE, INTEGER, LONG, INSTANT_MILLIS -> "0";
            case BOOLEAN -> "false";
            case STRING -> "";
            case NULL, RAW -> "null";
        };
    }

    private static String drawExpression(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (input.domain()) {
            case DECIMAL -> "rng.Decimal(" + (min == null ? "0" : min) + ", " + (max == null ? "10000" : max)
                    + ", " + Math.max(0, input.scale()) + ")";
            case DOUBLE -> "rng.Double(" + (min == null ? "0" : min) + ", " + (max == null ? "1000" : max) + ")";
            case INTEGER, LONG, INSTANT_MILLIS -> "rng.Long(" + (min == null ? "0" : min) + ", "
                    + (max == null ? "10000" : max) + ")";
            case BOOLEAN -> "rng.Boolean()";
            case STRING -> "rng.Text()";
            case NULL -> "null";
            case RAW -> input.values().isEmpty() ? "null" : input.values().getFirst();
        };
    }

    private static String longLiteral(String value) {
        String text = value == null ? "0" : value.strip();
        return text.isEmpty() ? "0L" : text + "L";
    }

    private static String doubleLiteral(String value) {
        String text = value == null ? "0" : value.strip();
        return text.isEmpty() ? "0d" : text + "d";
    }

    private static String substitute(String fragment, List<Scenario.Input> inputs) {
        String result = fragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("${" + input.name() + "}", input.name());
        }
        return result;
    }

    /** @return a statement: a fragment that is an expression gets the terminator it needs. */
    private static String ensureStatement(String fragment) {
        String text = fragment.stripTrailing();
        if (text.endsWith(";") || text.endsWith("}")) {
            return text;
        }
        return text + ";";
    }

    /** @return an expression: a fragment is used as authored, with any trailing terminator removed. */
    private static String ensureExpression(String fragment) {
        String text = fragment.strip();
        return text.endsWith(";") ? text.substring(0, text.length() - 1).stripTrailing() : text;
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

    /** Renders a C# string literal, including the surrounding quotes. */
    static String csharpString(String value) {
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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }

    /**
     * The helpers every generated class carries, all private to it so that two scenarios generated into
     * one project cannot collide.
     *
     * <p>The random generator is written out here rather than taken from {@code System.Random} because a
     * counterexample has to be replayable on another machine: the same seed must draw the same values
     * from the same runtime and from any other.
     *
     * <p>{@code cwFailed} is set by {@code CwFail} rather than read from xUnit's state, so the search loop
     * can stop at the first failing case without depending on an API that differs between frameworks.
     */
    static final String HELPERS = """
                /// <summary>Set when a case reported a violation, so the search stops at the first one.</summary>
                private static bool cwFailed;

                private readonly record struct CwInput(string Name, string Value);

                /// <summary>An xorshift generator: deterministic across runtimes, unlike System.Random.</summary>
                private struct CwRng
                {
                    private ulong _state;

                    public CwRng(ulong seed)
                    {
                        _state = (seed * 6364136223846793005UL + 1442695040888963407UL) | 1UL;
                    }

                    private ulong Next()
                    {
                        var x = _state;
                        x ^= x << 13;
                        x ^= x >> 7;
                        x ^= x << 17;
                        _state = x;
                        return x;
                    }

                    public long Long(long minimum, long maximum)
                    {
                        if (maximum <= minimum)
                        {
                            return minimum;
                        }
                        var span = (ulong)(maximum - minimum + 1);
                        return minimum + (long)(Next() % span);
                    }

                    public double Double(double minimum, double maximum)
                    {
                        if (maximum <= minimum)
                        {
                            return minimum;
                        }
                        return minimum + ((double)Next() / ulong.MaxValue) * (maximum - minimum);
                    }

                    public bool Boolean() => (Next() & 1UL) == 1UL;

                    public string Text() => "cw-" + (Next() % 10000UL).ToString(CultureInfo.InvariantCulture);

                    public decimal Decimal(double minimum, double maximum, int scale)
                    {
                        var value = (decimal)Double(minimum, maximum);
                        var factor = (decimal)System.Math.Pow(10, scale);
                        return System.Math.Round(value * factor) / factor;
                    }
                }

                private static string CwFormat(object? value) =>
                    Convert.ToString(value, CultureInfo.InvariantCulture) ?? "";

                private static decimal CwDecimalLiteral(string text) =>
                    decimal.Parse(text, NumberStyles.Number, CultureInfo.InvariantCulture);

                private static ulong CwSeed(ulong fallback)
                {
                    var value = Environment.GetEnvironmentVariable("CODEWITNESS_SEED");
                    return !string.IsNullOrWhiteSpace(value) && ulong.TryParse(value, out var parsed) ? parsed : fallback;
                }

                private static long CwCases(long fallback)
                {
                    var value = Environment.GetEnvironmentVariable("CODEWITNESS_CASES");
                    return !string.IsNullOrWhiteSpace(value) && long.TryParse(value, out var parsed) ? parsed : fallback;
                }

                private static long CwPinnedCase()
                {
                    var value = Environment.GetEnvironmentVariable("CODEWITNESS_CASE");
                    return !string.IsNullOrWhiteSpace(value) && long.TryParse(value, out var parsed) ? parsed : -1L;
                }

                private static string CwSanitize(string? value) =>
                    (value ?? "").Replace("|", "/").Replace("\\n", " ").Replace("\\r", " ");

                private static void CwFail(
                    long caseIndex, bool hasSeed, ulong seedValue, CwInput[] inputs,
                    string expected, string actual, string step)
                {
                    cwFailed = true;
                    var text = new System.Text.StringBuilder();
                    text.Append("CODEWITNESS_COUNTEREXAMPLE");
                    text.Append("|case=").Append(caseIndex.ToString(CultureInfo.InvariantCulture));
                    text.Append("|seed=");
                    if (hasSeed)
                    {
                        text.Append(seedValue.ToString(CultureInfo.InvariantCulture));
                    }
                    text.Append("|inputs={");
                    for (var i = 0; i < inputs.Length; i++)
                    {
                        if (i > 0)
                        {
                            text.Append(", ");
                        }
                        text.Append(inputs[i].Name).Append('=').Append(inputs[i].Value);
                    }
                    text.Append("}");
                    text.Append("|expected=").Append(CwSanitize(expected));
                    text.Append("|actual=").Append(CwSanitize(actual));
                    text.Append("|invariant=").Append(CwSanitize(CwInvariant));
                    text.Append("|step=").Append(CwSanitize(step));
                    text.Append("|requirement=").Append(CwRequirement);
                    text.Append("|obligation=").Append(CwObligation);
                    throw new Xunit.Sdk.XunitException(text.ToString());
                }

                /// <summary>
                /// Runs one case. An exception the scenario did not raise itself is reported as a failed case
                /// with the runtime's own message, and a counterexample already reported is passed through
                /// unchanged rather than being wrapped a second time.
                /// </summary>
                private static void CwRun(
                    long caseIndex, bool hasSeed, ulong seedValue, CwInput[] inputs, Action body)
                {
                    try
                    {
                        body();
                    }
                    catch (Xunit.Sdk.XunitException)
                    {
                        throw;
                    }
                    catch (Exception exception)
                    {
                        CwFail(caseIndex, hasSeed, seedValue, inputs,
                            "the declared steps must not throw", exception.Message, "step execution");
                    }
                }
            """;
}

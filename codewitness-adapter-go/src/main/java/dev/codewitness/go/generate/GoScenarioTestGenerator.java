package dev.codewitness.go.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.VerificationEngine;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.go.GoProjectDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns declared scenarios into executable Go tests.
 *
 * <p>A scenario supplies the oracle — the setup, the calls and the invariant, written in Go in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the
 * seeded random search, concurrent execution and the structured failure report. CodeWitness never
 * invents an oracle, because an invented oracle is exactly the kind of assertion this tool exists to
 * avoid.
 *
 * <p>The generated file is an ordinary {@code *_test.go} file placed in the package under test, so
 * {@code go test} compiles and runs it with the project's own toolchain and modules. Keeping the test
 * in the same package matters: it means the scenario fragments need no import and may call exported
 * and unexported identifiers alike, and it avoids the "imported and not used" trap that a separate
 * package would create for a scenario that mentions no package at all. Every helper carries the
 * generated test's own suffix, so two scenarios generated into the same package cannot collide.
 *
 * <p>Go's compiler rejects unused local variables, and the search itself knows nothing about which
 * inputs a given case exercises. Every generated input is therefore explicitly blanked with
 * {@code _ = name} after it is declared, and the observation is blanked the same way; a fragment that
 * does not mention an input still compiles.
 *
 * <p>Each failing case reports through a single {@code CODEWITNESS_COUNTEREXAMPLE|...} line via
 * {@code t.Errorf}, carrying the exact inputs, seed and case index. Unlike a panic it does not abort
 * the run, so every boundary case is reported and not just the first one.
 */
public final class GoScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** Default number of goroutines used by CONCURRENT scenarios. */
    private static final int CONCURRENT_GOROUTINES = 8;

    /** Go types that can be named in a declaration exactly as the scenario declared them. */
    private static final Set<String> PRIMITIVES = Set.of(
            "bool", "string", "byte", "rune",
            "int", "int8", "int16", "int32", "int64",
            "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
            "float32", "float64", "complex64", "complex128");

    /** Integer types narrower or different from the generator's native {@code int64}. */
    private static final Set<String> INTEGER_TYPES = Set.of(
            "int", "int8", "int16", "int32",
            "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "byte", "rune");

    @Override
    public String id() {
        return "go-scenario-generator";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
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
            String source = render(context, requirement, obligation, scenario, className);
            generated.add(new GeneratedTest(className, source, className,
                    List.of(requirement.id()), List.of(obligation.id()),
                    scenario.description(), scenario.seed(), null,
                    scenario.observation() != null && scenario.invariant() != null));
        }
        return generated;
    }

    /**
     * @return the package directory a generated test should live in: the directory that mentions the
     *     requirement when one does, otherwise the module root, falling back to the project root.
     */
    static Path packageDir(VerificationContext context, String requirementId) {
        List<Path> files = new ArrayList<>();
        for (Path root : context.projectInfo().sourceDirs()) {
            files.addAll(GoProjectDetector.goFiles(root));
        }
        if (requirementId != null && !requirementId.isBlank()) {
            String upper = requirementId.toUpperCase(Locale.ROOT);
            for (Path file : files) {
                if (read(file).toUpperCase(Locale.ROOT).contains(upper)) {
                    return file.getParent();
                }
            }
        }
        for (Path file : files) {
            if (file.getParent() != null && file.getParent().equals(context.projectRoot())) {
                return context.projectRoot();
            }
        }
        return files.isEmpty() || files.getFirst().getParent() == null
                ? context.projectRoot() : files.getFirst().getParent();
    }

    // ------------------------------------------------------------------ naming

    private static String classNameFor(Scenario scenario, VerificationObligation obligation) {
        String suffix = scenario.mode() == Scenario.Mode.CONCURRENT ? "Concurrent" : "";
        return "TestCw" + camel(scenario.id()) + camel(obligation.id()) + suffix;
    }

    /**
     * @return a Go-exported CamelCase identifier for an arbitrary id such as {@code SC-ROLLING}.
     *
     * <p>Each separator starts a new word; the rest of a word is lower-cased so {@code SC-ROLLING}
     * becomes {@code ScRolling} rather than a run of capitals. That keeps generated test names
     * readable and, more importantly, makes the name a valid {@code TestXxx} function name.
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
            String className) {

        String suffix = className;
        String packageName = GoProjectDetector.packageName(packageDir(context, requirement.id()));
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
        sb.append("// This file is temporary: CodeWitness keeps it with the run so a counterexample can be replayed.\n");
        sb.append("// It lives in the package under test, so it uses only the standard library and needs no import.\n\n");
        sb.append("package ").append(packageName).append("\n\n");
        sb.append(IMPORTS);
        sb.append("const cwRequirement").append(suffix).append(" = ")
                .append(goString(requirement.id())).append('\n');
        sb.append("const cwObligation").append(suffix).append(" = ")
                .append(goString(obligation.id())).append('\n');
        sb.append("const cwInvariant").append(suffix).append(" = ")
                .append(goString(oneLine(scenario.invariant() == null ? "(no invariant declared)" : scenario.invariant())))
                .append("\n\n");

        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            sb.append(boundaryCase(scenario, className, suffix, index, inputs, values));
            index++;
        }
        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input has a declared type but none declares a boundary value, so there is nothing
            // to enumerate. Run the declared defaults once instead of emitting a file with no test
            // functions at all: an empty test file reports nothing, and a scenario that reports
            // nothing must not be mistaken for one that ran and passed.
            sb.append(boundaryCase(scenario, className, suffix, 0, inputs, defaultValues(inputs)));
        }
        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            sb.append(singleCase(scenario, className, suffix));
        }
        boolean random = scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1;
        if (random && !inputs.isEmpty()) {
            sb.append(scenario.mode() == Scenario.Mode.CONCURRENT
                    ? concurrentSearch(scenario, className, suffix, inputs)
                    : randomSearch(scenario, className, suffix, inputs));
        }
        sb.append(HELPERS.replace("$S", suffix));
        return sb.toString();
    }

    // ------------------------------------------------------------------ test functions

    private String boundaryCase(
            Scenario scenario, String className, String suffix, int index,
            List<Scenario.Input> inputs, Map<String, String> values) {

        StringBuilder sb = new StringBuilder();
        sb.append("func ").append(className).append(String.format(Locale.ROOT, "Case%04d", index))
                .append("(t *testing.T) {\n");
        sb.append("    caseIndex := int64(").append(index).append(")\n");
        sb.append("    hasSeed := false\n");
        sb.append("    var seedValue uint64 = 0\n");
        for (Scenario.Input input : inputs) {
            sb.append("    ").append(declaration(suffix, input, literal(suffix, input, values.get(input.name())))).append('\n');
        }
        appendBlankAssignments(sb, inputs);
        sb.append("    ").append(inputsSlice(suffix, inputs)).append('\n');
        sb.append("    cwRun").append(suffix).append("(t, caseIndex, hasSeed, seedValue, cwInputs, func() {\n");
        sb.append(sequenceBody(scenario, className, suffix, inputs, 8));
        sb.append("    })\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String singleCase(Scenario scenario, String className, String suffix) {
        StringBuilder sb = new StringBuilder();
        sb.append("func ").append(className).append("Single(t *testing.T) {\n");
        sb.append("    caseIndex := int64(0)\n");
        sb.append("    hasSeed := false\n");
        sb.append("    var seedValue uint64 = 0\n");
        sb.append("    cwInputs := []cwInput").append(suffix).append("{}\n");
        sb.append("    cwRun").append(suffix).append("(t, caseIndex, hasSeed, seedValue, cwInputs, func() {\n");
        sb.append(sequenceBody(scenario, className, suffix, List.of(), 8));
        sb.append("    })\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String randomSearch(
            Scenario scenario, String className, String suffix, List<Scenario.Input> inputs) {

        StringBuilder sb = new StringBuilder();
        sb.append("func ").append(className).append("Random(t *testing.T) {\n");
        sb.append("    seedValue := cwSeed").append(suffix).append("(").append(scenario.seed()).append(")\n");
        sb.append("    hasSeed := true\n");
        sb.append("    caseCount := cwCases").append(suffix).append("(").append(scenario.cases()).append(")\n");
        sb.append("    pinned := cwPinnedCase").append(suffix).append("()\n");
        sb.append("    for caseIndex := int64(0); caseIndex < caseCount; caseIndex++ {\n");
        sb.append("        if pinned >= 0 && caseIndex != pinned {\n");
        sb.append("            continue\n");
        sb.append("        }\n");
        sb.append("        rnd := cwNewRng").append(suffix).append("(seedValue + uint64(caseIndex))\n");
        for (Scenario.Input input : inputs) {
            sb.append("        ").append(declaration(suffix, input, drawExpression(suffix, input))).append('\n');
        }
        appendBlankAssignments(sb, inputs);
        sb.append("        ").append(inputsSlice(suffix, inputs)).append('\n');
        sb.append("        cwRun").append(suffix).append("(t, caseIndex, hasSeed, seedValue, cwInputs, func() {\n");
        sb.append(sequenceBody(scenario, className, suffix, inputs, 12));
        sb.append("        })\n");
        sb.append("        if t.Failed() {\n");
        sb.append("            break\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String concurrentSearch(
            Scenario scenario, String className, String suffix, List<Scenario.Input> inputs) {

        StringBuilder sb = new StringBuilder();
        sb.append("func ").append(className).append("Search(t *testing.T) {\n");
        sb.append("    seedValue := cwSeed").append(suffix).append("(").append(scenario.seed()).append(")\n");
        sb.append("    hasSeed := true\n");
        sb.append("    caseCount := cwCases").append(suffix).append("(").append(scenario.cases()).append(")\n");
        sb.append("    pinned := cwPinnedCase").append(suffix).append("()\n");
        sb.append("    for caseIndex := int64(0); caseIndex < caseCount; caseIndex++ {\n");
        sb.append("        if pinned >= 0 && caseIndex != pinned {\n");
        sb.append("            continue\n");
        sb.append("        }\n");
        sb.append("        rnd := cwNewRng").append(suffix).append("(seedValue + uint64(caseIndex))\n");
        for (Scenario.Input input : inputs) {
            sb.append("        ").append(declaration(suffix, input, drawExpression(suffix, input))).append('\n');
        }
        appendBlankAssignments(sb, inputs);
        sb.append("        ").append(inputsSlice(suffix, inputs)).append('\n');
        sb.append("        cwRun").append(suffix).append("(t, caseIndex, hasSeed, seedValue, cwInputs, func() {\n");
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), 12)).append('\n');
        }
        sb.append("            var cwWait sync.WaitGroup\n");
        sb.append("            cwStart := make(chan struct{})\n");
        sb.append("            for cwWorker := 0; cwWorker < ").append(CONCURRENT_GOROUTINES).append("; cwWorker++ {\n");
        sb.append("                cwWait.Add(1)\n");
        sb.append("                go func() {\n");
        sb.append("                    defer cwWait.Done()\n");
        sb.append("                    <-cwStart\n");
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(ensureStatement(substitute(step.call(), inputs)), 20)).append('\n');
        }
        sb.append("                }()\n");
        sb.append("            }\n");
        sb.append("            close(cwStart)\n");
        sb.append("            cwWait.Wait()\n");
        sb.append(invariantCheck(scenario, suffix, inputs, 12, "concurrent execution"));
        sb.append("        })\n");
        sb.append("        if t.Failed() {\n");
        sb.append("            break\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    /** The setup, steps and invariant checks for a sequential case, indented inside the closure. */
    private String sequenceBody(
            Scenario scenario, String className, String suffix, List<Scenario.Input> inputs, int indent) {
        StringBuilder sb = new StringBuilder();
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), indent)).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(ensureStatement(substitute(step.call(), inputs)), indent)).append('\n');
            sb.append(invariantCheck(scenario, suffix, inputs, indent, oneLine(step.description())));
        }
        return sb.toString();
    }

    /**
     * Renders the observation and invariant check that follows a step, or nothing when none is declared.
     *
     * <p>Each check is wrapped in its own block so a scenario with several steps can declare
     * {@code observation} once per step: Go rejects a second {@code :=} for the same name in one scope,
     * and the invariant refers to the observation by that fixed name. A violated case reports through
     * {@code cwFail} and then returns, so it stops at the step that broke it while the other cases
     * still run. The observation is always blanked because a declared invariant is free not to mention
     * it.
     */
    private String invariantCheck(
            Scenario scenario, String suffix, List<Scenario.Input> inputs, int indent, String stepDescription) {
        if (scenario.observation() == null || scenario.invariant() == null) {
            return "";
        }
        String pad = " ".repeat(indent);
        String inner = " ".repeat(indent + 4);
        StringBuilder sb = new StringBuilder();
        sb.append(pad).append("{\n");
        sb.append(inner).append("observation := ")
                .append(substitute(scenario.observation(), inputs)).append('\n');
        sb.append(inner).append("_ = observation\n");
        sb.append(inner).append("if !(").append(substitute(scenario.invariant(), inputs)).append(") {\n");
        sb.append(inner).append("    cwFail").append(suffix)
                .append("(t, caseIndex, hasSeed, seedValue, cwInputs, ")
                .append(goString(oneLine(scenario.invariant())))
                .append(", fmt.Sprintf(\"%v\", observation), ")
                .append(goString(stepDescription)).append(")\n");
        sb.append(inner).append("    return\n");
        sb.append(inner).append("}\n");
        sb.append(pad).append("}\n");
        return sb.toString();
    }

    private static String inputsSlice(String suffix, List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder("cwInputs := []cwInput").append(suffix).append("{");
        for (int i = 0; i < inputs.size(); i++) {
            Scenario.Input input = inputs.get(i);
            sb.append(i == 0 ? "" : ", ").append('{').append(goString(input.name()))
                    .append(", fmt.Sprintf(\"%v\", ").append(input.name()).append(")}");
        }
        return sb.append("}").toString();
    }

    private static void appendBlankAssignments(StringBuilder sb, List<Scenario.Input> inputs) {
        for (Scenario.Input input : inputs) {
            sb.append("    _ = ").append(input.name()).append('\n');
        }
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

    private static String declaration(String suffix, Scenario.Input input, String expression) {
        String type = goType(input);
        if (type != null) {
            return "var " + input.name() + " " + type + " = " + expression;
        }
        return input.name() + " := " + expression;
    }

    /** @return the declared Go type when it can be trusted for an annotation, otherwise {@code null}. */
    private static String goType(Scenario.Input input) {
        String declared = input.type();
        if (declared != null && PRIMITIVES.contains(declared)) {
            return declared;
        }
        return switch (input.domain()) {
            case DECIMAL, DOUBLE -> "float64";
            case INTEGER, LONG, INSTANT_MILLIS -> "int64";
            case BOOLEAN -> "bool";
            case STRING -> "string";
            case NULL -> "*int64";
            case RAW -> null;
        };
    }

    private static String literal(String suffix, Scenario.Input input, String rawValue) {
        String value = rawValue;
        if (value == null) {
            value = input.values().isEmpty() ? defaultLiteral(input) : input.values().getFirst();
        }
        return switch (input.domain()) {
            case DECIMAL -> "cwF64" + suffix + "(" + goString(value) + ")";
            case INTEGER, LONG, INSTANT_MILLIS -> value.isBlank() ? "0" : value;
            case DOUBLE -> value.isBlank() ? "0" : value;
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(value));
            case STRING -> goString(value);
            case NULL -> "nil";
            case RAW -> value.isBlank() ? "nil" : value;
        };
    }

    private static String defaultLiteral(Scenario.Input input) {
        return switch (input.domain()) {
            case DECIMAL, STRING -> "";
            case INTEGER, LONG, INSTANT_MILLIS, DOUBLE -> "0";
            case BOOLEAN -> "false";
            case NULL, RAW -> "nil";
        };
    }

    private static String drawExpression(String suffix, Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (input.domain()) {
            case DECIMAL -> coerce(input, "cwDecimal" + suffix + "(&rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "10000" : max) + ", " + Math.max(0, input.scale()) + ")");
            case INTEGER, LONG -> coerce(input, "cwInt" + suffix + "(&rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "10000" : max) + ")");
            case DOUBLE -> coerce(input, "cwDouble" + suffix + "(&rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "1000" : max) + ")");
            case BOOLEAN -> "cwBoolean" + suffix + "(&rnd)";
            case STRING -> "cwString" + suffix + "(&rnd)";
            case INSTANT_MILLIS -> coerce(input, "cwInt" + suffix + "(&rnd, " + (min == null ? "0" : min) + ", "
                    + (max == null ? "86400000" : max) + ")");
            case NULL -> "nil";
            case RAW -> input.values().isEmpty() ? "nil" : input.values().getFirst();
        };
    }

    /** Casts a generated {@code int64}/{@code float64} value to the input's declared Go type. */
    private static String coerce(Scenario.Input input, String expression) {
        String type = input.type();
        if (type == null) {
            return expression;
        }
        return switch (input.domain()) {
            case INTEGER, LONG, INSTANT_MILLIS ->
                    INTEGER_TYPES.contains(type) ? type + "(" + expression + ")" : expression;
            case DOUBLE, DECIMAL -> type.equals("float32") ? "float32(" + expression + ")" : expression;
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
        // Go does not require a statement terminator when the statement ends at a newline, and the
        // generator always places each fragment on its own line, so the text is used as authored.
        return fragment.stripTrailing();
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

    /** Renders a Go interpreted string literal, including the surrounding quotes. */
    static String goString(String value) {
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
     * The fixed standard-library imports every generated test carries.
     *
     * <p>Go rejects an unused import, and a scenario may or may not mention a package, so each import
     * is referenced once through a blank identifier. That makes the common standard packages available
     * to a scenario fragment without ever failing to compile for lack of a use.
     */
    static final String IMPORTS = """
            import (
            	"errors"
            	"fmt"
            	"math"
            	"os"
            	"regexp"
            	"sort"
            	"strconv"
            	"strings"
            	"sync"
            	"testing"
            	"time"
            )

            var (
            	_ = errors.New
            	_ = fmt.Sprintf
            	_ = math.Abs
            	_ = os.Getenv
            	_ = regexp.MustCompile
            	_ = sort.Ints
            	_ = strconv.Itoa
            	_ = strings.TrimSpace
            	_ = sync.WaitGroup{}
            	_ = time.Now
            )

            """;

    /** The runtime helpers every generated test uses. Standard library only, deterministic by seed. */
    static final String HELPERS = """

            type cwInput$S struct {
            	name  string
            	value string
            }

            type cwRng$S struct {
            	state uint64
            }

            func cwNewRng$S(seed uint64) cwRng$S {
            	return cwRng$S{state: seed*6364136223846793005 + 1442695040888963407 | 1}
            }

            func (r *cwRng$S) next() uint64 {
            	x := r.state
            	x ^= x << 13
            	x ^= x >> 7
            	x ^= x << 17
            	r.state = x
            	return x
            }

            func (r *cwRng$S) int(minimum, maximum int64) int64 {
            	if maximum <= minimum {
            		return minimum
            	}
            	span := uint64(maximum - minimum + 1)
            	return minimum + int64(r.next()%span)
            }

            func (r *cwRng$S) double(minimum, maximum float64) float64 {
            	if maximum <= minimum {
            		return minimum
            	}
            	return minimum + (float64(r.next())/float64(^uint64(0)))*(maximum-minimum)
            }

            func (r *cwRng$S) boolean() bool {
            	return r.next()&1 == 1
            }

            func (r *cwRng$S) str() string {
            	return fmt.Sprintf("cw-%d", r.next()%10000)
            }

            func (r *cwRng$S) decimal(minimum, maximum float64, scale int) float64 {
            	value := r.double(minimum, maximum)
            	factor := math.Pow(10, float64(scale))
            	return math.Round(value*factor) / factor
            }

            func cwInt$S(r *cwRng$S, minimum, maximum int64) int64 {
            	return r.int(minimum, maximum)
            }

            func cwDouble$S(r *cwRng$S, minimum, maximum float64) float64 {
            	return r.double(minimum, maximum)
            }

            func cwDecimal$S(r *cwRng$S, minimum, maximum float64, scale int) float64 {
            	return r.decimal(minimum, maximum, scale)
            }

            func cwBoolean$S(r *cwRng$S) bool {
            	return r.boolean()
            }

            func cwString$S(r *cwRng$S) string {
            	return r.str()
            }

            func cwF64$S(text string) float64 {
            	value, err := strconv.ParseFloat(text, 64)
            	if err != nil {
            		return 0
            	}
            	return value
            }

            func cwSeed$S(fallback uint64) uint64 {
            	if value := strings.TrimSpace(os.Getenv("CODEWITNESS_SEED")); value != "" {
            		if parsed, err := strconv.ParseUint(value, 10, 64); err == nil {
            			return parsed
            		}
            	}
            	return fallback
            }

            func cwCases$S(fallback int64) int64 {
            	if value := strings.TrimSpace(os.Getenv("CODEWITNESS_CASES")); value != "" {
            		if parsed, err := strconv.ParseInt(value, 10, 64); err == nil {
            			return parsed
            		}
            	}
            	return fallback
            }

            func cwPinnedCase$S() int64 {
            	if value := strings.TrimSpace(os.Getenv("CODEWITNESS_CASE")); value != "" {
            		if parsed, err := strconv.ParseInt(value, 10, 64); err == nil {
            			return parsed
            		}
            	}
            	return -1
            }

            func cwSanitize$S(value string) string {
            	value = strings.ReplaceAll(value, "|", "/")
            	return strings.ReplaceAll(value, "\\n", " ")
            }

            func cwFail$S(t *testing.T, caseIndex int64, hasSeed bool, seedValue uint64, inputs []cwInput$S, expected, actual, step string) {
            	var text strings.Builder
            	text.WriteString("CODEWITNESS_COUNTEREXAMPLE")
            	text.WriteString("|case=")
            	text.WriteString(strconv.FormatInt(caseIndex, 10))
            	text.WriteString("|seed=")
            	if hasSeed {
            		text.WriteString(strconv.FormatUint(seedValue, 10))
            	}
            	text.WriteString("|inputs={")
            	for i, input := range inputs {
            		if i > 0 {
            			text.WriteString(", ")
            		}
            		text.WriteString(input.name)
            		text.WriteString("=")
            		text.WriteString(input.value)
            	}
            	text.WriteString("}")
            	text.WriteString("|expected=")
            	text.WriteString(cwSanitize$S(expected))
            	text.WriteString("|actual=")
            	text.WriteString(cwSanitize$S(actual))
            	text.WriteString("|invariant=")
            	text.WriteString(cwSanitize$S(cwInvariant$S))
            	text.WriteString("|step=")
            	text.WriteString(cwSanitize$S(step))
            	text.WriteString("|requirement=")
            	text.WriteString(cwRequirement$S)
            	text.WriteString("|obligation=")
            	text.WriteString(cwObligation$S)
            	t.Errorf("%s", text.String())
            }

            func cwRun$S(t *testing.T, caseIndex int64, hasSeed bool, seedValue uint64, inputs []cwInput$S, body func()) {
            	defer func() {
            		if recovered := recover(); recovered != nil {
            			message := fmt.Sprintf("%v", recovered)
            			if strings.Contains(message, "CODEWITNESS_COUNTEREXAMPLE") {
            				panic(recovered)
            			}
            			cwFail$S(t, caseIndex, hasSeed, seedValue, inputs, "the declared steps must not panic", message, "step execution")
            		}
            	}()
            	body()
            }
            """;
}

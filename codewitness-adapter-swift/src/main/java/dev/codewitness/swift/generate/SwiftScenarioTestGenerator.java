package dev.codewitness.swift.generate;

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
import dev.codewitness.swift.SwiftToolchain;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns declared scenarios into executable Swift XCTest classes.
 *
 * <p>A scenario supplies the oracle - the setup, the calls and the invariant, written in Swift in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the seeded
 * random search and the structured failure report. CodeWitness never invents an oracle, because an
 * invented oracle is exactly the kind of assertion this tool exists to avoid.
 *
 * <p>The generated file is an ordinary XCTest class written into the project's own test tree, at
 * {@code <first test root>/Generated/CwSc<ScenarioId>Tests.swift}, and it stays there. It has to: SwiftPM
 * only compiles the Swift files that live inside a declared test target's directory, so a class written
 * anywhere else is a file no test target ever sees. The replay command in a counterexample is a real
 * {@code swift test --filter ...} invocation over that file, and deleting it would make that command a
 * lie; the run also keeps a copy in its artefact store. This class only produces sources: it never runs
 * SwiftPM, never observes a result and never assigns a status.
 *
 * <p>XCTest reports a case as {@code <test target module>.<class>}, so the string this generator records
 * as {@link GeneratedTest#className()} is {@code <module>.CwSc<ScenarioId>Tests} - the same string
 * {@link SwiftGeneratedTestRunner} checks a parsed report against before any result is handed to the
 * engine. {@link #classNameFor(VerificationContext, Scenario)} derives it from the test target the
 * project's own detector recorded (see {@link SwiftToolchain#ATTR_TEST_MODULE}) and never from the
 * obligation, so one scenario always produces one class however many obligations asked for it.
 *
 * <p>The case itself is emitted into one nested Swift function. A scenario's fragments name the inputs as
 * plain Swift locals ({@code ${name}} becomes the local {@code name}), and the setup, the steps, the
 * observation and the invariant therefore have to be written where those locals are in scope. A shared
 * helper function could only see instance state, which would change the DSL the scenario author writes,
 * so the body is inlined into every case the way the Ruby and Node generators inline theirs.
 *
 * <p>Every generated method name begins with {@code test}: the generator emits {@code testCwCase0000},
 * {@code testCwSearch} and {@code testCwSingleCase}, and records exactly that name as
 * {@link GeneratedTest#testMethod()}. The prefix is not decoration. XCTest discovers a test method by
 * that prefix and by nothing else - a class of {@code cw_case0000}-style methods compiles and then runs
 * zero cases, and {@code swift test --filter} selects only from the discovered set, so a replay filter
 * for such a method matches nothing. The same prefix is what makes the replay command work, because
 * {@code --filter}'s method part has to be a prefix of the discovered name. The emitted method and the
 * recorded name therefore have to be one string, which is why they come from these constants.
 *
 * <p>Stated limit: the declared steps of a scenario whose mode is {@code concurrent} are executed once,
 * in order, and the generated file says so. Swift has concurrency, but a scenario's fragments are
 * arbitrary user code this generator cannot assume is safe under it, and a step that failed inside a
 * detached task would have to be caught and re-reported by machinery the generated file does not own - a
 * failure that could otherwise be swallowed and counted as a pass. The case still executes the declared
 * steps and checks the declared invariant; it demonstrates nothing about concurrency.
 */
public final class SwiftScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Upper bound on generated boundary cases per scenario, mirroring the Ruby generator's bound. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** Subdirectory of the first test root the generated class is written into. */
    static final String GENERATED_DIRECTORY = "Generated";

    /** Prefix and suffix of a generated class: {@code CwSc<identifier>Tests}. */
    private static final String CLASS_PREFIX = "CwSc";

    private static final String CLASS_SUFFIX = "Tests";

    /**
     * The method a boundary or default case is emitted as; the first one is the recorded replay method.
     *
     * <p>Every generated method name begins with {@code test} because XCTest discovers a test method by
     * that prefix and by nothing else: a method called {@code cw_case0000} is compiled but never
     * discovered, so {@code swift test} runs zero cases and {@code --filter} selects none of them. The
     * same prefix is what makes a replay filter work, because {@code --filter}'s method part has to be a
     * prefix of the discovered name.
     */
    private static final String FIRST_CASE_METHOD = "testCwCase0000";

    /** The method a scenario with no inputs at all is emitted as. */
    private static final String SINGLE_CASE_METHOD = "testCwSingleCase";

    /** The method the seeded search is emitted as, and the one a counterexample replays. */
    private static final String RANDOM_SEARCH_METHOD = "testCwSearch";

    /** Creates the generator that emits Swift XCTest classes. */
    public SwiftScenarioTestGenerator() {
    }

    @Override
    public String id() {
        return "swift-scenarios";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SWIFT);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        // The generated class identifies the scenario, not the obligation, so two obligations that share
        // one scenario would produce one class carrying both; grouping first means neither is silently
        // lost. The executor writes one file per generated test, so two tests with one class name would be
        // written to one path and the second would overwrite the first.
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
            if (!renderable(scenario)) {
                // A scenario whose inputs this generator cannot spell as Swift values is skipped rather
                // than emitted as code that cannot compile: a class that fails to build would fail the
                // whole test target, including the project's own tests. The obligation stays UNVERIFIED.
                continue;
            }
            String className = classNameFor(context, scenario);
            if (testModule(context) == null || importModule(context) == null) {
                // Without a test module the result could not be attributed, and without a host module the
                // class could not import the code it exercises and would not compile; the engine's "no
                // scenario" path is the honest answer for this obligation.
                continue;
            }
            String source = render(context, requirement, obligationsForScenario, scenario);
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
     * @return the class name a generated test is reported under: {@code <testModule>.<SimpleClassName>},
     *     the exact string XCTest prints as the suite of a case and therefore the string the executor
     *     matches a parsed report against.
     *
     * <p>The module is the project's recorded test target, not the class's own file path: SwiftPM compiles
     * a test target as one module, and XCTest names every case after that module. A project whose test
     * directory the detector could not name has no module to report, so this falls back to the bare class
     * name - the case {@link #generate} refuses to emit at all.
     */
    public static String classNameFor(VerificationContext context, Scenario scenario) {
        String module = testModule(context);
        String simpleName = simpleClassName(scenario);
        return module == null ? simpleName : module + "." + simpleName;
    }

    /**
     * @return the module XCTest reports a generated class under: the recorded test target, or the first
     *     test root's directory name as a fallback, or {@code null} when the project names no test root.
     *
     * <p>{@link SwiftProjectDetector} records the test target's name when the manifest declares one and
     * the conventional test directory's name otherwise, so the fallback only matters for a context built
     * without the detector - a test double, or a tree whose manifest could not be read.
     */
    public static String testModule(VerificationContext context) {
        String declared = context.projectInfo().attributes().get(SwiftToolchain.ATTR_TEST_MODULE);
        if (declared != null && !declared.isBlank()) {
            return declared.strip();
        }
        List<Path> testRoots = context.projectInfo().testSourceDirs();
        if (!testRoots.isEmpty() && testRoots.getFirst().getFileName() != null) {
            String name = testRoots.getFirst().getFileName().toString();
            if (!name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    /**
     * @return the module a generated class must {@code @testable import}: the package's library (or
     *     executable) target, which is a different fact from {@link #testModule}.
     *
     * <p>XCTest reports a case under the <em>test</em> target's module - that is what the engine matches a
     * result against - while the class's own source has to import the code under test from the
     * <em>library</em> target. A class written into the test target that imported that same test target
     * would not compile, which is why the detector records the two names separately.
     */
    public static String importModule(VerificationContext context) {
        String declared = context.projectInfo().attributes().get(SwiftToolchain.ATTR_HOST_MODULE);
        if (declared != null && !declared.isBlank()) {
            return declared.strip();
        }
        List<Path> sourceRoots = context.projectInfo().sourceDirs();
        if (!sourceRoots.isEmpty() && sourceRoots.getFirst().getFileName() != null) {
            String name = sourceRoots.getFirst().getFileName().toString();
            if (!name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    /**
     * @return the generated class's simple name: {@code CwSc<identifier>Tests}, with every character that
     *     cannot appear in a Swift identifier replaced by an underscore.
     *
     * <p>An underscore rather than a deletion, because {@code SC-A_B} and {@code SC-AB} must not become the
     * same class: two scenarios in one run would then share one generated file and one requirement would be
     * judged by the other's experiment.
     */
    public static String simpleClassName(Scenario scenario) {
        return CLASS_PREFIX + identifier(scenario.id()) + CLASS_SUFFIX;
    }

    /** @return an ASCII identifier fragment, so the result names a compilable class whatever the id was. */
    static String identifier(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            sb.append(ascii ? c : '_');
        }
        return sb.isEmpty() ? "Scenario" : sb.toString();
    }

    /**
     * @return the file a generated class is written to:
     *     {@code <first test root>/Generated/<SimpleClassName>.swift}, the one path the generator and the
     *     executor both have to agree on.
     *
     * <p>The first test root and not a configured one, because SwiftPM only compiles the sources of a test
     * target it declares, and the detector lists exactly those directories. The runner uses this helper
     * rather than a second copy of the naming rule, so a class is written exactly where the name it was
     * recorded under says it is.
     */
    public static Path fileFor(VerificationContext context, String simpleName) {
        return context.projectInfo().testSourceDirs().getFirst()
                .resolve(GENERATED_DIRECTORY)
                .resolve(simpleName + ".swift");
    }

    /**
     * @return the XCTest method a counterexample is replayed as. It is exactly the name the generated file
     *     declares, because the executor's replay command filters on {@code <suite>/<method>} and SwiftPM
     *     matches the method part from the start of the discovered test name.
     *
     * <p>The seeded search is the interesting case to replay when there is one; a scenario that declares
     * neither inputs nor a search has only its single case, and naming a method that does not exist would
     * make the counterexample's own recipe wrong.
     */
    static String testMethod(Scenario scenario) {
        if (hasRandomSearch(scenario)) {
            return RANDOM_SEARCH_METHOD;
        }
        return collectInputs(scenario).isEmpty() ? SINGLE_CASE_METHOD : FIRST_CASE_METHOD;
    }

    private static boolean hasRandomSearch(Scenario scenario) {
        return !collectInputs(scenario).isEmpty()
                && (scenario.mode() != Scenario.Mode.SINGLE || scenario.cases() > 1);
    }

    // ------------------------------------------------------------------ rendering

    private String render(
            VerificationContext context,
            Requirement requirement,
            List<VerificationObligation> obligations,
            Scenario scenario) {

        List<Scenario.Input> inputs = collectInputs(scenario);
        List<Map<String, String>> boundaryCases = boundaryCases(inputs);
        String simpleName = simpleClassName(scenario);
        String module = importModule(context);
        String obligationsText = String.join(", ",
                obligations.stream().map(VerificationObligation::id).toList());
        StringBuilder sb = new StringBuilder();

        sb.append("// Generated by CodeWitness ").append(VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("// Requirement: ").append(requirement.id()).append(" - ")
                .append(oneLine(requirement.title())).append('\n');
        for (VerificationObligation obligation : obligations) {
            sb.append("// Obligation: ").append(obligation.id()).append(" - ")
                    .append(oneLine(obligation.description())).append('\n');
        }
        sb.append("// Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases())
                .append(")\n");
        sb.append("// This file is temporary: CodeWitness writes it into the project's own test target under\n");
        sb.append("// ").append(displayPath(context, simpleName)).append(", because SwiftPM only compiles the\n");
        sb.append("// Swift files of a declared test target, and the run keeps a copy as an artefact. It is left\n");
        sb.append("// in place so the replay command works.\n");
        if (scenario.mode() == Scenario.Mode.CONCURRENT) {
            sb.append(CONCURRENT_NOTE);
        }
        sb.append('\n');
        sb.append("import XCTest\n");
        // Foundation is imported explicitly rather than relied on transitively: the helpers read the
        // replay variables through ProcessInfo, and whether importing XCTest re-exports Foundation is a
        // property of the toolchain, not of this file.
        sb.append("import Foundation\n");
        sb.append("@testable import ").append(module).append("\n\n");
        sb.append("final class ").append(simpleName).append(": XCTestCase {\n\n");

        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            if (index >= MAX_BOUNDARY_CASES) {
                break;
            }
            sb.append(caseMethod(scenario, requirement, obligationsText, index, inputs, values, 4));
            index++;
        }

        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input declares a type but none declares a boundary value, so there is nothing to
            // enumerate. Run the declared defaults once instead of emitting a class with no case: an
            // empty class reports nothing, and a scenario that reports nothing must not be mistaken for
            // one that ran and passed.
            sb.append(caseMethod(scenario, requirement, obligationsText, 0, inputs, defaultValues(inputs), 4));
        }

        if (inputs.isEmpty()) {
            sb.append(singleCase(scenario, requirement, obligationsText, 4));
        }

        if (hasRandomSearch(scenario)) {
            sb.append(searchMethod(scenario, requirement, obligationsText, inputs, 4));
        }

        sb.append(helpers());
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * @return one boundary case as an XCTest method that declares the case's values and runs it.
     *
     * <p>The case index and the effective seed are locals of the method rather than instance state: XCTest
     * creates one instance per method, and the counterexample line is written inline, where the locals are
     * in scope, so nothing has to be threaded through an initializer.
     */
    private String caseMethod(
            Scenario scenario,
            Requirement requirement,
            String obligationsText,
            int index,
            List<Scenario.Input> inputs,
            Map<String, String> values,
            int indent) {

        String pad = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        sb.append(pad).append("func testCwCase").append(String.format(Locale.ROOT, "%04d", index))
                .append("() {\n");
        sb.append(pad).append("    let cwCase = ").append(index).append('\n');
        sb.append(pad).append("    let cwSeedValue: Int64? = nil\n");
        for (Scenario.Input input : inputs) {
            sb.append(pad).append("    let ").append(input.name()).append(" = ")
                    .append(literal(input, values.get(input.name()))).append('\n');
        }
        sb.append(caseBody(scenario, requirement, obligationsText, inputs, indent + 4));
        sb.append(pad).append("}\n\n");
        return sb.toString();
    }

    /** @return the single case of a scenario that declares no inputs, run with an empty input vector. */
    private String singleCase(
            Scenario scenario, Requirement requirement, String obligationsText, int indent) {

        String pad = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        sb.append(pad).append("func ").append(SINGLE_CASE_METHOD).append("() {\n");
        sb.append(pad).append("    let cwCase = 0\n");
        sb.append(pad).append("    let cwSeedValue: Int64? = nil\n");
        sb.append(caseBody(scenario, requirement, obligationsText, List.of(), indent + 4));
        sb.append(pad).append("}\n\n");
        return sb.toString();
    }

    /**
     * @return the seeded search: {@code cases} iterations over the declared input domains, each drawn from
     *     a generator seeded from the scenario's own seed plus the case index.
     *
     * <p>A fresh generator per case seeded with {@code seed + case} is what makes one case reproducible
     * from the seed and the index alone, so a counterexample's replay command can pin both. The generator
     * is this file's own SplitMix64 rather than the standard library's, because {@code SystemRandomNumberGenerator}
     * is not reproducible across toolchains and a fragment that happened to draw a random number could
     * otherwise move the search.
     *
     * <p>{@code CODEWITNESS_SEED} overrides the scenario's seed and {@code CODEWITNESS_CASE} pins a single
     * iteration, which is the channel the executor's replay command uses. Both are read from the process
     * environment here, exactly as the Ruby and Rust generated tests read them.
     */
    private String searchMethod(
            Scenario scenario,
            Requirement requirement,
            String obligationsText,
            List<Scenario.Input> inputs,
            int indent) {

        String pad = " ".repeat(indent);
        String inner = " ".repeat(indent + 4);
        String body = " ".repeat(indent + 8);
        StringBuilder sb = new StringBuilder();
        sb.append(pad).append("func ").append(RANDOM_SEARCH_METHOD).append("() {\n");
        sb.append(inner).append("// Seeded search; pin one case with CODEWITNESS_SEED / CODEWITNESS_CASE.\n");
        sb.append(inner).append("let cwSeedValue = cwSeed(").append(scenario.seed()).append(")\n");
        sb.append(inner).append("let cwCaseCount = cwCases(").append(scenario.cases()).append(")\n");
        sb.append(inner).append("let cwPinned = cwPinnedCase()\n");
        sb.append(inner).append("for cwCase in 0..<cwCaseCount {\n");
        sb.append(body).append("if cwPinned >= 0 && cwCase != cwPinned { continue }\n");
        sb.append(body).append("var cwRandom = CwRandom(seed: cwSeedValue &+ Int64(cwCase))\n");
        for (Scenario.Input input : inputs) {
            sb.append(body).append("let ").append(input.name()).append(" = ")
                    .append(drawExpression(input)).append('\n');
        }
        sb.append(caseBody(scenario, requirement, obligationsText, inputs, indent + 8));
        sb.append(inner).append("}\n");
        sb.append(pad).append("}\n\n");
        return sb.toString();
    }

    /**
     * @return the setup, the declared steps and the invariant check that follows each of them, indented so
     *     it fits inside the generated method.
     *
     * <p>The invariant check is wrapped in a {@code do} block of its own so that {@code let observation}
     * can be declared once per step without a redeclaration error on a multi-step scenario - while the
     * step calls themselves stay in the method's scope, because a step may declare a value the next step
     * uses, exactly as the same fragment would in Ruby or Python.
     */
    private static String caseBody(
            Scenario scenario,
            Requirement requirement,
            String obligationsText,
            List<Scenario.Input> inputs,
            int indent) {

        boolean checks = scenario.observation() != null && scenario.invariant() != null;
        String pad = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        if (scenario.setup() != null && !scenario.setup().isBlank()) {
            sb.append(indent(scenario.setup(), indent)).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(substitute(step.call(), inputs), indent)).append('\n');
            if (checks) {
                sb.append(pad).append("do {\n");
                sb.append(pad).append("    let observation = (")
                        .append(substitute(scenario.observation(), inputs)).append(")\n");
                sb.append(pad).append("    if !(")
                        .append(substitute(scenario.invariant(), inputs)).append(") {\n");
                sb.append(pad).append("        ")
                        .append(failureMessage(scenario, requirement, obligationsText, inputs, step)).append('\n');
                sb.append(pad).append("    }\n");
                sb.append(pad).append("}\n");
            }
        }
        return sb.toString();
    }

    /**
     * @return the {@code XCTFail} call whose message is the single structured counterexample line the
     *     engine decodes out of the XCTest failure.
     *
     * <p>The line is built at runtime from the case's own locals, so the exact inputs and the observed
     * value are in it, and the static fields (the requirement, the obligations, the declared observation
     * and invariant, the step) are escaped into the Swift string literal. Every field separator in the
     * protocol is {@code |}, so any {@code |} or line break in a fragment is replaced before it is
     * written: one line is the whole contract, because {@link CounterexampleProtocol#decode} reads to the
     * end of the line it finds.
     */
    private static String failureMessage(
            Scenario scenario,
            Requirement requirement,
            String obligationsText,
            List<Scenario.Input> inputs,
            Scenario.Step step) {

        StringBuilder sb = new StringBuilder("XCTFail(\"");
        sb.append(CounterexampleProtocol.MARKER);
        sb.append("|case=\\(cwCase)");
        sb.append("|seed=\\(cwSeedText(cwSeedValue))");
        sb.append("|requirement=").append(messageField(requirement.id()));
        sb.append("|obligation=").append(messageField(obligationsText));
        sb.append("|inputs=\\(cwInputs(").append(inputsLiteral(inputs)).append("))");
        sb.append("|observation=").append(messageField(substitute(scenario.observation(), inputs)));
        sb.append("|expected=").append(messageField(substitute(scenario.invariant(), inputs)));
        sb.append("|actual=\\(cwText(observation))");
        sb.append("|invariant=").append(messageField(substitute(scenario.invariant(), inputs)));
        sb.append("|step=").append(messageField(step.description()));
        return sb.append("\")").toString();
    }

    /**
     * The helpers every generated class carries, written once so no case owns the protocol.
     *
     * <p>They are instance methods of the class rather than free functions so they cannot collide with the
     * project's own globals, and the random generator is a nested type for the same reason. One token is
     * filled in from the adapter rather than copied into the text block: the counterexample marker, which
     * comes from {@link CounterexampleProtocol} so a generated file cannot disagree with the engine about
     * the line it decodes.
     */
    private static String helpers() {
        // Indented into the class body for a reader: Swift does not care, but a helper block that starts at
        // column zero inside a class reads like it belongs to the file rather than to the class.
        return indent(HELPERS.replace(MARKER_PLACEHOLDER, CounterexampleProtocol.MARKER).strip(), 4) + "\n";
    }

    /** The token in {@link #HELPERS} replaced by the engine's own counterexample marker. */
    private static final String MARKER_PLACEHOLDER = "CWMARKER";

    /** The note a concurrent scenario's generated file carries, because the steps run once, in order. */
    private static final String CONCURRENT_NOTE = """
            // LIMIT: the scenario declares mode "concurrent" and this case executes its steps once, in order.
            // Swift has concurrency, but a scenario's fragments are arbitrary user code this generator cannot
            // assume is safe under it, and a step that failed inside a detached task would have to be caught
            // and re-reported by machinery this file does not own - a failure that could otherwise be
            // swallowed and counted as a pass. The case still executes the declared steps and checks the
            // declared invariant; it demonstrates nothing about concurrency.
            """;

    private static final String HELPERS = """

            // ---------------------------------------------------------------- CodeWitness helpers

            /// A small deterministic generator (SplitMix64) so a case is reproducible from its seed alone.
            private struct CwRandom {
                private var state: UInt64

                init(seed: Int64) {
                    self.state = UInt64(bitPattern: seed)
                }

                mutating func next() -> UInt64 {
                    state = state &+ 0x9E3779B97F4A7C15
                    var z = state
                    z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
                    z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
                    return z ^ (z >> 31)
                }

                mutating func int(_ minimum: Int, _ maximum: Int) -> Int {
                    maximum <= minimum ? minimum : minimum + Int(next() % UInt64(maximum - minimum + 1))
                }

                mutating func double(_ minimum: Double, _ maximum: Double) -> Double {
                    maximum <= minimum ? minimum
                        : minimum + (maximum - minimum) * (Double(next() >> 11) / 9007199254740992.0)
                }

                mutating func bool() -> Bool {
                    next() % 2 == 1
                }

                mutating func text() -> String {
                    "cw-" + String(describing: next() % 10000)
                }
            }

            private func cwEnvInteger(_ name: String) -> Int? {
                guard let raw = ProcessInfo.processInfo.environment[name]?
                    .trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
                    return nil
                }
                return Int(raw)
            }

            private func cwSeed(_ fallback: Int64) -> Int64 {
                guard let value = cwEnvInteger("CODEWITNESS_SEED") else { return fallback }
                return Int64(value)
            }

            private func cwCases(_ fallback: Int) -> Int {
                guard let value = cwEnvInteger("CODEWITNESS_CASES"), value >= 1 else { return fallback }
                return value
            }

            private func cwPinnedCase() -> Int {
                cwEnvInteger("CODEWITNESS_CASE") ?? -1
            }

            private func cwSeedText(_ value: Int64?) -> String {
                guard let value else { return "" }
                return String(value)
            }

            private func cwText<T>(_ value: T) -> String {
                cwSanitize(String(describing: value))
            }

            private func cwSanitize(_ text: String) -> String {
                text.replacingOccurrences(of: "|", with: "/")
                    .replacingOccurrences(of: "\\n", with: " ")
                    .replacingOccurrences(of: "\\r", with: " ")
            }

            private func cwInputs(_ pairs: [(String, String)]) -> String {
                "{" + pairs.map { cwSanitize($0.0) + "=" + cwSanitize($0.1) }.joined(separator: ", ") + "}"
            }

            private func cwInt(_ random: inout CwRandom, _ minimum: Int, _ maximum: Int) -> Int {
                random.int(minimum, maximum)
            }

            private func cwDouble(_ random: inout CwRandom, _ minimum: Double, _ maximum: Double) -> Double {
                random.double(minimum, maximum)
            }

            private func cwBool(_ random: inout CwRandom) -> Bool {
                random.bool()
            }

            private func cwString(_ random: inout CwRandom) -> String {
                random.text()
            }
            """;

    // ------------------------------------------------------------------ values

    /** How a generated file treats one declared input, once the domain and the Swift type name agree. */
    private enum Kind { INTEGER, DOUBLE, BOOLEAN, STRING, OTHER }

    /**
     * @return {@code true} when every input can be spelled as one of the four Swift scalar types this
     *     generator supports: {@code Int}, {@code Double}, {@code Bool} and {@code String}.
     *
     * <p>A scenario that declares something else cannot be rendered as compilable Swift here, and the
     * honest answer is to skip it - so the obligation stays {@code UNVERIFIED} - rather than to emit a
     * class that fails the project's test target.
     */
    private static boolean renderable(Scenario scenario) {
        return collectInputs(scenario).stream().allMatch(input -> kind(input) != Kind.OTHER);
    }

    /**
     * @return the value kind of an input: the declared domain when the scenario names one, otherwise the
     *     Swift type name.
     *
     * <p>{@code Scenario.Domain} is derived from a type name by a table of Java spellings, so a Swift
     * scenario that writes {@code type: Int} or {@code type: Bool} with no explicit domain would land on
     * {@code RAW} and the generated literal would be a bare word that does not parse. The Swift type names
     * a scenario author would write are therefore read here as well, the same way the Ruby and PHP
     * generators read theirs.
     */
    private static Kind kind(Scenario.Input input) {
        return switch (input.domain()) {
            case INTEGER, LONG, INSTANT_MILLIS -> Kind.INTEGER;
            case DOUBLE, DECIMAL -> Kind.DOUBLE;
            case BOOLEAN -> Kind.BOOLEAN;
            case STRING -> Kind.STRING;
            case NULL -> Kind.OTHER;
            case RAW -> switch (input.type().trim().toLowerCase(Locale.ROOT)) {
                case "int", "integer", "int8", "int16", "int32", "int64", "uint", "uint64" -> Kind.INTEGER;
                case "double", "float", "cgfloat", "decimal", "nsdecimalnumber" -> Kind.DOUBLE;
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
            case STRING -> swiftString(value);
            case OTHER -> swiftString(value);
        };
    }

    /**
     * @return the value a case uses when the input declares none, as a value rather than as source text:
     *     {@link #literal} is what turns it into a Swift literal, so a string default is the empty string
     *     and not two quote characters that would then be quoted again.
     */
    private static String defaultLiteral(Scenario.Input input) {
        return switch (kind(input)) {
            case INTEGER -> "0";
            case DOUBLE -> "0.0";
            case BOOLEAN -> "false";
            case STRING -> "";
            case OTHER -> "";
        };
    }

    /**
     * @return the Swift expression a search draws one input with, bounded by the declared range where the
     *     scenario gives one and by the same defaults the Ruby generator uses where it does not.
     */
    private static String drawExpression(Scenario.Input input) {
        return switch (kind(input)) {
            case INTEGER -> "cwInt(&cwRandom, " + integer(input.min(), "0") + ", "
                    + integer(input.max(), input.domain() == Scenario.Domain.INSTANT_MILLIS ? "86400000" : "10000")
                    + ")";
            case DOUBLE -> "cwDouble(&cwRandom, " + number(input.min(), "0.0") + ", "
                    + number(input.max(), "1000.0") + ")";
            case BOOLEAN -> "cwBool(&cwRandom)";
            case STRING -> "cwString(&cwRandom)";
            case OTHER -> "0";
        };
    }

    /** @return the text as a Swift integer literal, or {@code fallback} when it is not one. */
    private static String integer(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.matches("[+-]?\\d+") ? trimmed : fallback;
    }

    /**
     * @return the text as a Swift {@code Double} literal, or {@code fallback} when it is not a number.
     *
     * <p>A bare integer is spelled with a trailing {@code .0}: {@code let price = 2} infers {@code Int} in
     * Swift, so a scenario value of {@code 2} for a double input would otherwise fail to compile against
     * the project's own {@code Double} parameter.
     */
    private static String number(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
            return fallback;
        }
        return trimmed.indexOf('.') < 0 && trimmed.indexOf('e') < 0 && trimmed.indexOf('E') < 0
                ? trimmed + ".0" : trimmed;
    }

    private static String booleanLiteral(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.equalsIgnoreCase("true") || trimmed.equals("1") ? "true" : "false";
    }

    /**
     * @return a Swift string literal for arbitrary text.
     *
     * <p>A double-quoted Swift literal is the only form the language has, and it interpolates {@code \(...)}
     * and processes backslash escapes, so the backslash, the quote and the control characters that cannot
     * appear literally are escaped. Escaping the backslash is what stops a scenario value that contains
     * {@code \(} from being evaluated inside the generated file instead of staying the text it wrote.
     */
    static String swiftString(String value) {
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
                case '\0' -> sb.append("\\0");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
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

    /**
     * @return the Cartesian product of every input's boundary values, capped at {@link #MAX_BOUNDARY_CASES}.
     *
     * <p>An input's boundary values are every declared {@code values} entry plus its declared range's
     * minimum and maximum, and the two interior neighbours when the range is wider than two. An input with
     * neither a value list nor a range contributes its declared default as its one boundary value, which is
     * what makes a scenario whose inputs have no boundaries still execute exactly one case rather than
     * none.
     */
    private static List<Map<String, String>> boundaryCases(List<Scenario.Input> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> accumulation = new ArrayList<>();
        accumulation.add(new LinkedHashMap<>());
        for (Scenario.Input input : inputs) {
            List<String> candidates = boundaryValuesFor(input);
            if (candidates.isEmpty()) {
                return List.of();
            }
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : accumulation) {
                for (String value : candidates) {
                    if (next.size() >= MAX_BOUNDARY_CASES) {
                        break;
                    }
                    Map<String, String> copy = new LinkedHashMap<>(partial);
                    copy.put(input.name(), value);
                    next.add(copy);
                }
                if (next.size() >= MAX_BOUNDARY_CASES) {
                    break;
                }
            }
            accumulation = next;
        }
        return List.copyOf(accumulation.subList(0, Math.min(accumulation.size(), MAX_BOUNDARY_CASES)));
    }

    /**
     * @return the boundary values of one input: every declared {@code values} entry, plus the declared
     *     range's ends and - when the range is wider than two - the two values just inside them, or the
     *     declared default when the input declares neither.
     *
     * <p>The declared values come first and the range-derived ones follow, so the first generated case is
     * the one the scenario's own {@code values} describe. The range ends and their neighbours are added
     * even when values are declared, because a range is a second statement about the interesting region
     * and dropping it would leave the ends of a declared domain untried; duplicates are removed so a
     * scenario that spells {@code values: ["0", "100"]} next to {@code min: 0, max: 100} does not run the
     * same case twice.
     */
    private static List<String> boundaryValuesFor(Scenario.Input input) {
        List<String> values = new ArrayList<>();
        for (String declared : input.values()) {
            addDistinct(values, declared);
        }
        String min = input.min();
        String max = input.max();
        boolean hasMin = min != null && !min.isBlank();
        boolean hasMax = max != null && !max.isBlank();
        if (hasMin) {
            addDistinct(values, min.strip());
        }
        if (hasMax) {
            addDistinct(values, max.strip());
        }
        if (hasMin && hasMax && widerThanTwo(min, max)) {
            addDistinct(values, stepOne(min, 1));
            addDistinct(values, stepOne(max, -1));
        }
        if (values.isEmpty()) {
            values.add(defaultLiteral(input));
        }
        return List.copyOf(values);
    }

    /** Adds a boundary value unless it is blank or already present, so one case is never run twice. */
    private static void addDistinct(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    /** @return {@code true} when both ends parse as numbers and the upper end is more than two above. */
    private static boolean widerThanTwo(String min, String max) {
        try {
            return new BigDecimal(max.strip()).subtract(new BigDecimal(min.strip()))
                    .compareTo(BigDecimal.valueOf(2)) > 0;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /** @return the end moved one whole unit inward, as plain text; used for the interior boundary values. */
    private static String stepOne(String value, int delta) {
        try {
            BigDecimal moved = new BigDecimal(value.strip()).add(BigDecimal.valueOf(delta));
            return moved.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException _) {
            return value.strip();
        }
    }

    /** @return the Swift pair-array literal the counterexample line renders its inputs from. */
    private static String inputsLiteral(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('(').append(swiftString(inputs.get(i).name()))
                    .append(", cwText(").append(inputs.get(i).name()).append("))");
        }
        return sb.append(']').toString();
    }

    /**
     * Replaces every {@code ${name}} with the Swift local variable {@code name}, which is the whole DSL.
     *
     * <p>The placeholder is {@code ${name}} and not {@code \(name)}: {@code \(...)} is string interpolation
     * in Swift, so a fragment that built a message out of an input would be silently rewritten, while
     * {@code ${...}} is not valid Swift anywhere and cannot collide with what the fragment meant to write.
     */
    private static String substitute(String fragment, List<Scenario.Input> inputs) {
        String result = fragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("${" + input.name() + "}", input.name());
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

    /**
     * @param value a fragment written into the counterexample line
     * @return the fragment as one line with no protocol separator, escaped for a Swift string literal, so
     *     an invariant that contains a quote or a newline cannot break the generated {@code XCTFail} call
     *     and cannot split the protocol line the engine decodes.
     */
    private static String messageField(String value) {
        return oneLine(value).replace("|", "/").replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    /**
     * @param simpleName the generated class's simple name
     * @return the path a reader should open, for the header comment of the generated file
     */
    private static String displayPath(VerificationContext context, String simpleName) {
        if (context.projectInfo().testSourceDirs().isEmpty()) {
            return GENERATED_DIRECTORY + "/" + simpleName + ".swift";
        }
        Path file = fileFor(context, simpleName).toAbsolutePath().normalize();
        Path root = context.projectRoot().toAbsolutePath().normalize();
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }
}

package dev.codewitness.ruby.generate;

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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns declared scenarios into executable RSpec spec files.
 *
 * <p>A scenario supplies the oracle - the setup, the calls and the invariant, written in Ruby in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the
 * seeded random search and the structured failure report. CodeWitness never invents an oracle, because
 * an invented oracle is exactly the kind of assertion this tool exists to avoid.
 *
 * <p>The generated file is an ordinary spec written into the project's own test tree, at
 * {@code <test root>/generated/<name>_spec.rb}, and it stays there. It has to: RSpec discovers a spec by
 * its file name, and the {@code require 'spec_helper'} it opens with is what loads the project's own
 * configuration - a spec that lives elsewhere loads nothing. The replay command in a counterexample is a
 * real {@code rspec} invocation over that file, and deleting it would make that command a lie; the run
 * also keeps a copy in its artefact store. This class only produces sources: it never runs RSpec, never
 * observes a result and never assigns a status.
 *
 * <p>Each case is one RSpec example, so a failure is attributed by RSpec's own report rather than by a
 * line number a reader has to interpret. A failing case fails through the same
 * {@code RSpec::Expectations::ExpectationNotMetError} that an ordinary {@code expect(...)} raises, whose
 * message is a single {@code CODEWITNESS_COUNTEREXAMPLE|...} line carrying the exact inputs, the seed,
 * the case index, the observed value and the invariant; that line is what the engine decodes out of the
 * {@code exception.message} the report carries.
 *
 * <p>The generated spec is named after the dotted path its report will carry
 * ({@code spec/generated/cw_sc_x_spec.rb} -&gt; {@code spec.generated.cw_sc_x_spec}), because that string
 * is how the executor's report parser labels an example and therefore how the engine attributes a result
 * back to the test that produced it. {@link #classNameFor(VerificationContext, Scenario)} derives it with
 * the report parser's own rule, from the scenario and the project's test tree and never from the
 * obligation, so one scenario always produces one file however many obligations asked for it.
 *
 * <p>Stated limit: the declared steps of a scenario whose mode is {@code concurrent} are executed once,
 * in order, and the generated file says so. Ruby has threads, but a scenario's fragments are arbitrary
 * user code that this generator cannot assume is thread-safe, and a step that raised inside a worker
 * thread would have to be caught and re-reported by machinery the generated file does not own - a failure
 * that could otherwise be swallowed and counted as a pass. The case still executes the declared steps and
 * checks the declared invariant; it demonstrates nothing about interleaving. The PHP adapter states the
 * same limit for the same reason, and this generator mirrors it rather than pretending to more.
 */
public final class RubyScenarioTestGenerator implements VerificationTools.TestGenerator {

    /** Upper bound on generated boundary cases per scenario. */
    private static final int MAX_BOUNDARY_CASES = 12;

    /** The token in {@link #HELPERS} replaced by the engine's own counterexample marker. */
    private static final String MARKER_PLACEHOLDER = "CWMARKER";

    /** The scaffolding every generated spec file is written against. */
    private final RSpecProfile profile;

    /**
     * The RSpec scaffolding a generated spec file is written against.
     *
     * <p>Ruby has exactly one of these in this adapter, so it is a record rather than an interface: the
     * shared {@link dev.codewitness.support.generate.LanguageScenarioProfile} exists so that the
     * JavaScript and TypeScript adapters can drive one generator with two dialects, and a second Ruby
     * dialect is not something this adapter has. Naming the scaffolding in one object is still what keeps
     * the renderer from spelling RSpec out in a dozen places, and it is what a second test framework would
     * replace.
     *
     * <p>There is no fixture-setup member. A generated spec has no scaffolding-level fixture hook: the
     * scenario's own {@code setup} fragment is executed inside each example, exactly as the PHP generator
     * executes it inside each case, because it is the user's fixture rather than the framework's.
     *
     * @param extension       file extension of a generated spec, without the dot
     * @param directory       directory below the project's test root the spec is written into
     * @param helperRequire   the line that loads the project's own spec helper
     * @param describeKeyword the keyword a spec file groups its examples under
     * @param caseKeyword     the keyword one generated case is written with
     * @param truthMatcher    the matcher a declared invariant is checked through; the invariant is a
     *                        boolean by declaration, so an expression that merely returns a truthy value
     *                        fails, and that is the strict reading of the contract the scenario states
     * @param failureClass    the exception class a counterexample is raised as, which is the class RSpec's
     *                        own matchers raise, so a generated failure is an RSpec failure and nothing else
     */
    public record RSpecProfile(
            String extension,
            String directory,
            String helperRequire,
            String describeKeyword,
            String caseKeyword,
            String truthMatcher,
            String failureClass) {

        /** @return the scaffolding a generated RSpec spec file is written against. */
        public static RSpecProfile rspec() {
            return new RSpecProfile("rb", "generated", "require 'spec_helper'", "RSpec.describe", "it",
                    "be(true)", "RSpec::Expectations::ExpectationNotMetError");
        }

        /**
         * @param specName the generated spec's name without its extension
         * @return the spec's path relative to the project's test root
         */
        public String relativePath(String specName) {
            return directory + "/" + specName + "." + extension;
        }
    }

    /** Creates the generator that emits RSpec spec files. */
    public RubyScenarioTestGenerator() {
        this(RSpecProfile.rspec());
    }

    /**
     * @param profile the scaffolding the emitted spec files are written against
     */
    public RubyScenarioTestGenerator(RSpecProfile profile) {
        this.profile = profile;
    }

    @Override
    public String id() {
        return "ruby-scenarios";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUBY);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        // The spec file identifies the scenario, not the obligation, so two obligations that share one
        // scenario would produce two files with one name - and the executor writes them to one path.
        // Grouping first means the one file carries both obligations and neither is silently lost. Ruby
        // would run two `RSpec.describe` blocks with the same description happily and then attribute both
        // results to the same name, which is the same loss with more noise.
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
            String className = classNameFor(context, scenario, profile);
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
     * @return the generated spec file's name without its extension: {@code cw_sc_<ScenarioId>_spec}, with
     *     every character that cannot appear in a Ruby identifier replaced by an underscore.
     *
     * <p>An underscore rather than a deletion, because {@code SC-A_B} and {@code SC-AB} must not become the
     *     same file: two scenarios in one run would then overwrite each other's generated spec and one
     *     requirement would be judged by the other's experiment.
     */
    static String specName(Scenario scenario) {
        return "cw_sc_" + identifier(scenario.id()) + "_spec";
    }

    /** @return an ASCII identifier fragment, so the result names a runnable spec whatever the id was. */
    static String identifier(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            sb.append(ascii ? c : '_');
        }
        return sb.isEmpty() ? "Scenario" : sb.toString();
    }

    /**
     * @return the identifier the generated spec is reported under: the path of
     *     {@link #specFile(VerificationContext, String)} relative to the project root with its extension
     *     removed and its separators turned into dots.
     *
     * <p>That is the same rule {@code RubyTestReportParser} applies to RSpec's own {@code file_path}, and
     * it has to be, because the engine attributes a result by matching the suite the report names against
     * the {@code className} recorded here. The rule is restated rather than called because the parser's
     * helper is package-private to {@code dev.codewitness.ruby}; {@code RubyGeneratedTestRunner} refuses a
     * batch whose report names no generated test, so a drift between the two would be reported as a
     * failure to execute rather than silently scoring as "no case ran".
     *
     * <p>A project with no test directory is the one case with no path to name: the spec falls back to its
     * own file name, and the executor refuses the batch with the reason, since there is no tree RSpec
     * would scan.
     */
    static String classNameFor(VerificationContext context, Scenario scenario) {
        return classNameFor(context, scenario, RSpecProfile.rspec());
    }

    /** @param profile the scaffolding the spec's path is named by */
    static String classNameFor(VerificationContext context, Scenario scenario, RSpecProfile profile) {
        String specName = specName(scenario);
        if (context.projectInfo().testSourceDirs().isEmpty()) {
            return specName;
        }
        Path file = specFile(context, specName, profile).toAbsolutePath().normalize();
        Path root = context.projectRoot().toAbsolutePath().normalize();
        String relative = file.startsWith(root)
                ? root.relativize(file).toString() : file.getFileName().toString();
        String name = relative.replace('\\', '/');
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        return name.replace('/', '.');
    }

    /**
     * @return the file a generated spec is written to: {@code <test root>/<directory>/<name>.<extension>},
     *     the one path the generator and the executor both have to agree on
     */
    static Path specFile(VerificationContext context, String specName) {
        return specFile(context, specName, RSpecProfile.rspec());
    }

    /** @param profile the scaffolding the spec's path is named by */
    static Path specFile(VerificationContext context, String specName, RSpecProfile profile) {
        return context.projectInfo().testSourceDirs().getFirst().resolve(profile.relativePath(specName));
    }

    /**
     * @return the example that reproduces one case, named as the generated file names it.
     *
     * <p>The random search is the interesting case to replay when there is one; a scenario that declares
     * neither boundary values nor a search has only its single case, and naming an example that does not
     * exist would make the counterexample's own recipe wrong. RSpec's {@code --example} filter matches on
     * a description, so the name recorded here is exactly the description the generated file uses.
     */
    static String testMethod(Scenario scenario) {
        if (hasRandomSearch(scenario)) {
            return scenario.mode() == Scenario.Mode.CONCURRENT ? "cw_concurrent_search" : "cw_random_search";
        }
        return collectInputs(scenario).isEmpty() ? "cw_single_case" : "cw_case0000";
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

        sb.append("# Generated by CodeWitness ").append(VerificationEngine.VERSION)
                .append(" for run ").append(context.runId()).append(".\n");
        sb.append("# Requirement: ").append(requirement.id()).append(" - ").append(oneLine(requirement.title()))
                .append('\n');
        for (VerificationObligation obligation : obligations) {
            sb.append("# Obligation: ").append(obligation.id()).append(" - ")
                    .append(oneLine(obligation.description())).append('\n');
        }
        sb.append("# Scenario: ").append(scenario.id()).append(" (mode ").append(scenario.mode())
                .append(", seed ").append(scenario.seed()).append(", cases ").append(scenario.cases()).append(")\n");
        sb.append("# This file is temporary: CodeWitness writes it into the project's own test tree under\n");
        sb.append("# ").append(displayPath(className)).append(", because RSpec discovers a spec by its file name and the\n");
        sb.append("# project's spec_helper is what makes one loadable, and the run keeps a copy as an artefact.\n");
        sb.append("# It is left in place so the replay command works.\n");
        if (scenario.mode() == Scenario.Mode.CONCURRENT) {
            sb.append(CONCURRENT_NOTE);
        }
        sb.append('\n');

        sb.append(profile.helperRequire()).append("\n\n");
        sb.append(profile.describeKeyword()).append(' ').append(rubyString(className)).append(" do\n");
        sb.append("  def cw_requirement\n    ").append(rubyString(requirement.id())).append("\n  end\n\n");
        sb.append("  def cw_obligations\n    ")
                .append(rubyString(String.join(", ",
                        obligations.stream().map(VerificationObligation::id).toList())))
                .append("\n  end\n\n");
        sb.append("  def cw_invariant\n    ")
                .append(rubyString(oneLine(scenario.invariant() == null
                        ? "(no invariant declared)" : scenario.invariant())))
                .append("\n  end\n\n");

        int index = 0;
        for (Map<String, String> values : boundaryCases) {
            sb.append(caseExample(scenario, index, inputs, values));
            index++;
        }

        if (boundaryCases.isEmpty() && !inputs.isEmpty()) {
            // Every input has a declared type but none declares a boundary value, so there is nothing to
            // enumerate. Run the declared defaults once instead of emitting a file with no example at all:
            // an empty spec file reports nothing, and a scenario that reports nothing must not be mistaken
            // for one that ran and passed.
            sb.append(caseExample(scenario, 0, inputs, defaultValues(inputs)));
        }

        if (boundaryCases.isEmpty() && inputs.isEmpty()) {
            sb.append(singleCase(scenario));
        }

        if (hasRandomSearch(scenario)) {
            sb.append(searchExample(inputs, scenario));
        }

        sb.append(helpers());
        sb.append("end\n");
        return sb.toString();
    }

    /**
     * @return one boundary case as an example that assigns the case's values and runs it.
     *
     * <p>The case's own context is put in instance variables rather than passed to every helper. An RSpec
     *     example is an instance that lives for exactly one case, so nothing can leak from one case to the
     *     next, and the invariant check - which is written inline because the scenario's fragments name the
     *     inputs as plain locals - can report a counterexample without repeating four arguments on every
     *     line of generated source.
     */
    private String caseExample(
            Scenario scenario, int index, List<Scenario.Input> inputs, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(profile.caseKeyword()).append(" 'cw_case")
                .append(String.format(Locale.ROOT, "%04d", index)).append("' do\n");
        sb.append("    @cw_case = ").append(index).append('\n');
        sb.append("    @cw_seed_value = nil\n");
        for (Scenario.Input input : inputs) {
            sb.append("    ").append(input.name()).append(" = ")
                    .append(literal(input, values.get(input.name()))).append('\n');
        }
        sb.append("    @cw_inputs = ").append(inputsLiteral(inputs)).append('\n');
        sb.append("    cw_run_case(").append(rubyString(stepForThrow(scenario, inputs))).append(") do\n");
        sb.append(caseBody(scenario, inputs, 6));
        sb.append("    end\n");
        sb.append("  end\n\n");
        return sb.toString();
    }

    /** @return the single case of a scenario that declares no inputs, run with an empty input vector. */
    private String singleCase(Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(profile.caseKeyword()).append(" 'cw_single_case' do\n");
        sb.append("    @cw_case = 0\n");
        sb.append("    @cw_seed_value = nil\n");
        sb.append("    @cw_inputs = []\n");
        sb.append("    cw_run_case(").append(rubyString(stepForThrow(scenario, List.of()))).append(") do\n");
        sb.append(caseBody(scenario, List.of(), 6));
        sb.append("    end\n");
        sb.append("  end\n\n");
        return sb.toString();
    }

    private String searchExample(List<Scenario.Input> inputs, Scenario scenario) {
        String keyword = scenario.mode() == Scenario.Mode.CONCURRENT
                ? "cw_concurrent_search" : "cw_random_search";
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(profile.caseKeyword()).append(" '").append(keyword).append("' do\n");
        sb.append("    # Seeded search; pin one case with CODEWITNESS_SEED / CODEWITNESS_CASE.\n");
        sb.append("    cw_seed_value = cw_seed(").append(scenario.seed()).append(")\n");
        sb.append("    cw_case_count = cw_cases(").append(scenario.cases()).append(")\n");
        sb.append("    cw_pinned = cw_pinned_case\n");
        sb.append("    (0...cw_case_count).each do |cw_case|\n");
        sb.append("      next if cw_pinned >= 0 && cw_case != cw_pinned\n");
        // A fresh generator per case, seeded with the run's seed plus the case index, is what makes one
        // case reproducible from the seed and the index alone. Ruby's Random is Mersenne Twister with a
        // seed-to-state mapping fixed by the interpreter's own specification, and using this object rather
        // than Kernel#rand means a fragment that happens to call rand cannot move the search.
        sb.append("      rnd = Random.new(cw_seed_value + cw_case)\n");
        for (Scenario.Input input : inputs) {
            sb.append("      ").append(input.name()).append(" = ").append(drawExpression(input)).append('\n');
        }
        sb.append("      @cw_case = cw_case\n");
        sb.append("      @cw_seed_value = cw_seed_value\n");
        sb.append("      @cw_inputs = ").append(inputsLiteral(inputs)).append('\n');
        sb.append("      cw_run_case(").append(rubyString(stepForThrow(scenario, inputs))).append(") do\n");
        sb.append(caseBody(scenario, inputs, 8));
        sb.append("      end\n");
        sb.append("    end\n");
        sb.append("  end\n\n");
        return sb.toString();
    }

    /**
     * @return the setup, the declared steps and the invariant check that follows each of them, indented so
     *     it fits inside the generated example.
     *
     * <p>Unlike the PHP generator this body is written into every case rather than into one shared method.
     *     The scenario's fragments name the inputs as plain Ruby locals, so the body has to be written
     *     where those locals are in scope; a shared method could only see instance variables, which would
     *     change the DSL the scenario author writes. The Node generator inlines its cases for the same
     *     reason.
     */
    private static String caseBody(Scenario scenario, List<Scenario.Input> inputs, int indent) {
        boolean checks = scenario.observation() != null && scenario.invariant() != null;
        String pad = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        if (scenario.setup() != null) {
            sb.append(indent(scenario.setup(), indent)).append('\n');
        }
        for (Scenario.Step step : scenario.steps()) {
            sb.append(indent(substitute(step.call(), inputs), indent)).append('\n');
            if (checks) {
                sb.append(pad).append("observation = ")
                        .append(substitute(scenario.observation(), inputs)).append('\n');
                sb.append(pad).append("cw_check(observation, (")
                        .append(substitute(scenario.invariant(), inputs)).append("), ")
                        .append(rubyString(oneLine(scenario.invariant()))).append(", ")
                        .append(rubyString(oneLine(step.description()))).append(")\n");
            }
        }
        return sb.toString();
    }

    /**
     * @return the text the thrown-error path reports as the step it was executing.
     *
     * <p>PHP passes the observation expression here because the step that threw is not known by the time
     *     the case catches the error; this generator keeps that answer rather than inventing a step name,
     *     and says "step execution" only when the scenario declares no observation at all.
     */
    private static String stepForThrow(Scenario scenario, List<Scenario.Input> inputs) {
        return oneLine(substitute(
                scenario.observation() == null ? "step execution" : scenario.observation(), inputs));
    }

    /**
     * The helper methods every generated spec carries, written once so no case owns the protocol.
     *
     * <p>They are methods of the example group rather than constants, because Ruby refuses a constant
     * assignment inside a block - and the whole file is one block. They are named with a {@code cw_}
     * prefix so they cannot collide with a project helper the spec_helper defines.
     *
     * <p>Three tokens are filled in from the adapter rather than copied into the text block: the
     * counterexample marker, which comes from {@link CounterexampleProtocol} so a generated file cannot
     * disagree with the engine about the line it decodes; the failure class; and the matcher the invariant
     * is checked through. Both of the latter come from the profile, which is what makes the emitted
     * scaffolding data rather than a string in a dozen places.
     */
    private String helpers() {
        return HELPERS
                .replace(MARKER_PLACEHOLDER, CounterexampleProtocol.MARKER)
                .replace("CWFAILURE", profile.failureClass())
                .replace("CWTRUTH", profile.truthMatcher());
    }

    /** The note a concurrent scenario's generated file carries, because the steps run once, in order. */
    private static final String CONCURRENT_NOTE = """
            # LIMIT: the scenario declares mode "concurrent" and this example executes its steps once, in order.
            # Ruby has threads, but a scenario's fragments are arbitrary user code this generator cannot assume
            # is thread-safe, and a step that raised inside a worker thread would have to be caught and
            # re-reported by machinery this file does not own - a failure that could otherwise be swallowed and
            # counted as a pass. The case still executes the declared steps and checks the declared invariant;
            # it demonstrates nothing about concurrency.
            """;

    private static final String HELPERS = """

              def cw_seed(cw_fallback)
                cw_value = cw_env_integer('CODEWITNESS_SEED')
                cw_value.nil? ? cw_fallback : cw_value
              end

              def cw_cases(cw_fallback)
                cw_value = cw_env_integer('CODEWITNESS_CASES')
                cw_value.nil? || cw_value < 1 ? cw_fallback : cw_value
              end

              def cw_pinned_case
                cw_value = cw_env_integer('CODEWITNESS_CASE')
                cw_value.nil? ? -1 : cw_value
              end

              def cw_env_integer(cw_name)
                cw_raw = ENV[cw_name].to_s.strip
                cw_raw.empty? ? nil : Integer(cw_raw, 10, exception: false)
              end

              def cw_int(cw_rnd, cw_min, cw_max)
                cw_max <= cw_min ? cw_min : cw_rnd.rand(cw_min..cw_max)
              end

              def cw_double(cw_rnd, cw_min, cw_max)
                cw_max <= cw_min ? cw_min : cw_min + (cw_max - cw_min) * cw_rnd.rand
              end

              def cw_bool(cw_rnd)
                cw_rnd.rand(2) == 1
              end

              def cw_string(cw_rnd)
                'cw-' + cw_rnd.rand(10000).to_s
              end

              def cw_text(cw_value)
                return 'nil' if cw_value.nil?
                cw_value.to_s
              rescue StandardError
                cw_value.class.to_s
              end

              def cw_sanitize(cw_value)
                cw_value.to_s.gsub(/[\\r\\n|]/, ' ')
              end

              def cw_render_inputs(cw_inputs)
                '{' + cw_inputs.map { |cw_name, cw_value| cw_name.to_s + '=' + cw_value.to_s }.join(', ') + '}'
              end

              def cw_protocol(cw_case, cw_seed, cw_inputs, cw_expected, cw_actual, cw_step)
                cw_fields = [
                  ['case', cw_case],
                  ['seed', cw_seed.nil? ? '' : cw_seed],
                  ['inputs', cw_render_inputs(cw_inputs)],
                  ['expected', cw_expected],
                  ['actual', cw_actual],
                  ['invariant', cw_invariant],
                  ['step', cw_step],
                  ['requirement', cw_requirement],
                  ['obligation', cw_obligations],
                ]
                cw_line = 'CWMARKER'
                cw_fields.each { |cw_key, cw_value| cw_line += '|' + cw_key.to_s + '=' + cw_sanitize(cw_value) }
                cw_line
              end

              def cw_fail(cw_expected, cw_actual, cw_step)
                raise CWFAILURE,
                      cw_protocol(@cw_case, @cw_seed_value, @cw_inputs, cw_expected, cw_actual, cw_step)
              end

              def cw_check(cw_observation, cw_holds, cw_expected, cw_step)
                expect(cw_holds).to CWTRUTH
              rescue CWFAILURE
                cw_fail(cw_expected, cw_text(cw_observation), cw_step)
              end

              def cw_run_case(cw_step)
                yield
              rescue CWFAILURE
                raise
              rescue StandardError => cw_error
                cw_fail('the declared steps must not throw ' + cw_error.class.to_s, cw_error.message, cw_step)
              end
            """;

    // ------------------------------------------------------------------ values

    /** How a generated file treats one declared input, once the domain and the Ruby type name agree. */
    private enum Kind { INTEGER, DOUBLE, BOOLEAN, STRING, OTHER }

    /**
     * @return the value kind of an input: the declared domain when the scenario names one, otherwise the
     *     Ruby type name.
     *
     * <p>{@code Scenario.Domain} is derived from a type name by a table of Java spellings, so a Ruby
     *     scenario that writes {@code type: string} or {@code type: bool} with no explicit domain would land
     *     on {@code RAW} and the generated literal would be a bare word that does not parse. The Ruby type
     *     names a scenario author would write are therefore read here as well, the same way the PHP
     *     generator reads the PHP ones.
     */
    private static Kind kind(Scenario.Input input) {
        return switch (input.domain()) {
            case INTEGER, LONG, INSTANT_MILLIS -> Kind.INTEGER;
            case DOUBLE, DECIMAL -> Kind.DOUBLE;
            case BOOLEAN -> Kind.BOOLEAN;
            case STRING -> Kind.STRING;
            case NULL -> Kind.OTHER;
            case RAW -> switch (input.type().trim().toLowerCase(Locale.ROOT)) {
                case "int", "integer", "fixnum", "bignum" -> Kind.INTEGER;
                case "float", "double", "rational", "bigdecimal" -> Kind.DOUBLE;
                case "bool", "boolean", "trueclass", "falseclass" -> Kind.BOOLEAN;
                case "string", "symbol" -> Kind.STRING;
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
            case STRING -> rubyString(value);
            case OTHER -> rawValue(value);
        };
    }

    /**
     * @return the value a case uses when the input declares none, as a value rather than as source text:
     *     {@link #literal} is what turns it into a Ruby literal, so a string default is the empty string
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

    private static String drawExpression(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (input.domain()) {
            case DECIMAL, DOUBLE -> "cw_double(rnd, " + number(min, "0") + ", " + number(max, "1000") + ")";
            case INTEGER, LONG -> "cw_int(rnd, " + integer(min, "0") + ", " + integer(max, "10000") + ")";
            case INSTANT_MILLIS -> "cw_int(rnd, " + integer(min, "0") + ", " + integer(max, "86400000") + ")";
            case BOOLEAN -> "cw_bool(rnd)";
            case STRING -> "cw_string(rnd)";
            case NULL -> "nil";
            case RAW -> rawDraw(input);
        };
    }

    /** @return the draw for an input whose domain is unknown, using the Ruby type name the scenario gave. */
    private static String rawDraw(Scenario.Input input) {
        String min = input.min();
        String max = input.max();
        return switch (kind(input)) {
            case INTEGER -> "cw_int(rnd, " + (min == null ? "0" : min) + ", " + (max == null ? "10000" : max) + ")";
            case DOUBLE -> "cw_double(rnd, " + (min == null ? "0" : min) + ", " + (max == null ? "1000" : max) + ")";
            case BOOLEAN -> "cw_bool(rnd)";
            case STRING -> "cw_string(rnd)";
            case OTHER -> input.values().isEmpty() ? "nil" : literal(input, input.values().getFirst());
        };
    }

    /** @return the text as a Ruby integer literal, or {@code fallback} when it is not one. */
    private static String integer(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.matches("[+-]?\\d+") ? trimmed : fallback;
    }

    /** @return the text as a Ruby numeric literal, or {@code fallback} when it is not one. */
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

    /**
     * @param value the scenario's own text for a value that is not one of the declared scalar kinds
     * @return Ruby source for it: a Java map spelling is turned back into a Ruby hash literal, and anything
     *     else is written exactly as the scenario wrote it, with nothing written rendering as {@code nil}.
     *
     * <p>Why a map needs turning back, and a list does not: the configuration loader flattens a
     *     {@code values} entry that is a YAML collection with {@code String.valueOf}, so a map arrives here
     *     as Java's own spelling of it ({@code {amount=1000}}), which is not Ruby at all. A list needs no
     *     such help, because Java spells one {@code [1, 2]} and that is already a valid Ruby array literal.
     *     The limit is stated rather than hidden: a list of <em>strings</em> arrives as bare words
     *     ({@code [a, b]}), and that spelling is genuinely ambiguous with a Ruby array of method calls, so
     *     it is left as it came instead of being guessed at. A scenario that needs either shape should
     *     prefer scalar inputs, which the configuration can carry exactly.
     */
    private static String rawValue(String value) {
        if (value.isBlank()) {
            return "nil";
        }
        String hash = rubyHash(value);
        return hash == null ? value : hash;
    }

    /**
     * @param value the scenario's raw text, possibly Java's spelling of a map
     * @return a Ruby hash literal, or {@code null} when the text is not a Java map spelling this generator
     *     can read back unambiguously
     *
     * <p>A hash is written as {@code 'key' => value} rather than in the label shorthand {@code key: value}:
     *     the shorthand makes a <em>symbol</em> key, and a map that came from YAML had string keys, so the
     *     shorthand would silently change the value the scenario asked for. Ruby 3.3, which is the version
     *     this adapter targets, has no quoted-label form that would keep a string key.
     *
     * <p>Anything it cannot read back confidently is refused rather than approximated: a pair with no
     *     {@code =}, and a value containing a comma - which Java's unquoted spelling leaves ambiguous with
     *     the separator - both return {@code null}, and the caller writes the scenario's text unchanged.
     */
    private static String rubyHash(String value) {
        String text = value.trim();
        if (!text.startsWith("{") || !text.endsWith("}") || text.contains("=>")) {
            return null;
        }
        String body = text.substring(1, text.length() - 1).trim();
        if (body.isEmpty()) {
            return null;
        }
        List<String> pairs = splitTopLevel(body);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.size(); i++) {
            String pair = pairs.get(i).trim();
            int equals = separator(pair);
            if (equals <= 0 || equals == pair.length() - 1) {
                return null;
            }
            if (i > 0) {
                sb.append(", ");
            }
            String element = pair.substring(equals + 1).trim();
            String nested = rubyHash(element);
            sb.append(rubyString(pair.substring(0, equals).trim())).append(" => ")
                    .append(nested == null ? element : nested);
        }
        return sb.append('}').toString();
    }

    /** @return the body split on the commas that sit outside every bracket and brace. */
    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    /**
     * @return the index of the {@code =} that separates one map entry's key from its value, or {@code -1}
     *     when the entry does not have one or is a Ruby label hash rather than a Java map spelling
     */
    private static int separator(String pair) {
        int depth = 0;
        for (int i = 0; i < pair.length(); i++) {
            char c = pair.charAt(i);
            if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                depth--;
            } else if (c == '=' && depth == 0) {
                return i;
            } else if (c == ':' && depth == 0) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * @return a Ruby single-quoted string literal.
     *
     * <p>Single quotes because a single-quoted Ruby string interpolates nothing: a scenario value that
     *     contains {@code #{...}} stays the text the scenario wrote instead of being evaluated inside the
     *     generated file. Only the backslash and the quote itself need escaping there, exactly as in PHP.
     *     A value that contains a line break is written verbatim; a single-quoted literal may span lines, so
     *     the file still parses.
     */
    static String rubyString(String value) {
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

    static List<Map<String, String>> boundaryCases(List<Scenario.Input> inputs) {

        // One implementation, in support: fourteen copies of this existed and three of them

        // truncated differently, which changes which experiments run and therefore what the

        // same scenario verifies.

        return dev.codewitness.support.generate.ScenarioCases.boundaryCases(inputs);

    }

    private static String inputsLiteral(List<Scenario.Input> inputs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < inputs.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('[').append(rubyString(inputs.get(i).name()))
                    .append(", cw_text(").append(inputs.get(i).name()).append(")]");
        }
        return sb.append(']').toString();
    }

    /**
     * Replaces every {@code ${name}} with the Ruby local variable {@code name}, which is the whole DSL.
     *
     * <p>The placeholder is {@code ${name}} and not {@code #{name}}: {@code #{...}} is string
     *     interpolation in Ruby, so a fragment that built a message out of an input would be silently
     *     rewritten, while {@code ${...}} is not valid Ruby anywhere and cannot collide with what the
     *     fragment meant to write.
     */
    private static String substitute(String fragment, List<Scenario.Input> inputs) {
        String result = fragment;
        for (Scenario.Input input : inputs) {
            result = result.replace("${" + input.name() + "}", input.name());
        }
        return result;
    }

    /** Indents a block of user-authored statements so it fits the generated example body. */
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

    /**
     * @param className the generated spec's reported identifier
     * @return the path a reader should open, for the header comment of the generated file
     */
    private String displayPath(String className) {
        return className.replace('.', '/') + "." + profile.extension();
    }
}

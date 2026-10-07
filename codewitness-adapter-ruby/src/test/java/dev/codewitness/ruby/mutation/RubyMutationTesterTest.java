package dev.codewitness.ruby.mutation;

import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.ruby.RubyInterpreter;
import dev.codewitness.ruby.RubyTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation tester's contract for the outcomes that need no interpreter.
 *
 * <p>Nothing here spawns a process: the parts that do - writing a mutant, running RSpec over it, deciding
 * whether it was killed - are covered by the lexis tests and by the end-to-end fixture, and a test that
 * needed Ruby installed to assert "the interpreter probe failed" would be asserting the machine rather
 * than the adapter. What the tests are really about is the difference the whole profile exists for: a
 * mutant the runner could not load or parse is not a kill, and a run that cannot judge any mutant is a
 * reason rather than a score of zero.
 */
class RubyMutationTesterTest {

    @Test
    @DisplayName("the tester names itself as the report tool the adapter documents and claims only Ruby")
    void namesItselfAndClaimsOnlyRuby(@TempDir Path root) {
        RubyMutationTester tester = new RubyMutationTester();

        assertEquals("ruby-mutation", tester.id());
        assertTrue(tester.supports(RubyTestContexts.projectInfo(root)));
        assertFalse(tester.supports(ProjectInfo.builder(ProjectRef.of(root))
                        .language(LanguageId.JAVA)
                        .build()),
                "a Java project's mutation is the Java adapter's to measure");
    }

    @Test
    @DisplayName("a project with no Ruby test root is not measured, never a score over a suite that cannot run")
    void aProjectWithoutATestRootIsNotMeasured(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        VerificationContext context = context(root, run);

        MutationReport report = new RubyMutationTester().analyse(context);

        assertFalse(report.measured(), report.describe());
        assertTrue(report.note().contains("no Ruby test source directory"),
                "without a test tree the budget spent mutating could only produce survivors: " + report.note());
        assertEquals(0, report.generated(), "no mutant may be counted when none could be judged");
        assertEquals(0, report.killed(), report.describe());
    }

    @Test
    @DisplayName("a failed interpreter probe is reported before anything else, even for an otherwise runnable project")
    void aFailedInterpreterProbeIsReportedRatherThanMutated(@TempDir Path root, @TempDir Path run)
            throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(root, "spec/ledger_spec.rb", "# the suite under test\n");
        RubyTestContexts.write(root, "bin/rspec", "#!/usr/bin/env ruby\n");
        VerificationContext context = context(root, run,
                Map.of(RubyInterpreter.ATTR_PROBE_FAILURE, "ruby: not found"));

        String problem = RubyMutationTester.manifestProblem(context);
        assertNotNull(problem);
        assertTrue(problem.contains("interpreter probe failed"), problem);
        assertTrue(problem.contains("ruby: not found"),
                "the reason must carry the interpreter's own words: " + problem);

        MutationReport report = new RubyMutationTester().analyse(context);

        assertFalse(report.measured(), report.describe());
        assertTrue(report.note().contains("interpreter probe failed"), report.note());
        assertEquals(0, report.generated(), "no mutant may be counted when none could be parsed or run");
    }

    @Test
    @DisplayName("a manifest problem is reported rather than mutated, and the test tree is refused before the runner")
    void aManifestProblemIsReportedRatherThanMutated(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        // A spec directory that holds no Ruby source is not a test tree, and the Gemfile is what makes
        // RSpec resolvable through Bundler - so the refusal below is about the missing tests alone and not
        // about a missing runner.
        RubyTestContexts.write(root, "spec/README.md", "notes, not specs\n");
        RubyTestContexts.write(root, "Gemfile", "source 'https://rubygems.org'\n");
        VerificationContext context = context(root, run);

        assertFalse(RubyInterpreter.rspec(context.projectInfo(), context).isEmpty(),
                "the Gemfile makes bundle exec rspec available, so the refusal is not about the runner");

        String problem = RubyMutationTester.manifestProblem(context);
        assertNotNull(problem, "a spec tree with no Ruby source cannot run a test");
        assertTrue(problem.contains("no Ruby test source directory"), problem);

        MutationReport report = new RubyMutationTester().analyse(context);

        assertFalse(report.measured(), report.describe());
        assertEquals(0, report.generated());
    }

    @Test
    @DisplayName("the mutant's test command is the RSpec invocation over the detected roots, with the progress formatter")
    void theTestCommandIsTheRSpecInvocation(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(root, "spec/ledger_spec.rb", "# the suite under test\n");
        RubyTestContexts.write(root, "bin/rspec", "#!/usr/bin/env ruby\n");
        ProjectInfo project = RubyTestContexts.projectInfo(root);
        VerificationContext context = context(project, run);

        List<String> command = RubyMutationTester.testCommand(project, context);

        assertTrue(command.stream().anyMatch(part -> part.endsWith("bin/rspec")),
                "the mutant is judged by the project's own pinned RSpec: " + command);
        assertTrue(command.contains("spec"),
                "the test roots come from the detector's report rather than being assumed: " + command);
        assertEquals("--format", command.get(command.size() - 2), command.toString());
        assertEquals("progress", command.getLast(),
                "one character per example keeps a failing example's own message readable, and the shared "
                        + "loop decides from the exit status: " + command);
        assertNull(RubyMutationTester.manifestProblem(context),
                "a test tree and a binstub are exactly what the run needs, so nothing refuses it");
    }

    @Test
    @DisplayName("a detected test root that is no longer on disk is left out of the command")
    void aTestRootThatIsGoneIsLeftOut(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(root, "spec/ledger_spec.rb", "# the suite under test\n");
        RubyTestContexts.write(root, "bin/rspec", "#!/usr/bin/env ruby\n");
        ProjectInfo project = RubyTestContexts.projectInfo(root);
        // The report still names spec/, but handing RSpec a path it cannot find makes it fail before any
        // mutant is judged, which would score every mutant as non-viable.
        Files.delete(root.resolve("spec/ledger_spec.rb"));
        Files.delete(root.resolve("spec"));

        List<String> command = RubyMutationTester.testCommand(project, context(project, run));

        assertFalse(command.contains("spec"),
                "a root that does not exist on disk is left out rather than handed to RSpec: " + command);
        assertTrue(command.stream().anyMatch(part -> part.endsWith("bin/rspec")), command.toString());
        assertEquals("progress", command.getLast(), command.toString());
    }

    @Test
    @DisplayName("a mutant the runner could not parse, load or find a test for is non-viable, never a kill")
    void nonViableRunsAreNotKills() {
        assertNotNull(RubyMutationTester.nonViableReason(null),
                "a run that printed nothing observed nothing");
        assertNotNull(RubyMutationTester.nonViableReason(""));

        String syntax = RubyMutationTester.nonViableReason(
                "spec/ledger_spec.rb:3: syntax error, unexpected end-of-input, expecting `end'");
        assertNotNull(syntax,
                "Ruby has no compile step, so an unparseable mutant fails at load time with a non-zero exit "
                        + "status - exactly like a real test failure");
        assertTrue(syntax.contains("did not parse"), syntax);

        String load = RubyMutationTester.nonViableReason(
                "/usr/bin/ruby: cannot load such file -- ledger (LoadError)");
        assertNotNull(load, "a project that could not be loaded was never tested");
        assertTrue(load.contains("could not load the project"), load);

        String noExamples = RubyMutationTester.nonViableReason("0 examples, 0 failures");
        assertNotNull(noExamples, "a suite that discovered nothing says nothing about the mutant");
        assertTrue(noExamples.contains("no example was discovered"), noExamples);

        assertNull(RubyMutationTester.nonViableReason("3 examples, 1 failure\n\nFailures:\n  1) Ledger fails"),
                "a suite that ran and failed is a kill decided by the exit status, not a non-viable mutant");
    }

    @Test
    @DisplayName("the project's own sources are mutated and its tests are not")
    void theTestPathRuleKeepsTheOracleOut() {
        assertTrue(RubyMutationTester.isTestPath("spec/ledger_spec.rb"));
        assertTrue(RubyMutationTester.isTestPath("lib/ledger_spec.rb"),
                "RSpec's naming convention answers it for a flat layout too");
        assertTrue(RubyMutationTester.isTestPath("lib/ledger_test.rb"),
                "minitest's convention does as well");
        assertTrue(RubyMutationTester.isTestPath("spec/ledger.rb"),
                "the conventional directory answers it when the file is not named like a spec");
        assertTrue(RubyMutationTester.isTestPath("test/ledger.rb"));
        assertFalse(RubyMutationTester.isTestPath("lib/ledger.rb"),
                "mutating the oracle would measure the test suite against itself");
        assertFalse(RubyMutationTester.isTestPath("lib/testing.rb"),
                "a name is not a directory element, so an ordinary file called testing.rb is source");
    }

    // ------------------------------------------------------------------ helpers

    private static VerificationContext context(Path root, Path run) {
        return context(root, run, Map.of());
    }

    /** @return a context whose project declares an interpreter that does not exist on this machine */
    private static VerificationContext context(Path root, Path run, Map<String, String> attributes) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY);
        merged.putAll(attributes);
        return RubyTestContexts.context(RubyTestContexts.withAttributes(root, merged), run, List.of(),
                new LocalProcessSandbox(List.of(root, run)));
    }

    private static VerificationContext context(ProjectInfo info, Path run) {
        return RubyTestContexts.context(info, run, List.of(),
                new LocalProcessSandbox(List.of(info.project().root(), run)));
    }
}

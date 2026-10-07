package dev.codewitness.ruby;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the syntax checker's structured diagnostics, and the build results around them.
 *
 * <p>The syntax check runs the interpreter's own parser through {@link RubyScripts#LINT}, so a case that
 * would otherwise need Ruby installed is driven through a substituted process runner that returns the
 * checker's own line protocol. What the tests assert is the contract around it: only a parse failure fails
 * the build, a tree with no Gemfile never reaches Bundler, and a diagnostic about somebody else's file is
 * kept at the path the checker printed rather than rebased onto this project.
 */
class RubyBuildProviderTest {

    @Test
    @DisplayName("CWDIAG lines become compiler diagnostics with a project-relative path and no column")
    void parsesDiagnostics() {
        Path root = Path.of("/project");
        String output = """
                CWDIAG|ERROR|/project/lib/ledger.rb|12|syntax error, unexpected `end', expecting end-of-input
                CWDIAG|WARNING|/project/other.rb|0|the file could not be checked (SystemCallError)
                CWSYNTAX|files=2|errors=1
                """;

        List<CompilerDiagnostic> diagnostics = RubyBuildProvider.parseDiagnostics(output, root);

        assertEquals(2, diagnostics.size());
        CompilerDiagnostic first = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.ERROR, first.severity());
        assertEquals("lib/ledger.rb", first.file());
        assertEquals(12, first.line());
        assertNull(first.column(), "the interpreter's parse error reports a line, not a column");
        assertEquals("syntax error, unexpected `end', expecting end-of-input", first.message());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity(),
                "a file that could not be read says nothing about the project's code");
        assertEquals("other.rb", diagnostics.get(1).file());
    }

    @Test
    @DisplayName("a truncated diagnostic line is dropped rather than given an invented location")
    void ignoresIncompleteAndUnrelatedOutput() {
        assertEquals(List.of(), RubyBuildProvider.parseDiagnostics(
                "CWDIAG|ERROR|/project/lib/broken.rb|12\n", Path.of("/project")));
        assertEquals(List.of(), RubyBuildProvider.parseDiagnostics(
                "ruby-lint.rb:12:in `<main>': undefined method\n", Path.of("/project")));
        assertEquals(List.of(), RubyBuildProvider.parseDiagnostics(null, Path.of("/project")));
    }

    @Test
    @DisplayName("only a path below the project root is rebased; another project's file keeps its path")
    void theProjectRootRebasesDiagnosticPaths() {
        String output = """
                CWDIAG|ERROR|/project/lib/ledger.rb|4|unexpected end-of-input
                CWDIAG|ERROR|/elsewhere/lib/dep.rb|7|syntax error, unexpected `)'
                CWDIAG|ERROR|lib/relative.rb|2|syntax error, unexpected `,'
                """;

        List<CompilerDiagnostic> diagnostics = RubyBuildProvider.parseDiagnostics(output, Path.of("/project"));

        assertEquals("lib/ledger.rb", diagnostics.get(0).file(),
                "a file of this project is reported relative to it, which is how a report points at it");
        assertEquals("/elsewhere/lib/dep.rb", diagnostics.get(1).file(),
                "a file outside the project is somebody else's code: rebasing it would attribute it to "
                        + "this project");
        assertEquals("lib/relative.rb", diagnostics.get(2).file(),
                "a path the checker already printed relative to the root is left as it is");
    }

    @Test
    @DisplayName("only Ruby is built by this provider")
    void supportsOnlyRuby(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        ProjectInfo info = RubyTestContexts.projectInfo(root);
        RubyBuildProvider provider = new RubyBuildProvider();

        assertEquals("ruby-build", provider.id());
        assertTrue(provider.supports(info));

        ProjectInfo java = ProjectInfo.builder(info.project())
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .build();
        assertFalse(provider.supports(java),
                "a Java project's build is the Java adapter's to run, not this one's");
    }

    @Test
    @DisplayName("a failed interpreter probe makes the build not attempted, so it can never be a code defect")
    void theProbeFailureIsAnInfrastructureProblem(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root, Map.of(
                        RubyInterpreter.ATTR_PROBE_FAILURE, "not started: IOException: no such file",
                        RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run);

        BuildResult result = new RubyBuildProvider().build(context);

        assertFalse(result.attempted(), "nothing was built, so nothing may be reported as built");
        assertFalse(result.success());
        assertTrue(result.failureReason().contains("interpreter probe failed"), result.failureReason());
    }

    @Test
    @DisplayName("a project with no Ruby source outside its tests has no build to attempt")
    void noSourcesMeansNothingToBuild(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root,
                        Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run);

        BuildResult result = new RubyBuildProvider().build(context);

        assertFalse(result.attempted());
        assertTrue(result.failureReason().contains("no Ruby source file"), result.failureReason());
    }

    @Test
    @DisplayName("an interpreter that cannot be started is not attempted, never a passed build")
    void aMissingInterpreterIsNotAPassedBuild(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        List<List<String>> commands = new ArrayList<>();
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root,
                        Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run, List.<Scenario>of(), sandbox(List.of(notStarted("no such file: "
                        + RubyTestContexts.MISSING_RUBY)), commands));

        BuildResult result = new RubyBuildProvider().build(context);

        assertFalse(result.attempted(), "no interpreter ran, so no build outcome was observed");
        assertTrue(result.failureReason() != null && !result.failureReason().isBlank());
        assertEquals(1, commands.size(), "and nothing was attempted after it: " + commands);
    }

    @Test
    @DisplayName("a project without a Gemfile is parsed but installs nothing, so Bundler never runs")
    void aProjectWithoutAGemfileDoesNotRunBundler(@TempDir Path root, @TempDir Path run) throws IOException {
        Path source = RubyTestContexts.write(root, "lib/ledger.rb",
                "class Ledger\n  def withdraw(amount)\n    amount\n  end\nend\n");
        List<List<String>> commands = new ArrayList<>();
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root,
                        Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run, List.<Scenario>of(), sandbox(List.of(succeeded("CWSYNTAX|files=1|errors=0\n")), commands));

        BuildResult result = new RubyBuildProvider().build(context);

        assertTrue(result.attempted());
        assertTrue(result.success(), result.describe());
        assertEquals(1, commands.size(), "there is nothing to install, so only the check may run: " + commands);
        List<String> lint = commands.getFirst();
        assertTrue(lint.contains(source.toString()),
                "the project's own file is what the checker is asked about: " + lint);
        assertTrue(String.join(" ", lint).contains("ruby-lint.rb"), lint.toString());
        assertFalse(String.join(" ", commands.stream().flatMap(List::stream).toList()).contains("install"),
                "a tree with no Gemfile has no bundle to install: " + commands);

        Path script = RubyScripts.lintScript(context);
        assertEquals(run.resolve("artifacts").resolve("ruby").resolve("ruby-lint.rb"), script,
                "the helper script belongs to the run, not to the project it measures");
        assertEquals(RubyScripts.LINT, Files.readString(script, StandardCharsets.UTF_8),
                "the command above ran exactly the script this class defines");
        assertFalse(Files.exists(root.resolve("ruby-lint.rb")),
                "a verifier must not leave its own files in somebody's working tree");
    }

    @Test
    @DisplayName("a Gemfile project is installed with the project's own Bundler, after the syntax check")
    void aGemfileProjectRunsBundleInstall(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        Path binstub = RubyTestContexts.write(root, "bin/" + bundlerName(), "#!/usr/bin/env ruby\n");
        List<List<String>> commands = new ArrayList<>();
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root,
                        Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run, List.<Scenario>of(),
                sandbox(List.of(succeeded("CWSYNTAX|files=1|errors=0\n"), succeeded("Bundle complete!\n")),
                        commands));

        BuildResult result = new RubyBuildProvider().build(context);

        assertTrue(result.attempted());
        assertTrue(result.success(), result.describe());
        assertEquals(2, commands.size(), "the check and the install, in that order: " + commands);
        assertTrue(String.join(" ", commands.get(0)).contains("ruby-lint.rb"),
                "a tree that does not parse must be reported before a bundle is installed into it, or the "
                        + "install's failure would be reported instead of the syntax error: " + commands);
        assertEquals(List.of(binstub.toString(), "install"), commands.get(1),
                "the project's own binstub is the Bundler to install with, and the command is never "
                        + "bundle update");
    }

    @Test
    @DisplayName("a parse error fails the build and the CWSYNTAX summary is part of the result")
    void aSyntaxErrorFailsTheBuild(@TempDir Path root, @TempDir Path run) throws IOException {
        Path source = RubyTestContexts.write(root, "lib/ledger.rb",
                "class Ledger\n  def withdraw(\nend\n");
        List<List<String>> commands = new ArrayList<>();
        String stdout = "CWDIAG|ERROR|" + source + "|2|syntax error, unexpected `end', expecting ')'\n"
                + "CWSYNTAX|files=1|errors=1\n";
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root,
                        Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run, List.<Scenario>of(),
                sandbox(List.of(new ProcessResult(List.of(), null, true, 1, stdout, "",
                        Duration.ofMillis(120), false, null)), commands));

        BuildResult result = new RubyBuildProvider().build(context);

        assertTrue(result.attempted());
        assertFalse(result.success(), "a file the interpreter cannot parse is a first failure, not a pass");
        assertEquals(1, result.errors().size(), result.diagnostics().toString());
        assertEquals("lib/ledger.rb", result.errors().getFirst().file());
        assertEquals(2, result.errors().getFirst().line());
        assertTrue(result.output().contains("CWSYNTAX|files=1|errors=1"),
                "the checker's summary is recorded in the build result, so a report can say how much "
                        + "was checked rather than only what failed: " + result.output());
        assertEquals(1, commands.size(), "a tree that does not parse was never installed into: " + commands);
    }

    @Test
    @DisplayName("the helper script speaks the CWDIAG/CWSYNTAX line protocol and exits non-zero only on errors")
    void theScriptSpeaksTheLineProtocol() {
        assertTrue(RubyScripts.LINT.contains("CWDIAG|#{severity}|#{file}|#{line}|#{message}"),
                "the Java side parses facts rather than prose");
        assertTrue(RubyScripts.LINT.contains("CWSYNTAX|files=#{files.size}|errors=#{errors}"),
                RubyScripts.LINT);
        assertTrue(RubyScripts.LINT.contains("RubyVM::InstructionSequence.compile_file(file)"),
                "the check uses the interpreter's own parser, so the diagnostics are exact");
        assertTrue(RubyScripts.LINT.contains("exit(errors > 0 ? 1 : 0)"),
                "a non-zero exit means the project does not parse and never that the checker could not "
                        + "run: an unreadable file is a warning");
        assertTrue(RubyScripts.LINT.contains("vendor") && RubyScripts.LINT.contains("coverage")
                        && RubyScripts.LINT.contains("tmp") && RubyScripts.LINT.contains("log"),
                "the script skips the same tool directories the Java side skips");
    }

    /** @return a successful process outcome carrying {@code stdout}. */
    private static ProcessResult succeeded(String stdout) {
        return new ProcessResult(List.of(), null, true, 0, stdout, "", Duration.ZERO, false, null);
    }

    /** @return Bundler's executable name on this platform, so the binstub is the one the adapter looks for. */
    private static String bundlerName() {
        return dev.codewitness.support.Platform.isWindows() ? "bundle.bat" : "bundle";
    }

    /** @return an outcome for a process that could not be started at all. */
    private static ProcessResult notStarted(String reason) {
        return ProcessResult.notStarted(List.of(), null, reason);
    }

    /**
     * A sandbox whose runner returns the scripted outcomes in order and records every argv.
     *
     * <p>Nothing needs Ruby, Bundler or RSpec to be installed: the checker's output is supplied rather than
     * produced, and the command that would have produced it is asserted separately. Each result is
     * re-bound to the command that was actually requested, so a test can never pass by matching an outcome
     * to the wrong run.
     */
    private static ExecutionSandbox sandbox(List<ProcessResult> scripted, List<List<String>> commands) {
        return new ExecutionSandbox() {
            private int index;

            @Override
            public String id() {
                return "test-sandbox";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: answers with a scripted outcome and starts nothing";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    commands.add(spec.command());
                    ProcessResult next = scripted.get(Math.min(index, scripted.size() - 1));
                    index++;
                    return new ProcessResult(spec.command(), spec.workingDirectory(), next.started(),
                            next.exitCode(), next.stdout(), next.stderr(), next.duration(),
                            next.timedOut(), next.failureReason());
                };
            }
        };
    }
}

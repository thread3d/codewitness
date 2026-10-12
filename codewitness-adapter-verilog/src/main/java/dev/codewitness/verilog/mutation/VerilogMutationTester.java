package dev.codewitness.verilog.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;
import dev.codewitness.verilog.VerilogModuleParser;
import dev.codewitness.verilog.VerilogProjectDetector;
import dev.codewitness.verilog.VerilogTestCommand;
import dev.codewitness.verilog.VerilogTool;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mutation testing for Verilog using a bounded source mutator.
 *
 * <p>Verilog has no universally installed mutation tool, and the academic ones are research artifacts
 * rather than something a project can be expected to have. The adapter therefore mutates the source
 * text itself through the shared mutator and judges each mutant by recompiling it and simulating the
 * project's own testbenches: a mutant the benches detect is killed, one they do not is a survivor.
 *
 * <p>That makes the test command a two-step pipeline per mutant, which is why it is supplied as a
 * shell script through {@link VerilogTestCommand}: the shared tester runs one command per mutant and
 * knows nothing about Icarus. A mutant whose compile fails is non-viable and is excluded from the
 * score rather than counted as a kill the benches never made - an Icarus error line is the signal for
 * that, exactly as a Go build failure is for that adapter.
 *
 * <p>The run is bounded by the shared tester's mutant cap and wall-clock budget, and every source file
 * is restored in a {@code finally} block, so a crash cannot leave a mutated design behind. Surviving
 * mutants never change a verdict: they are evidence and a run limitation.
 */
public final class VerilogMutationTester implements VerificationTools.MutationTester {

    private final CommandMutationTester delegate = new CommandMutationTester(new VerilogMutationProfile());

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        return delegate.analyse(context);
    }

    /** The Verilog half of the shared mutation loop: its testbench check, its sources, its command, its lexis. */
    static final class VerilogMutationProfile implements CommandMutationProfile {

        /** An Icarus compile failure, in the shapes the toolchain prints, or a missing toolchain. */
        private static final Pattern COMPILE_FAILURE = Pattern.compile(
                "(?i):\\s*(error|sorry):|\\bsyntax error\\b|Elaboration failed|command not found|"
                        + "iverilog: not found|iverilog: No such file");

        @Override
        public String languageName() {
            return "Verilog";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.VERILOG;
        }

        @Override
        public String mutationTesterId() {
            return "verilog-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "verilog-text";
        }

        @Override
        public LanguageLexis lexis() {
            return VerilogLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            return VerilogProjectDetector.testbenches(context.projectRoot()).isEmpty()
                    ? "no testbench module was found, so a mutant cannot be judged by a simulation"
                    : null;
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return VerilogProjectDetector.sourceFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            if (relative == null) {
                return false;
            }
            String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
            String name = normalized.contains("/")
                    ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            if (VerilogModuleParser.isTestbenchName(stem(name))) {
                return true;
            }
            for (String element : normalized.split("/")) {
                if (element.equals("tb") || element.equals("testbench") || element.equals("testbenches")
                        || element.equals("test") || element.equals("tests") || element.equals("sim")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<String> testCommand(ProjectInfo project, VerificationContext context) {
            return VerilogTestCommand.forProject(context);
        }

        @Override
        public Map<String, String> environment(VerificationContext context) {
            return VerilogTool.environment(context);
        }

        @Override
        public String nonViableReason(String output) {
            if (output == null || output.isBlank()) {
                return null;
            }
            return COMPILE_FAILURE.matcher(output).find() ? VerilogTool.lastLines(output, 3) : null;
        }

        @Override
        public String operatorSetDescription() {
            return "The operator set is deliberately small (arithmetic, comparison boundaries, equality, "
                    + "boolean and bitwise operators and the shifts), and only operators surrounded by "
                    + "whitespace and by value expressions are considered; the nonblocking assignment and "
                    + "comparison operator <= is a site, and based literals are left whole.";
        }

        private static String stem(String fileName) {
            int dot = fileName.lastIndexOf('.');
            return dot < 0 ? fileName : fileName.substring(0, dot);
        }
    }
}

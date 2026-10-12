package dev.codewitness.vhdl.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;
import dev.codewitness.vhdl.VhdlProjectDetector;
import dev.codewitness.vhdl.VhdlSourceParser;
import dev.codewitness.vhdl.VhdlTestCommand;
import dev.codewitness.vhdl.VhdlTool;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mutation testing for VHDL using a bounded source mutator.
 *
 * <p>VHDL has no universally installed mutation tool, and the academic ones are research artifacts
 * rather than something a project can be expected to have. The adapter therefore mutates the source
 * text itself through the shared mutator and judges each mutant by re-analysing it and simulating the
 * project's own testbenches: a mutant the benches detect is killed, one they do not is a survivor.
 *
 * <p>That makes the test command a multi-step pipeline per mutant, which is why it is supplied as a
 * shell script through {@link VhdlTestCommand}: the shared tester runs one command per mutant and knows
 * nothing about GHDL. A mutant GHDL rejects is non-viable and is excluded from the score rather than
 * counted as a kill the benches never made - a GHDL error line is the signal for that, exactly as an
 * Icarus error line is for the Verilog adapter.
 *
 * <p>The run is bounded by the shared tester's mutant cap and wall-clock budget, and every source file
 * is restored in a {@code finally} block, so a crash cannot leave a mutated design behind. Surviving
 * mutants never change a verdict: they are evidence and a run limitation.
 */
public final class VhdlMutationTester implements VerificationTools.MutationTester {

    private final CommandMutationTester delegate = new CommandMutationTester(new VhdlMutationProfile());

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

    /** The VHDL half of the shared mutation loop: its testbench check, its sources, its command, its lexis. */
    static final class VhdlMutationProfile implements CommandMutationProfile {

        /**
         * A GHDL analysis or elaboration failure, in the shapes the toolchain prints, or a missing
         * toolchain.
         *
         * <p>The pattern is deliberately narrow. GHDL prints both a rejected mutant and a mutant whose
         * simulation then failed as {@code ghdl:error:}, so a bare {@code ghdl:error:} cannot be the
         * test: it would classify a killed mutant as non-viable and hide the kill. Only a file-anchored
         * analysis diagnostic and an elaboration failure are non-viable; {@code assertion failed},
         * {@code report failed} and {@code simulation failed} are the tests detecting the mutant, which
         * is a kill.
         */
        private static final Pattern COMPILE_FAILURE = Pattern.compile(
                "(?i)\\.(?:vhd|vhdl):\\d+(?::\\d+)?:\\s*error:"
                        + "|ghdl:error:\\s*elaboration"
                        + "|ghdl:error:\\s*(cannot|unknown|no such|option|missing)"
                        + "|not found|No such file|cannot find");

        @Override
        public String languageName() {
            return "VHDL";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.VHDL;
        }

        @Override
        public String mutationTesterId() {
            return "vhdl-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "vhdl-text";
        }

        @Override
        public LanguageLexis lexis() {
            return VhdlLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            return VhdlProjectDetector.testbenches(context.projectRoot()).isEmpty()
                    ? "no testbench entity was found, so a mutant cannot be judged by a simulation"
                    : null;
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return VhdlProjectDetector.sourceFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            if (relative == null) {
                return false;
            }
            String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
            String name = normalized.contains("/")
                    ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            if (VhdlSourceParser.isTestbenchName(VhdlSourceParser.stem(name))) {
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
            return VhdlTestCommand.forProject(context);
        }

        @Override
        public Map<String, String> environment(VerificationContext context) {
            return VhdlTool.environment(context);
        }

        @Override
        public String nonViableReason(String output) {
            if (output == null || output.isBlank()) {
                return null;
            }
            return COMPILE_FAILURE.matcher(output).find() ? VhdlTool.lastLines(output, 3) : null;
        }

        @Override
        public String operatorSetDescription() {
            return "The operator set is deliberately small (arithmetic, comparison and equality operators), "
                    + "and only operators surrounded by whitespace and by value expressions are considered; the "
                    + "signal assignment <=, the variable assignment := and the association => are consumed "
                    + "whole and left unmutated, and based literals are left whole.";
        }
    }
}

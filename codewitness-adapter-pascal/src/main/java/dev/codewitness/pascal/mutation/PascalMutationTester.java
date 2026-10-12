package dev.codewitness.pascal.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.pascal.PascalProjectDetector;
import dev.codewitness.pascal.PascalSourceParser;
import dev.codewitness.pascal.PascalTestCommand;
import dev.codewitness.pascal.PascalTestProvider;
import dev.codewitness.pascal.PascalTool;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mutation testing for Pascal: a mutant of one source file is kept alive only if the project's own test
 * suite still passes, and a mutant the suite kills is evidence that the suite constrains that behaviour.
 *
 * <p>The mutation loop, its bounds and its restore guarantee are the shared command tester's; what is
 * Pascal's arrives here as a profile - which files are the code under test, how the compiler reports a
 * mutant it cannot build, and how the operator set reads in a report.
 */
public final class PascalMutationTester implements VerificationTools.MutationTester {

    private final CommandMutationTester delegate = new CommandMutationTester(new PascalMutationProfile());

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

    /**
     * What the shared command mutation tester needs to know about Pascal.
     *
     * <p>A compile failure is the compiler's own wording - {@code Fatal:} or {@code Error:} at a source
     * location - and it means the mutant is non-viable rather than killed: it never became a program the
     * tests could judge. A test failure, by contrast, is the tests running and rejecting the mutant, which
     * is what a killed mutant is.
     */
    static final class PascalMutationProfile implements CommandMutationProfile {

        private static final Pattern COMPILE_FAILURE = Pattern.compile(
                "(?i)(\\(\\d+[,)]\\s*)?(fatal|error):\\s|can't find unit|command not found|fpc: not found|"
                        + "no such file or directory");

        @Override
        public String languageName() {
            return "Pascal";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.PASCAL;
        }

        @Override
        public String mutationTesterId() {
            return "pascal-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "pascal-text";
        }

        @Override
        public LanguageLexis lexis() {
            return PascalLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            if (PascalTestProvider.runners(context).isEmpty()) {
                return "no Pascal test program could be found, so a mutant has nothing to fail against";
            }
            return null;
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return PascalProjectDetector.sourceFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            String normalized = relative.replace('\\', '/');
            String stem = PascalSourceParser.stem(Path.of(normalized));
            if (PascalSourceParser.isTestName(stem)) {
                return true;
            }
            int slash = normalized.lastIndexOf('/');
            while (slash > 0) {
                String element = normalized.substring(0, slash);
                int next = element.lastIndexOf('/');
                String directory = next < 0 ? element : element.substring(next + 1);
                if (PascalSourceParser.isTestDirectory(directory)) {
                    return true;
                }
                slash = next;
            }
            return false;
        }

        @Override
        public List<String> testCommand(ProjectInfo project, VerificationContext context) {
            return PascalTestCommand.forProject(context);
        }

        @Override
        public Map<String, String> environment(VerificationContext context) {
            return PascalTool.environment(context);
        }

        @Override
        public String nonViableReason(String output) {
            if (output != null && COMPILE_FAILURE.matcher(output).find()) {
                return PascalTool.lastLines(output, 3);
            }
            return null;
        }

        @Override
        public String operatorSetDescription() {
            return "Arithmetic, comparison, equality, boolean, integer-division and shift operators are "
                    + "flipped one at a time (+ to -, * to /, < to <=, > to >=, = to <>, and to or, div to mod "
                    + "and shl to shr), each whitespace-surrounded, with comments and string literals left whole.";
        }

        /** @return {@code true} when the path is under a conventional test directory. */
        static boolean isTestDirectoryName(String name) {
            return PascalSourceParser.isTestDirectory(name.toLowerCase(Locale.ROOT));
        }
    }
}

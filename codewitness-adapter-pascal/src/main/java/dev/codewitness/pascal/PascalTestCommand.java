package dev.codewitness.pascal;

import dev.codewitness.core.spi.VerificationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the one shell command that compiles and runs a project's Pascal test suite.
 *
 * <p>The mutation tester needs a single command whose exit status says whether a mutant's tests passed,
 * and a Pascal test is inherently two processes: the compiler links the test program, then the program
 * runs. This class states that pipeline once, in the form a shell can execute, so the mutation profile
 * and a reproduction command cannot disagree about what running the tests means.
 */
public final class PascalTestCommand {

    private PascalTestCommand() {
    }

    /**
     * @param context run context, providing the resolved compiler and the artifact directory
     * @return a shell invocation that compiles and runs every test program, or an empty list when the
     *     project declares none
     */
    public static List<String> forProject(VerificationContext context) {
        List<PascalTestProvider.Runner> runners = PascalTestProvider.runners(context);
        if (runners.isEmpty()) {
            return List.of();
        }
        String fpc = PascalTool.fpc(context.projectInfo(), context);
        StringBuilder script = new StringBuilder("set -e\n");
        for (PascalTestProvider.Runner runner : runners) {
            var executable = PascalTestProvider.testExecutable(context, runner.name());
            List<String> compile = PascalBuildProvider.compileCommand(
                    fpc, context.projectRoot(), context, runner.file(), executable, true);
            script.append(join(compile)).append('\n');
            script.append(quote(executable.toString()));
            if (runner.consoleRunner()) {
                script.append(" --all --no-addresses");
            }
            script.append('\n');
        }
        return shell(script.toString());
    }

    /** @return the command that runs a project's tests, wrapped for the host shell. */
    public static List<String> shell(String script) {
        return PascalTool.isWindows() ? List.of("cmd", "/c", script) : List.of("/bin/sh", "-c", script);
    }

    /** @return the value quoted for the host shell. */
    public static String quote(String value) {
        if (PascalTool.isWindows()) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String join(List<String> command) {
        List<String> quoted = new ArrayList<>();
        for (String part : command) {
            quoted.add(quote(part));
        }
        return String.join(" ", quoted);
    }
}

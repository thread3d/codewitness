package dev.codewitness.cli;

import dev.codewitness.core.engine.VerificationEngine;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CodeWitness command line entry point.
 *
 * <p>CodeWitness is an evidence engine, not an oracle: it assembles deterministic observations about a
 * project and reports what they do and do not support. Commands:
 * <pre>
 * codewitness verify   &lt;project&gt; [--requirements requirements.md]
 * codewitness inspect  &lt;project&gt;
 * codewitness test     &lt;project&gt;
 * codewitness report   &lt;run-id&gt;
 * codewitness reproduce &lt;run-id&gt; [--counterexample CE-001]
 * codewitness clean    &lt;project&gt;
 * codewitness init     &lt;project&gt;
 * codewitness version
 * </pre>
 */
@Command(
        name = "codewitness",
        mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        description = "Independent verification engine for AI-generated software.",
        subcommands = {
                VerifyCommand.class,
                InspectCommand.class,
                TestCommand.class,
                ReportCommand.class,
                ReproduceCommand.class,
                CleanCommand.class,
                InitCommand.class,
                VersionCommand.class
        })
public final class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /**
     * Runs the CLI and returns its exit code.
     *
     * <p>Exit codes are part of the documented interface: {@code 3} means "the command line or the
     * configuration could not be used". picocli's default for a bad parameter is {@code 2}, which
     * collides with "verification was BLOCKED", and an error thrown by a command was rethrown wrapped
     * in a picocli {@code ExecutionException}, which used to escape as a stack trace. Both are mapped to
     * {@code 3} here so that the documented table is what the program actually does.
     *
     * <p>The unwrapping matters: catching {@code IllegalArgumentException} around the call is not
     * enough, because picocli wraps whatever a command throws. A configuration mistake is a statement
     * about how the tool was invoked, not a bug in it, and it must read as one.
     */
    public static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setParameterExceptionHandler((exception, commandArgs) -> {
            System.err.println("codewitness: " + exception.getMessage());
            System.err.println("Run 'codewitness <command> --help' for usage.");
            return 3;
        });
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof IllegalArgumentException) {
                System.err.println("codewitness: " + cause.getMessage());
                return 3;
            }
            System.err.println("codewitness: internal failure: " + cause);
            cause.printStackTrace(System.err);
            return 3;
        });
        return commandLine.execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** Supplies {@code --version} output. */
    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {
                    "codewitness " + VerificationEngine.VERSION,
                    "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                    "https://github.com/codewitness/codewitness"
            };
        }
    }
}

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

    /** Runs the CLI and returns its exit code. */
    public static int execute(String... args) {
        return new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true).execute(args);
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

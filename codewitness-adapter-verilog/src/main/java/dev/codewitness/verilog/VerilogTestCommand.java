package dev.codewitness.verilog;

import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Builds the one command that compiles a Verilog tree and simulates every testbench in it.
 *
 * <p>A Verilog test is two processes - {@code iverilog} to compile a testbench as the root and
 * {@code vvp} to run it - and some callers need that as a single command: the shared mutation tester
 * runs exactly one command per mutant, because it knows nothing about any language's toolchain. This
 * class supplies that command as a shell invocation ({@code sh -c '...'} on POSIX, {@code cmd /c} on
 * Windows) whose script compiles each testbench and runs it, stopping at the first failure.
 *
 * <p>{@code vvp -N} is deliberate: without it {@code $stop} opens an interactive prompt, and a mutant
 * that reaches one would hang the mutation run rather than be scored. Every path is single-quoted, so
 * a project directory with a space in it is still compiled from the right place.
 */
public final class VerilogTestCommand {

    /** Subdirectory of the run's artefact store the compiled simulations are written into. */
    private static final String RUN_DIRECTORY = "verilog/runs";

    private VerilogTestCommand() {
    }

    /**
     * @return the shell command that compiles and simulates every testbench, or an empty list when the
     *     tree has no testbench or no source to compile
     */
    public static List<String> forProject(VerificationContext context) {
        List<Path> sources = VerilogProjectDetector.sourceFiles(context.projectRoot());
        List<VerilogModule> testbenches = VerilogProjectDetector.testbenches(context.projectRoot());
        if (sources.isEmpty() || testbenches.isEmpty()) {
            return List.of();
        }
        ProjectInfo project = context.projectInfo();
        String iverilog = VerilogTool.iverilog(project, context);
        String vvp = VerilogTool.vvp(project, context);
        Path runDirectory = context.artifacts().artifactsDirectory().resolve(RUN_DIRECTORY);

        StringBuilder script = new StringBuilder();
        script.append("set -e\n");
        script.append("mkdir -p ").append(quote(runDirectory.toString())).append('\n');
        for (VerilogModule testbench : testbenches) {
            Path output = runDirectory.resolve(safeName(testbench.name()) + ".vvp");
            script.append(quote(iverilog)).append(' ').append(VerilogTool.DIALECT_FLAG)
                    .append(" -s ").append(quote(testbench.name()))
                    .append(" -o ").append(quote(output.toString()));
            for (Path source : sources) {
                script.append(' ').append(quote(source.toString()));
            }
            script.append('\n');
            script.append(quote(vvp)).append(" -N ").append(quote(output.toString())).append('\n');
        }
        return shell(script.toString());
    }

    /** @return a shell invocation running {@code script}, in the platform's own shell. */
    public static List<String> shell(String script) {
        if (Platform.isWindows()) {
            return List.of("cmd", "/c", script);
        }
        return List.of("/bin/sh", "-c", script);
    }

    /** @return {@code value} quoted for a POSIX shell, or for {@code cmd.exe} on Windows. */
    public static String quote(String value) {
        if (Platform.isWindows()) {
            return '"' + value.replace("\"", "\\\"") + '"';
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safeName(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}

package dev.codewitness.vhdl;

import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the one command that analyses a VHDL tree and simulates every testbench in it.
 *
 * <p>A VHDL test is two phases - {@code ghdl -a} to analyse the design units into a work library and
 * {@code ghdl -r} to run a testbench - and some callers need that as a single command: the shared
 * mutation tester runs exactly one command per mutant, because it knows nothing about any language's
 * toolchain. This class supplies that command as a shell invocation ({@code sh -c '...'} on POSIX,
 * {@code cmd /c} on Windows) whose script analyses the tree in dependency order and then runs each
 * testbench, stopping at the first failure.
 *
 * <p>The analysis order is the same retry the Java path uses: a design unit that fails while a unit it
 * needs is still unanalysed is retried, and only a pass that makes no progress is a real error. The
 * file list is written to a file rather than interpolated into a shell word list, so a path with a
 * space in it is still analysed from the right place.
 */
public final class VhdlTestCommand {

    /** Subdirectory of the run's artefact store the work library and scripts are written into. */
    private static final String RUN_DIRECTORY = "vhdl/runs";

    /** Heredoc delimiter for the embedded source list; quoted so no expansion happens inside it. */
    private static final String SOURCE_LIST_DELIMITER = "CODEWITNESS_VHDL_SOURCES";

    private VhdlTestCommand() {
    }

    /**
     * @return the shell command that analyses and simulates every testbench, or an empty list when the
     *     tree has no testbench or no source to analyse
     */
    public static List<String> forProject(VerificationContext context) {
        List<Path> sources = VhdlProjectDetector.sourceFiles(context.projectRoot());
        List<VhdlDesignUnit> testbenches = VhdlProjectDetector.testbenches(context.projectRoot());
        if (sources.isEmpty() || testbenches.isEmpty()) {
            return List.of();
        }
        ProjectInfo project = context.projectInfo();
        String ghdl = VhdlTool.ghdl(project, context);
        Path runDirectory = context.artifacts().artifactsDirectory().resolve(RUN_DIRECTORY);
        List<String> tops = testbenches.stream().map(VhdlDesignUnit::name).toList();
        return shell(script(ghdl, runDirectory, sources, List.of(), tops));
    }

    /**
     * @return the shell command that analyses {@code sources} plus {@code extraSources} and simulates
     *     {@code top}; used to replay a counterexample from the test phase.
     */
    public static List<String> replay(
            VerificationContext context, List<Path> sources, List<Path> extraSources, String top) {
        if (top == null || top.isBlank()) {
            return List.of();
        }
        String ghdl = VhdlTool.ghdl(context.projectInfo(), context);
        Path runDirectory = context.artifacts().artifactsDirectory().resolve(RUN_DIRECTORY + "/replay");
        return shell(script(ghdl, runDirectory, sources, extraSources, List.of(top)));
    }

    /** @return the POSIX shell script that analyses the sources and runs the tops in order. */
    private static String script(
            String ghdl, Path runDirectory, List<Path> sources, List<Path> extraSources, List<String> tops) {

        Path library = runDirectory.resolve("lib");
        Path sourceList = runDirectory.resolve("sources.txt");
        List<Path> allSources = new ArrayList<>(sources);
        allSources.addAll(extraSources);
        int maxPasses = Math.max(1, allSources.size()) + 1;

        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n");
        sb.append("GHDL=").append(quote(ghdl)).append('\n');
        sb.append("LIB=").append(quote(library.toString())).append('\n');
        sb.append("SRC=").append(quote(sourceList.toString())).append('\n');
        sb.append("rm -rf \"$LIB\"\n");
        sb.append("mkdir -p \"$LIB\"\n");
        sb.append("cat > \"$SRC\" <<'").append(SOURCE_LIST_DELIMITER).append("'\n");
        for (Path source : allSources) {
            sb.append(source).append('\n');
        }
        sb.append(SOURCE_LIST_DELIMITER).append('\n');
        sb.append("passes=0\n");
        sb.append("while [ -s \"$SRC\" ] && [ \"$passes\" -lt ").append(maxPasses).append(" ]; do\n");
        sb.append("  passes=$((passes+1))\n");
        sb.append("  progress=0\n");
        sb.append("  : > \"$SRC.next\"\n");
        sb.append("  while IFS= read -r f; do\n");
        sb.append("    if \"$GHDL\" -a ").append(VhdlTool.STD_FLAG)
                .append(" --workdir=\"$LIB\" \"$f\" >/dev/null 2>&1; then\n");
        sb.append("      progress=1\n");
        sb.append("    else\n");
        sb.append("      printf '%s\\n' \"$f\" >> \"$SRC.next\"\n");
        sb.append("    fi\n");
        sb.append("  done < \"$SRC\"\n");
        sb.append("  if [ ! -s \"$SRC.next\" ]; then break; fi\n");
        sb.append("  if [ \"$progress\" -eq 0 ]; then\n");
        sb.append("    while IFS= read -r f; do \"$GHDL\" -a ").append(VhdlTool.STD_FLAG)
                .append(" --workdir=\"$LIB\" \"$f\" 2>&1 || true; done < \"$SRC.next\"\n");
        sb.append("    exit 1\n");
        sb.append("  fi\n");
        sb.append("  mv \"$SRC.next\" \"$SRC\"\n");
        sb.append("done\n");
        for (String top : tops) {
            sb.append(quote(ghdl)).append(" -r ").append(VhdlTool.STD_FLAG)
                    .append(" --workdir=").append(quote(library.toString()))
                    .append(' ').append(quote(top)).append(" --assert-level=error\n");
        }
        return sb.toString();
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
}

package dev.codewitness.vhdl;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.spi.VerificationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyses VHDL design units into a work library, in dependency order.
 *
 * <p>Unlike Verilog, VHDL analysis is order-sensitive: an architecture that instantiates an entity
 * cannot be analysed until the entity has been. A command line caller cannot know a tree's dependency
 * order without parsing it, so this helper analyses the files one at a time and repeats until either
 * everything is in the library or a whole pass makes no progress. A file that fails while another
 * file it needs is still unanalysed is retried; only a file that fails in a no-progress pass is a real
 * error, and its output is what the build reports.
 *
 * <p>Every unit that succeeds stays in the library, so a retry only re-runs the files that were still
 * pending - the work is bounded by the number of files times the number of passes, and a well ordered
 * tree finishes in one pass.
 */
public final class VhdlAnalyzer {

    private VhdlAnalyzer() {
    }

    /**
     * The outcome of analysing a set of files.
     *
     * @param success     {@code true} when every file was analysed
     * @param duration    the total wall-clock time of every attempt
     * @param failedRuns  the attempts that failed in the final, no-progress pass
     * @param diagnostics the diagnostics parsed from {@link #failedRuns}, or the warnings of a
     *     successful analysis when there are none
     */
    public record Analysis(
            boolean success, Duration duration, List<ProcessResult> failedRuns, List<CompilerDiagnostic> diagnostics) {

        /** @return the combined output of the final failed runs, or {@code ""} on success. */
        public String failureOutput() {
            StringBuilder sb = new StringBuilder();
            for (ProcessResult run : failedRuns) {
                if (!sb.isEmpty()) {
                    sb.append(System.lineSeparator());
                }
                sb.append(run.combinedOutput());
            }
            return sb.toString();
        }

        /** @return a process result that describes the analysis as a whole, for a build report. */
        public ProcessResult representative() {
            if (!failedRuns.isEmpty()) {
                return failedRuns.getLast();
            }
            return new ProcessResult(List.of(), null, true, 0, "", "", duration, false, null);
        }
    }

    /**
     * Analyses every file into {@code workDirectory}, retrying files whose dependencies are not yet in
     * the library.
     *
     * @param context       run context, providing the sandboxed runner
     * @param ghdl          the resolved {@code ghdl} command
     * @param workDirectory the library directory; created when absent
     * @param files         the source files to analyse
     * @return what happened, with the failing files' output when the analysis did not finish
     */
    public static Analysis analyze(
            VerificationContext context, String ghdl, Path workDirectory, List<Path> files) {

        try {
            Files.createDirectories(workDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the GHDL work library directory", e);
        }

        List<Path> pending = new ArrayList<>(files);
        List<ProcessResult> lastFailures = new ArrayList<>();
        Duration total = Duration.ZERO;
        int maxPasses = Math.max(1, files.size()) + 1;
        for (int pass = 0; pass < maxPasses && !pending.isEmpty(); pass++) {
            List<Path> next = new ArrayList<>();
            lastFailures = new ArrayList<>();
            for (Path file : pending) {
                ProcessSpec spec = ProcessSpec.builder(
                                VhdlBuildProvider.analyzeCommand(ghdl, workDirectory, file), context.projectRoot())
                        .environment(VhdlTool.environment(context))
                        .timeout(context.config().limits().timeout())
                        .maxOutputBytes(1_000_000)
                        .build();
                ProcessResult result = context.runner().run(spec);
                total = total.plus(result.duration());
                if (result.success()) {
                    continue;
                }
                next.add(file);
                lastFailures.add(result);
            }
            if (next.isEmpty()) {
                return new Analysis(true, total, List.of(), List.of());
            }
            if (next.size() == pending.size()) {
                // A whole pass made no progress: the remaining files have real errors, not a missing
                // dependency that a later file will supply.
                List<CompilerDiagnostic> diagnostics = parseDiagnostics(context, lastFailures, DiagnosticSeverity.ERROR);
                return new Analysis(false, total, List.copyOf(lastFailures), diagnostics);
            }
            pending = next;
        }
        List<CompilerDiagnostic> diagnostics = parseDiagnostics(context, lastFailures, DiagnosticSeverity.ERROR);
        return new Analysis(false, total, List.copyOf(lastFailures), diagnostics);
    }

    private static List<CompilerDiagnostic> parseDiagnostics(
            VerificationContext context, List<ProcessResult> runs, DiagnosticSeverity severity) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        for (ProcessResult run : runs) {
            diagnostics.addAll(VhdlDiagnostics.parse(run.combinedOutput(), context.projectRoot(), severity));
        }
        return List.copyOf(diagnostics);
    }
}

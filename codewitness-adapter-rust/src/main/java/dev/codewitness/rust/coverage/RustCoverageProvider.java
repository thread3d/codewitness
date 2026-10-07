package dev.codewitness.rust.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.rust.Cargo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures coverage through {@code cargo-llvm-cov}.
 *
 * <p>Rust has no coverage tool in its standard distribution, so its absence is a normal, reported
 * condition rather than a failure: when the subcommand is not installed the report says coverage was
 * not measured, and no requirement is affected either way. When it is present the tests are re-run
 * under instrumentation and the numbers are read from cargo-llvm-cov's own JSON export rather than
 * from its console table.
 *
 * <p>Only the aggregate totals are read. Per-file coverage would require parsing the exporter's nested
 * file array, which this adapter does not do; the report says so instead of inventing per-file
 * numbers.
 */
public final class RustCoverageProvider implements VerificationTools.CoverageProvider {

    private static final Pattern NUMBER = Pattern.compile("\"%s\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    @Override
    public String id() {
        return "cargo-llvm-cov";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUST);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        boolean llvmCov = Boolean.parseBoolean(
                project.attributes().getOrDefault(Cargo.ATTR_HAS_LLVM_COV, "false"));
        boolean tarpaulin = Boolean.parseBoolean(
                project.attributes().getOrDefault(Cargo.ATTR_HAS_TARPAULIN, "false"));
        if (!llvmCov) {
            String note = tarpaulin
                    ? "cargo-tarpaulin is installed but CodeWitness only reads cargo-llvm-cov's JSON export; "
                            + "install cargo-llvm-cov to measure coverage"
                    : "cargo-llvm-cov is not installed; install it to measure coverage";
            return CoverageReport.notMeasured("cargo-llvm-cov", note);
        }
        if (!Cargo.hasManifest(context)) {
            return CoverageReport.notMeasured("cargo-llvm-cov", "no Cargo.toml was found");
        }
        Path report = context.artifacts().artifactsDirectory().resolve("rust/coverage.json");
        try {
            Files.createDirectories(report.getParent());
            Files.deleteIfExists(report);
        } catch (IOException e) {
            return CoverageReport.notMeasured("cargo-llvm-cov",
                    "cannot prepare the coverage report directory: " + e);
        }
        String cargo = Cargo.cargo(project, context);
        List<String> command = List.of(cargo, "llvm-cov", "--json", "--output-path", report.toString(), "--quiet");
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(Cargo.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (result.timedOut()) {
            // The killed exporter may have written a partial JSON file, so the timeout is checked
            // before that file is looked for or parsed.
            return CoverageReport.notMeasured("cargo-llvm-cov", "cargo llvm-cov was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; a partial coverage "
                    + "report is not a measurement");
        }

        if (!Files.isRegularFile(report)) {
            return CoverageReport.notMeasured("cargo-llvm-cov", "cargo llvm-cov produced no JSON report ("
                    + result.describeOutcome() + "); last output: " + Cargo.lastLines(result.combinedOutput(), 4));
        }
        return parse(report, "cargo-llvm-cov JSON; test run " + result.describeOutcome());
    }

    /** Parses cargo-llvm-cov's JSON export without pulling a JSON library into the adapter. */
    static CoverageReport parse(Path report, String note) {
        String json;
        try {
            json = Files.readString(report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return CoverageReport.notMeasured("cargo-llvm-cov", "cannot read " + report + ": " + e);
        }
        String totals = objectAt(json, "totals");
        String lines = objectAt(totals, "lines");
        int covered = (int) numberAt(lines, "covered");
        int total = (int) numberAt(lines, "count");
        String branches = objectAt(totals, "branches");
        int branchCovered = (int) numberAt(branches, "covered");
        int branchTotal = (int) numberAt(branches, "count");
        if (total == 0) {
            return CoverageReport.notMeasured("cargo-llvm-cov",
                    "the cargo-llvm-cov JSON report contained no line totals");
        }
        return new CoverageReport(true, "cargo-llvm-cov", note, covered, total, branchCovered, branchTotal,
                Map.of(), List.of(report));
    }

    /** @return the substring of the object stored under {@code "key"}, or an empty string. */
    static String objectAt(String json, String key) {
        if (json == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{").matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return balancedAt(json, matcher.end() - 1);
    }

    /** @return the balanced brace/bracket expression starting at {@code start}, or the rest of the text. */
    static String balancedAt(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) {
            return "";
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : open == '[' ? ']' : 0;
        if (close == 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return text.substring(start);
    }

    private static double numberAt(String json, String key) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile(String.format(NUMBER.pattern(), Pattern.quote(key))).matcher(json);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}

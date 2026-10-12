package dev.codewitness.verilog.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.verilog.VerilogModuleParser;
import dev.codewitness.verilog.VerilogProjectDetector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Verilog and SystemVerilog rule table, read over one literal-aware view of each source file.
 *
 * <p>The rules cover the mistakes that a compiler accepts and a simulation may not reveal: a blocking
 * assignment in a clocked block, a nonblocking assignment in a combinational one, a {@code case} with
 * no {@code default}, an incomplete sensitivity list, a zero-delay infinite loop, an {@code initial}
 * block in a module that is meant to be synthesised, and a division by a run-time value. The security
 * rules cover the simulation system tasks that reach outside the design - {@code $system} and the file
 * tasks - and are reported separately by {@link VerilogSecurityAnalyzer}.
 *
 * <p>Every finding this analyzer produces is advisory. It identifies itself as {@code verilog-heuristics},
 * and the core treats a heuristic finding as a hint rather than a refutation, so a rule that is wrong
 * about a legitimate design cannot fail a requirement. The rules are deliberately narrow: each one
 * matches the shape it is about in the masked view, where comments and strings are blank, and reports
 * the file and line so a reader can check it.
 */
public final class VerilogHeuristicAnalyzer
        implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /** Analyzer id; the word "heuristics" is what keeps its findings advisory in the core. */
    public static final String ID = "verilog-heuristics";

    /** A lone assignment operator: {@code =} that is not part of {@code ==}, {@code <=}, {@code >=} or {@code !=}. */
    private static final Pattern BLOCKING_ASSIGNMENT = Pattern.compile("(?<![<>=!])=(?!=)");

    /** A nonblocking assignment at the start of a statement. */
    private static final Pattern NONBLOCKING_ASSIGNMENT = Pattern.compile(
            "(?m)(?:^|;|\\)|\\bbegin\\b|\\belse\\b|\\bend\\b)\\s*[A-Za-z_][A-Za-z0-9_$]*(?:\\s*\\[[^\\]]*\\])?\\s*<=");

    private static final Pattern CASE_KEYWORD = Pattern.compile("\\b(case|casex|casez)\\b");
    private static final Pattern DEFAULT_KEYWORD = Pattern.compile("\\bdefault\\b");
    private static final Pattern FOREVER = Pattern.compile("\\bforever\\b");
    private static final Pattern WHILE = Pattern.compile("\\bwhile\\s*\\(");
    private static final Pattern DIVISION = Pattern.compile("[/%]\\s*[A-Za-z_][A-Za-z0-9_$]*");
    private static final Pattern MODULE_HEADER = Pattern.compile(
            "\\bmodule\\s+[A-Za-z_][A-Za-z0-9_$]*\\s*(?:#\\s*\\([^;]*?\\)\\s*)?\\(([^;]*?)\\)\\s*;",
            Pattern.DOTALL);

    private static final Pattern SYSTEM_TASK = Pattern.compile(
            "\\$(system|fopen|fclose|fwrite|fdisplay|fread|fscanf|fgetc|fgets|readmemh|readmemb|sformatf?)\\b");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VERILOG);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return allFindings(context).stream()
                .filter(finding -> finding.category() != FindingCategory.SECURITY)
                .toList();
    }

    /**
     * @return only the security rules' findings, for the separate security phase.
     *
     * <p>A class that implements both analyzer interfaces must answer them differently: the engine
     * registers a static analyzer and a security analyzer as two providers and calls each one's
     * {@code analyze}, so returning every rule from both would put the code rules into the security
     * phase and the security rules into the static one twice.
     */
    public List<Finding> analyzeSecurity(VerificationContext context) {
        return allFindings(context).stream()
                .filter(finding -> finding.category() == FindingCategory.SECURITY)
                .toList();
    }

    private List<Finding> allFindings(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : designFiles(context)) {
            String source = read(file);
            if (source.isBlank()) {
                continue;
            }
            VerilogSourceScan scan = VerilogSourceScan.of(source);
            String relative = relativeTo(context.projectRoot(), file);
            findings.addAll(combinationalAndSequential(scan, relative));
            findings.addAll(caseWithoutDefault(scan, relative));
            findings.addAll(loops(scan, relative));
            findings.addAll(initialInDesign(scan, relative));
            findings.addAll(nonConstantDivision(scan, relative));
            findings.addAll(securityTasks(scan, relative));
        }
        return List.copyOf(findings);
    }

    // ------------------------------------------------------------------ rules

    private List<Finding> combinationalAndSequential(VerilogSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        for (AlwaysBlock block : alwaysBlocks(scan.masked())) {
            boolean sequential = block.sensitivity().contains("posedge") || block.sensitivity().contains("negedge");
            if (sequential) {
                if (BLOCKING_ASSIGNMENT.matcher(block.body()).find()) {
                    findings.add(finding("CW-VLOG-BUG-001", Severity.MEDIUM, FindingCategory.BUG, file,
                            scan.lineAt(block.bodyStart()),
                            "a blocking assignment (=) in a clocked always block; sequential logic is "
                                    + "expected to use nonblocking assignments (<=) so every register updates "
                                    + "on the same edge"));
                }
            } else if (NONBLOCKING_ASSIGNMENT.matcher(block.body()).find()) {
                findings.add(finding("CW-VLOG-BUG-002", Severity.MEDIUM, FindingCategory.BUG, file,
                        scan.lineAt(block.bodyStart()),
                        "a nonblocking assignment (<=) in a combinational always block; combinational logic "
                                + "is expected to use blocking assignments (=) so its outputs settle in order"));
            }
            if (incompleteSensitivity(block)) {
                findings.add(finding("CW-VLOG-BUG-003", Severity.LOW, FindingCategory.BUG, file,
                        scan.lineAt(block.start()),
                        "the always block's sensitivity list names one signal explicitly; a combinational "
                                + "block that reads any other signal will not re-evaluate when it changes "
                                + "(always @(*) is the complete form)"));
            }
        }
        return findings;
    }

    private List<Finding> caseWithoutDefault(VerilogSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        String text = scan.masked();
        Matcher matcher = CASE_KEYWORD.matcher(text);
        while (matcher.find()) {
            int end = keyword(text, matcher.end(), "endcase");
            if (end < 0) {
                continue;
            }
            String body = text.substring(matcher.end(), end);
            if (!DEFAULT_KEYWORD.matcher(body).find()) {
                findings.add(finding("CW-VLOG-CORRECT-001", Severity.LOW, FindingCategory.CORRECTNESS, file,
                        scan.lineAt(matcher.start()),
                        "a " + matcher.group(1) + " statement has no default branch; an unmatched value "
                                + "leaves the assignment target unchanged and infers a latch"));
            }
        }
        return findings;
    }

    private List<Finding> loops(VerilogSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        for (VerilogSourceScan.Match match : scan.find(FOREVER)) {
            String body = bodyAfter(scan.masked(), match.start(), "forever".length());
            if (!body.contains("@") && !body.contains("#")) {
                findings.add(finding("CW-VLOG-BUG-004", Severity.HIGH, FindingCategory.BUG, file, match.line(),
                        "a forever loop with no @ or # in its body never advances simulation time; it will "
                                + "hang the simulator rather than repeat a clocked action"));
            }
        }
        for (VerilogSourceScan.Match match : scan.find(WHILE)) {
            int open = scan.masked().indexOf('(', match.start());
            int close = open < 0 ? -1 : VerilogModuleParser.matching(scan.masked(), open, '(', ')');
            if (close < 0) {
                continue;
            }
            String condition = scan.masked().substring(open + 1, close - 1).replace(" ", "");
            if (isAlwaysTrue(condition)) {
                String body = bodyAfter(scan.masked(), close - 1, 1);
                if (!body.contains("@") && !body.contains("#")) {
                    findings.add(finding("CW-VLOG-BUG-005", Severity.HIGH, FindingCategory.BUG, file,
                            match.line(), "a while loop whose condition is always true and whose body has no @ "
                                    + "or # never advances simulation time; it will hang the simulator"));
                }
            }
        }
        return findings;
    }

    private List<Finding> initialInDesign(VerilogSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        Matcher header = MODULE_HEADER.matcher(scan.masked());
        while (header.find()) {
            if (header.group(1).isBlank()) {
                // A module with no ports is a testbench or a top-level wrapper; an initial block there
                // is how a testbench works and is not a synthesis concern.
                continue;
            }
            int endmodule = keyword(scan.masked(), header.end(), "endmodule");
            if (endmodule < 0) {
                continue;
            }
            String body = scan.masked().substring(header.end(), endmodule);
            Matcher initial = Pattern.compile("\\binitial\\b").matcher(body);
            if (initial.find()) {
                findings.add(finding("CW-VLOG-CORRECT-002", Severity.LOW, FindingCategory.CORRECTNESS, file,
                        scan.lineAt(header.end() + initial.start()),
                        "an initial block in a module with ports is not synthesizable; it is executed once at "
                                + "time zero in simulation and ignored by synthesis"));
            }
        }
        return findings;
    }

    private List<Finding> nonConstantDivision(VerilogSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        for (VerilogSourceScan.Match match : scan.find(DIVISION)) {
            findings.add(finding("CW-VLOG-STYLE-001", Severity.LOW, FindingCategory.STYLE, file, match.line(),
                    "a division or modulo by a signal rather than a constant; most synthesis tools either "
                            + "reject it or infer a large, slow divider"));
        }
        return findings;
    }

    private List<Finding> securityTasks(VerilogSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        for (VerilogSourceScan.Match match : scan.findWith(SYSTEM_TASK)) {
            String task = match.captured() == null ? "" : match.captured().toLowerCase(Locale.ROOT);
            boolean shell = task.equals("system");
            findings.add(finding(shell ? "CW-VLOG-SEC-001" : "CW-VLOG-SEC-002",
                    shell ? Severity.HIGH : Severity.MEDIUM, FindingCategory.SECURITY, file, match.line(),
                    shell
                            ? "a $system call executes a shell command from the simulation; a design that runs "
                                    + "it is running whatever the source names on the verifying machine"
                            : "a file system task ($" + task + ") reads or writes a path at simulation time, "
                                    + "so the result depends on files outside the design"));
        }
        return findings;
    }

    // ------------------------------------------------------------------ helpers

    /** @return the design source files, excluding testbenches, whose always blocks are not design logic. */
    private static List<Path> designFiles(VerificationContext context) {
        List<Path> files = new ArrayList<>();
        for (Path file : VerilogProjectDetector.sourceFiles(context.projectRoot())) {
            String stem = stem(file.getFileName().toString());
            if (VerilogModuleParser.isTestbenchName(stem)) {
                continue;
            }
            files.add(file);
        }
        return files;
    }

    private static boolean incompleteSensitivity(AlwaysBlock block) {
        String sensitivity = block.sensitivity().replace(" ", "");
        if (sensitivity.isEmpty() || sensitivity.contains("*") || sensitivity.contains("posedge")
                || sensitivity.contains("negedge") || sensitivity.contains(",") || sensitivity.contains("or")) {
            return false;
        }
        return sensitivity.matches("[A-Za-z_][A-Za-z0-9_$.\\[\\]:]*");
    }

    private static boolean isAlwaysTrue(String condition) {
        String normalized = condition.toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("1'b1") || normalized.equals("1'b1")
                || normalized.equals("true") || normalized.equals("(1)");
    }

    /** @return every {@code always @(...)} block's sensitivity list and body range. */
    private static List<AlwaysBlock> alwaysBlocks(String text) {
        List<AlwaysBlock> blocks = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\balways\\b\\s*@\\s*\\(").matcher(text);
        while (matcher.find()) {
            int open = text.indexOf('(', matcher.end() - 1);
            if (open < 0) {
                continue;
            }
            int close = VerilogModuleParser.matching(text, open, '(', ')');
            if (close < 0) {
                continue;
            }
            String sensitivity = text.substring(open + 1, close - 1);
            int bodyStart = bodyStart(text, close);
            int bodyEnd = bodyEnd(text, bodyStart);
            if (bodyEnd <= bodyStart) {
                continue;
            }
            blocks.add(new AlwaysBlock(matcher.start(), sensitivity, bodyStart, text.substring(bodyStart, bodyEnd)));
        }
        return blocks;
    }

    private static int bodyStart(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** @return the end offset of a statement or begin/end block that starts at {@code start}. */
    private static int bodyEnd(String text, int start) {
        if (start >= text.length()) {
            return start;
        }
        if (text.startsWith("begin", start) && isWordBoundary(text, start + "begin".length())) {
            int end = keyword(text, start + "begin".length(), "end");
            return end < 0 ? text.length() : end - 1;
        }
        int semicolon = text.indexOf(';', start);
        return semicolon < 0 ? text.length() : semicolon;
    }

    private static String bodyAfter(String text, int start, int keywordLength) {
        int bodyStart = bodyStart(text, start + keywordLength);
        int bodyEnd = bodyEnd(text, bodyStart);
        return text.substring(bodyStart, Math.max(bodyStart, bodyEnd));
    }

    private static boolean isWordBoundary(String text, int index) {
        return index >= text.length() || !Character.isLetterOrDigit(text.charAt(index)) && text.charAt(index) != '_';
    }

    /** @return the offset of the {@code close} keyword that ends the block opened before {@code from}. */
    private static int keyword(String text, int from, String close) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(close) + "\\b").matcher(text);
        return matcher.find(from) ? matcher.start() : -1;
    }

    private static Finding finding(
            String ruleId, Severity severity, FindingCategory category, String file, int line, String message) {
        return new Finding(ID, ruleId, severity, category, message, file, line, null, Map.of());
    }

    private static String relativeTo(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException _) {
            return file.toString();
        }
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (Exception _) {
            return "";
        }
    }

    /** An {@code always} block's sensitivity list and the body the rules read. */
    private record AlwaysBlock(int start, String sensitivity, int bodyStart, String body) {
    }
}

package dev.codewitness.vhdl.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.vhdl.VhdlProjectDetector;
import dev.codewitness.vhdl.VhdlSourceParser;

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
 * A VHDL rule table, read over one literal-aware view of each source file.
 *
 * <p>The rules cover the mistakes a simulator accepts and a synthesis tool or a later reader may not:
 * a {@code case} statement with no {@code when others} branch, an {@code if} in a clocked process with
 * no {@code else} branch, a process that declares both a sensitivity list and a {@code wait} statement,
 * an unbounded loop with neither a {@code wait} nor an {@code exit}, and a division or modulo by a
 * run-time value. The security rules cover the constructs that reach outside the design - a foreign
 * subprogram binding and file I/O through {@code std.textio} - and are reported separately by
 * {@link VhdlSecurityAnalyzer}.
 *
 * <p>Every finding this analyzer produces is advisory. It identifies itself as {@code vhdl-heuristics},
 * and the core treats a heuristic finding as a hint rather than a refutation, so a rule that is wrong
 * about a legitimate design cannot fail a requirement. The rules are deliberately narrow: each one
 * matches the shape it is about in the masked view, where comments and strings are blank, and reports
 * the file and line so a reader can check it.
 */
public final class VhdlHeuristicAnalyzer
        implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /** Analyzer id; the word "heuristics" is what keeps its findings advisory in the core. */
    public static final String ID = "vhdl-heuristics";

    private static final Pattern CASE_KEYWORD = Pattern.compile("(?i)\\bcase\\b");
    private static final Pattern WHEN_OTHERS = Pattern.compile("(?i)\\bwhen\\s+others\\s*=>");
    private static final Pattern PROCESS = Pattern.compile("(?i)\\bprocess\\b");
    private static final Pattern CLOCKED = Pattern.compile("(?i)\\b(rising_edge|falling_edge)\\s*\\(");
    private static final Pattern WAIT = Pattern.compile("(?i)\\bwait\\b");
    private static final Pattern EXIT_OR_WAIT = Pattern.compile("(?i)\\b(exit|wait)\\b");
    private static final Pattern UNBOUNDED_LOOP = Pattern.compile("(?i)\\b(while|loop)\\b");
    private static final Pattern DIVISION = Pattern.compile(
            "(?i)(?<![/*])/(?![/*])\\s*[A-Za-z_]|\\b(mod|rem)\\b\\s*[A-Za-z_(]");
    private static final Pattern SIMULATION_ONLY = Pattern.compile(
            "(?i)\\bstd\\s*\\.\\s*env\\s*\\.\\s*(finish|stop)\\b");
    private static final Pattern FILE_IO = Pattern.compile(
            "(?i)\\b(file_open|file_close|readline|writeline|textio|endfile)\\b");
    private static final Pattern FOREIGN = Pattern.compile("(?i)\\bforeign\\b");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VHDL);
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
            VhdlSourceScan scan = VhdlSourceScan.of(source);
            String relative = relativeTo(context.projectRoot(), file);
            findings.addAll(caseWithoutOthers(scan, relative));
            findings.addAll(clockedIfWithoutElse(scan, relative));
            findings.addAll(sensitivityAndWait(scan, relative));
            findings.addAll(unboundedLoops(scan, relative));
            findings.addAll(simulationOnly(scan, relative));
            findings.addAll(nonConstantDivision(scan, relative));
            findings.addAll(securityConstructs(scan, relative));
        }
        return List.copyOf(findings);
    }

    // ------------------------------------------------------------------ rules

    private List<Finding> caseWithoutOthers(VhdlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        String lower = scan.masked().toLowerCase(Locale.ROOT);
        Matcher matcher = CASE_KEYWORD.matcher(scan.masked());
        while (matcher.find()) {
            if (precededByEnd(lower, matcher.start())) {
                continue;
            }
            int end = findEndCase(lower, matcher.end());
            if (end < 0) {
                continue;
            }
            String body = scan.masked().substring(matcher.end(), end);
            if (!WHEN_OTHERS.matcher(body).find()) {
                findings.add(finding("CW-VHDL-BUG-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, file,
                        scan.lineAt(matcher.start()),
                        "a case statement has no when others branch; an unmatched value leaves the assignment "
                                + "target unchanged and infers a latch"));
            }
        }
        return findings;
    }

    private List<Finding> clockedIfWithoutElse(VhdlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        String masked = scan.masked();
        String lower = masked.toLowerCase(Locale.ROOT);
        Matcher process = PROCESS.matcher(masked);
        while (process.find()) {
            int begin = VhdlSourceParser.findKeyword(lower, "begin", process.end());
            int endProcess = VhdlSourceParser.findKeyword(lower, "process", begin < 0 ? process.end() : begin);
            if (begin < 0) {
                continue;
            }
            int end = endProcessFor(lower, begin);
            if (end < 0) {
                continue;
            }
            String body = masked.substring(begin, end);
            String bodyLower = body.toLowerCase(Locale.ROOT);
            if (!CLOCKED.matcher(body).find()) {
                continue;
            }
            Matcher ifs = Pattern.compile("(?i)\\bif\\b").matcher(body);
            while (ifs.find()) {
                if (hasElse(bodyLower, ifs.end())) {
                    continue;
                }
                findings.add(finding("CW-VHDL-BUG-002", Severity.MEDIUM, FindingCategory.CORRECTNESS, file,
                        scan.lineAt(begin + ifs.start()),
                        "an if statement in a clocked process has no else branch; a path that assigns nothing "
                                + "infers a latch or leaves the target unchanged"));
            }
        }
        return findings;
    }

    private List<Finding> sensitivityAndWait(VhdlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        String masked = scan.masked();
        String lower = masked.toLowerCase(Locale.ROOT);
        Matcher process = PROCESS.matcher(masked);
        while (process.find()) {
            int open = skipWhitespace(lower, process.end());
            if (open >= lower.length() || lower.charAt(open) != '(') {
                // `process` with no sensitivity list: a wait is its normal form, not a hazard.
                continue;
            }
            int close = VhdlSourceParser.matching(masked, open, '(', ')');
            if (close < 0) {
                continue;
            }
            int begin = VhdlSourceParser.findKeyword(lower, "begin", close);
            if (begin < 0) {
                continue;
            }
            int end = endProcessFor(lower, begin);
            if (end < 0) {
                continue;
            }
            if (WAIT.matcher(masked.substring(begin, end)).find()) {
                findings.add(finding("CW-VHDL-BUG-003", Severity.LOW, FindingCategory.CORRECTNESS, file,
                        scan.lineAt(process.start()),
                        "a process declares both a sensitivity list and a wait statement; VHDL forbids the "
                                + "combination, and a synthesis tool reads the sensitivity list while a "
                                + "simulator reads the wait"));
            }
        }
        return findings;
    }

    private List<Finding> unboundedLoops(VhdlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        String masked = scan.masked();
        String lower = masked.toLowerCase(Locale.ROOT);
        Matcher matcher = UNBOUNDED_LOOP.matcher(masked);
        while (matcher.find()) {
            String keyword = matcher.group().toLowerCase(Locale.ROOT);
            String preceding = precedingWord(lower, matcher.start());
            if (keyword.equals("loop")) {
                if (preceding.equals("end")) {
                    continue;
                }
                if (statementPrefixContains(lower, matcher.start(), "while")
                        || statementPrefixContains(lower, matcher.start(), "for")) {
                    // `while ... loop` and `for ... loop` are one loop; the `while` match covers the
                    // unbounded form and a `for` loop is bounded, so the bare `loop` adds nothing.
                    continue;
                }
            }
            if (preceding.equals("end")) {
                continue;
            }
            int endLoop = VhdlSourceParser.findKeyword(lower, "end", matcher.end());
            if (endLoop < 0) {
                continue;
            }
            int end = VhdlSourceParser.findKeyword(lower, "loop", endLoop);
            if (end < 0) {
                continue;
            }
            String body = masked.substring(matcher.end(), end);
            if (!EXIT_OR_WAIT.matcher(body).find()) {
                findings.add(finding("CW-VHDL-BUG-004", Severity.HIGH, FindingCategory.BUG, file,
                        scan.lineAt(matcher.start()),
                        "an unbounded " + keyword + " loop has neither a wait nor an exit in its body; it never "
                                + "advances simulation time and will hang the simulator"));
            }
        }
        return findings;
    }

    private List<Finding> simulationOnly(VhdlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        for (VhdlSourceScan.Match match : scan.findWith(SIMULATION_ONLY)) {
            findings.add(finding("CW-VHDL-CORRECT-001", Severity.LOW, FindingCategory.CORRECTNESS, file,
                    match.line(),
                    "std.env." + (match.captured() == null ? "finish" : match.captured().toLowerCase(Locale.ROOT))
                            + " stops the simulation and has no meaning in hardware; a design that carries it "
                            + "cannot be synthesized"));
        }
        return findings;
    }

    private List<Finding> nonConstantDivision(VhdlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        for (VhdlSourceScan.Match match : scan.find(DIVISION)) {
            findings.add(finding("CW-VHDL-STYLE-001", Severity.LOW, FindingCategory.STYLE, file, match.line(),
                    "a division or modulo by a signal rather than a constant; most synthesis tools either "
                            + "reject it or infer a large, slow divider"));
        }
        return findings;
    }

    private List<Finding> securityConstructs(VhdlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        for (VhdlSourceScan.Match match : scan.find(FOREIGN)) {
            findings.add(finding("CW-VHDL-SEC-001", Severity.HIGH, FindingCategory.SECURITY, file, match.line(),
                    "a foreign subprogram binding (VHPIDIRECT) calls code outside the design; a design that "
                            + "runs it is running whatever the source names on the verifying machine"));
        }
        for (VhdlSourceScan.Match match : scan.findWith(FILE_IO)) {
            String name = match.captured() == null ? "file I/O" : match.captured().toLowerCase(Locale.ROOT);
            findings.add(finding("CW-VHDL-SEC-002", Severity.MEDIUM, FindingCategory.SECURITY, file, match.line(),
                    "a " + name + " call reads or writes a path at simulation time, so the result depends on "
                            + "files outside the design"));
        }
        return findings;
    }

    // ------------------------------------------------------------------ helpers

    /** @return the design source files, excluding testbenches, whose processes are not design logic. */
    private static List<Path> designFiles(VerificationContext context) {
        List<Path> files = new ArrayList<>();
        for (Path file : VhdlProjectDetector.sourceFiles(context.projectRoot())) {
            if (VhdlSourceParser.isTestbenchName(VhdlSourceParser.stem(file.getFileName().toString()))) {
                continue;
            }
            files.add(file);
        }
        return files;
    }

    private static boolean precededByEnd(String lower, int start) {
        return precedingWord(lower, start).equals("end");
    }

    /** @return the identifier immediately before {@code start}, or {@code ""} when there is none. */
    private static String precedingWord(String lower, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(lower.charAt(i))) {
            i--;
        }
        int wordEnd = i + 1;
        while (i >= 0 && (Character.isLetterOrDigit(lower.charAt(i)) || lower.charAt(i) == '_')) {
            i--;
        }
        return lower.substring(i + 1, wordEnd);
    }

    /** @return {@code true} when {@code word} appears in the same statement before {@code start}. */
    private static boolean statementPrefixContains(String lower, int start, String word) {
        int i = start - 1;
        while (i >= 0) {
            char c = lower.charAt(i);
            if (c == ';' || c == '\n') {
                break;
            }
            i--;
        }
        String prefix = lower.substring(i + 1, start);
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(prefix).find();
    }

    private static int findEndCase(String lower, int from) {
        int index = from;
        while (true) {
            int end = VhdlSourceParser.findKeyword(lower, "end", index);
            if (end < 0) {
                return -1;
            }
            int after = VhdlSourceParser.findKeyword(lower, "case", end);
            if (after >= 0 && after - end < 12) {
                return after;
            }
            index = end + 3;
        }
    }

    private static int endProcessFor(String lower, int from) {
        int index = from;
        while (true) {
            int end = VhdlSourceParser.findKeyword(lower, "end", index);
            if (end < 0) {
                return -1;
            }
            int after = VhdlSourceParser.findKeyword(lower, "process", end);
            if (after >= 0 && after - end < 15) {
                return after;
            }
            index = end + 3;
        }
    }

    /** @return {@code true} when the statement beginning at {@code ifEnd} has a plain {@code else}. */
    private static boolean hasElse(String lower, int ifEnd) {
        int depth = 0;
        int i = ifEnd;
        while (i < lower.length()) {
            int ifAt = VhdlSourceParser.findKeyword(lower, "if", i);
            int endIfAt = lower.indexOf("end if", i);
            int elseAt = lower.indexOf("else", i);
            int elsifAt = lower.indexOf("elsif", i);
            if (endIfAt >= 0 && endIfAt <= ifAt) {
                if (depth == 0) {
                    return false;
                }
                depth--;
                i = endIfAt + "end if".length();
                continue;
            }
            if (ifAt >= 0 && (endIfAt < 0 || ifAt < endIfAt)) {
                depth++;
                i = ifAt + 2;
                continue;
            }
            if (elseAt >= 0 && (elsifAt != elseAt) && (ifAt < 0 || elseAt < ifAt)) {
                if (depth == 0) {
                    return true;
                }
                i = elseAt + 4;
                continue;
            }
            if (elsifAt >= 0) {
                i = elsifAt + 5;
                continue;
            }
            return false;
        }
        return false;
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
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

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (Exception _) {
            return "";
        }
    }
}

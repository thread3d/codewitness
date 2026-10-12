package dev.codewitness.pascal.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.pascal.PascalModule;
import dev.codewitness.pascal.PascalProjectDetector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Static analysis for Pascal, stated as rules over the source.
 *
 * <p>These are heuristics, not a compiler and not a proof: the analyzer is named
 * {@code pascal-heuristics}, which is how the engine knows its findings are advisory and cannot on their
 * own refute a requirement. What it looks for is the small set of shapes that are almost always mistakes
 * in Pascal and that a compiler does not flag - memory allocated and never released, an exception
 * handler that swallows what it caught, a {@code with} that hides where a name comes from, and file
 * handles left open.
 *
 * <p>One class implements both the static and the security provider, because the rules share a scanner
 * and a report shape; the two interfaces are split by category so a security finding is never presented
 * as ordinary static analysis.
 */
public final class PascalHeuristicAnalyzer
        implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /** The identifier the engine reads to classify this analyzer's findings as advisory. */
    public static final String ID = "pascal-heuristics";

    private static final Pattern ALLOCATION = Pattern.compile("(?i)\\b(getmem|new)\\s*\\(");
    private static final Pattern RELEASE = Pattern.compile("(?i)\\b(freemem|dispose)\\s*\\(");
    private static final Pattern FILE_OPEN = Pattern.compile("(?i)\\b(reset|rewrite|append)\\s*\\(");
    private static final Pattern FILE_CLOSE = Pattern.compile("(?i)\\bclosefile\\s*\\(");
    private static final Pattern EMPTY_EXCEPT = Pattern.compile("(?i)\\bexcept\\s*;?\\s*end\\b");
    private static final Pattern WITH_STATEMENT = Pattern.compile("(?i)\\bwith\\s+[A-Za-z_]");
    private static final Pattern GOTO_STATEMENT = Pattern.compile("(?i)\\bgoto\\b");
    private static final Pattern SQL_STATEMENT =
            Pattern.compile("(?i)(\\bexecsql\\b|\\.open\\b|\\bsql\\.add\\b|\\bsql\\.text\\b)");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b[A-Za-z_][A-Za-z0-9_]*"
                    + "(password|passwd|secret|token|apikey|api_key|privatekey)"
                    + "[A-Za-z0-9_]*\\s*(:=|=|:)\\s*'([^']+)'");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return LanguageId.PASCAL.equals(project.language());
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return allFindings(context).stream().filter(finding -> finding.category() != FindingCategory.SECURITY).toList();
    }

    /**
     * @return the security findings alone. The security provider is a separate interface, so one class
     *     being registered twice needs the split to be explicit rather than accidental.
     */
    public List<Finding> analyzeSecurity(VerificationContext context) {
        return allFindings(context).stream().filter(finding -> finding.category() == FindingCategory.SECURITY).toList();
    }

    private List<Finding> allFindings(VerificationContext context) {
        Path root = context.projectRoot();
        List<Finding> findings = new ArrayList<>();
        for (PascalModule module : PascalProjectDetector.modules(root)) {
            if (module.test()) {
                continue;
            }
            String source = PascalProjectDetector.readString(module.file());
            if (source.isBlank()) {
                continue;
            }
            PascalSourceScan scan = PascalSourceScan.of(source);
            String relative = relativeTo(root, module.file());
            memoryLeaks(scan, relative, findings);
            openFiles(scan, relative, findings);
            emptyExcept(scan, relative, findings);
            withStatements(scan, relative, findings);
            gotoStatements(scan, relative, findings);
            sqlInjection(scan, relative, findings);
            hardcodedSecrets(scan, relative, findings);
        }
        return List.copyOf(findings);
    }

    private void memoryLeaks(PascalSourceScan scan, String file, List<Finding> findings) {
        if (!RELEASE.matcher(scan.masked()).find()) {
            for (PascalSourceScan.Match match : scan.find(ALLOCATION)) {
                findings.add(finding("CW-PAS-BUG-001", Severity.MEDIUM, FindingCategory.BUG,
                        "memory is allocated with GetMem or New but never released with FreeMem or Dispose "
                                + "anywhere in this unit, so the allocation leaks",
                        file, match.line()));
            }
        }
    }

    private void openFiles(PascalSourceScan scan, String file, List<Finding> findings) {
        if (!FILE_CLOSE.matcher(scan.masked()).find()) {
            for (PascalSourceScan.Match match : scan.find(FILE_OPEN)) {
                findings.add(finding("CW-PAS-BUG-002", Severity.MEDIUM, FindingCategory.BUG,
                        "a file is opened with Reset, Rewrite or Append but CloseFile is never called in this "
                                + "unit, so the handle leaks",
                        file, match.line()));
            }
        }
    }

    private void emptyExcept(PascalSourceScan scan, String file, List<Finding> findings) {
        for (PascalSourceScan.Match match : scan.find(EMPTY_EXCEPT)) {
            findings.add(finding("CW-PAS-CORRECT-001", Severity.LOW, FindingCategory.CORRECTNESS,
                    "an except block has no statements, so every exception it catches is silently swallowed",
                    file, match.line()));
        }
    }

    private void withStatements(PascalSourceScan scan, String file, List<Finding> findings) {
        for (PascalSourceScan.Match match : scan.find(WITH_STATEMENT)) {
            findings.add(finding("CW-PAS-MAINT-001", Severity.LOW, FindingCategory.MAINTAINABILITY,
                    "a with statement makes the owner of each bare field name implicit, which hides shadowing "
                            + "and makes the code harder to change safely",
                    file, match.line()));
        }
    }

    private void gotoStatements(PascalSourceScan scan, String file, List<Finding> findings) {
        for (PascalSourceScan.Match match : scan.find(GOTO_STATEMENT)) {
            findings.add(finding("CW-PAS-STYLE-001", Severity.LOW, FindingCategory.STYLE,
                    "a goto statement makes control flow hard to follow and is rarely needed in Pascal",
                    file, match.line()));
        }
    }

    private void sqlInjection(PascalSourceScan scan, String file, List<Finding> findings) {
        String masked = scan.masked();
        int line = 1;
        for (String text : masked.split("\\R", -1)) {
            if (SQL_STATEMENT.matcher(text).find()
                    && (text.contains("+") || text.toLowerCase(Locale.ROOT).contains("format(")
                            || text.contains("%s") || text.toLowerCase(Locale.ROOT).contains("quotedstr"))) {
                findings.add(finding("CW-PAS-SEC-001", Severity.HIGH, FindingCategory.SECURITY,
                        "a SQL statement is assembled by concatenation or Format, so a value that reaches it can "
                                + "change the statement rather than be compared by it",
                        file, line));
            }
            line++;
        }
    }

    private void hardcodedSecrets(PascalSourceScan scan, String file, List<Finding> findings) {
        for (PascalSourceScan.Match match : scan.findRaw(SECRET_ASSIGNMENT)) {
            String value = match.captured();
            if (value == null || value.isBlank()) {
                continue;
            }
            findings.add(finding("CW-PAS-SEC-002", Severity.MEDIUM, FindingCategory.SECURITY,
                    "a credential is written into the source as a literal, so it is visible to anyone who can "
                            + "read the repository and cannot be rotated without a rebuild",
                    file, match.line()));
        }
    }

    private static Finding finding(
            String ruleId, Severity severity, FindingCategory category, String message,
            String file, int line) {
        return new Finding(ID, ruleId, severity, category, message, file, line, null, Map.of());
    }

    private static String relativeTo(Path root, Path file) {
        try {
            return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException _) {
            return file.toString();
        }
    }
}

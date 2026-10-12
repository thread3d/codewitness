package dev.codewitness.sql.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.sql.SqlProjectDetector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Security-oriented SQL rules, read over the literal-aware view of each script.
 *
 * <p>The rules cover the statements that reach outside the database's own data: dynamic SQL built at run
 * time ({@code EXECUTE IMMEDIATE}), an operating-system command channel ({@code xp_cmdshell}), a
 * privilege granted to every user ({@code GRANT ... TO PUBLIC}), loading a native extension, and
 * attaching another database file.
 *
 * <p>Like the correctness table, every finding is advisory: the analyzer's id contains
 * {@code heuristics}, so the core records a match as a hint rather than a demonstration. That is the
 * honest reading of a regular expression - it recognises the shape of a dangerous statement, not the
 * existence of an exploitable one - and it means a requirement is never failed on this analyzer's word
 * alone. A schema that genuinely must build dynamic SQL can still be verified by executing it.
 */
public final class SqlSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    /** Analyzer id; the word "heuristics" is what keeps its findings advisory in the core. */
    public static final String ID = "sql-security-heuristics";

    private static final Pattern EXECUTE_IMMEDIATE = Pattern.compile("(?is)\\bexecute\\s+immediate\\b");
    private static final Pattern XP_CMDSHELL = Pattern.compile("(?is)\\bxp_cmdshell\\b");
    private static final Pattern GRANT_TO_PUBLIC = Pattern.compile("(?is)\\bgrant\\b[^;]*\\bto\\s+public\\b");
    private static final Pattern LOAD_EXTENSION = Pattern.compile("(?is)\\bload_extension\\b");
    private static final Pattern ATTACH_DATABASE = Pattern.compile("(?is)\\battach\\s+(?:database\\s+)?['\"]?");
    private static final Pattern PRAGMA_WRITABLE_SCHEMA = Pattern.compile("(?is)\\bpragma\\s+writable_schema\\b");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SQL);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : SqlProjectDetector.schemaFiles(context.projectRoot())) {
            String source = SqlProjectDetector.readString(file);
            if (source.isBlank()) {
                continue;
            }
            SqlSourceScan scan = SqlSourceScan.of(source);
            String relative = relative(context, file);
            findings.addAll(matches(EXECUTE_IMMEDIATE, scan, relative, "sql-execute-immediate", Severity.HIGH,
                    "dynamic SQL executed from a string; if any part of that string comes from outside the "
                            + "database it is an injection point"));
            findings.addAll(matches(XP_CMDSHELL, scan, relative, "sql-os-command", Severity.CRITICAL,
                    "xp_cmdshell runs operating-system commands from SQL and is disabled by default for a "
                            + "reason"));
            findings.addAll(matches(GRANT_TO_PUBLIC, scan, relative, "sql-grant-public", Severity.HIGH,
                    "a privilege granted TO PUBLIC is held by every user, including ones added later"));
            findings.addAll(matches(LOAD_EXTENSION, scan, relative, "sql-load-extension", Severity.MEDIUM,
                    "loading a native extension runs code outside the database engine"));
            findings.addAll(matches(ATTACH_DATABASE, scan, relative, "sql-attach-database", Severity.MEDIUM,
                    "ATTACH reaches a database file outside this one, which widens what a query can touch"));
            findings.addAll(matches(PRAGMA_WRITABLE_SCHEMA, scan, relative, "sql-writable-schema",
                    Severity.HIGH,
                    "writable_schema lets the schema itself be edited directly and can corrupt the database"));
        }
        return List.copyOf(findings);
    }

    private static List<Finding> matches(
            Pattern pattern, SqlSourceScan scan, String file, String rule, Severity severity, String message) {
        List<Finding> findings = new ArrayList<>();
        Matcher matcher = pattern.matcher(scan.masked());
        while (matcher.find()) {
            findings.add(new Finding(ID, rule, severity, FindingCategory.SECURITY, message, file,
                    scan.lineAt(matcher.start()), null, Map.of()));
        }
        return findings;
    }

    private static String relative(VerificationContext context, Path file) {
        Path root = context.projectRoot();
        return file.startsWith(root) ? root.relativize(file).toString() : file.toString();
    }
}

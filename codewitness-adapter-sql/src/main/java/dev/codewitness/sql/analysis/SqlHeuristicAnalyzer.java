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
 * A SQL rule table, read over the literal-aware view of each script.
 *
 * <p>The rules cover the mistakes a database accepts: a {@code SELECT *} that breaks when a column is
 * added, an {@code UPDATE} or {@code DELETE} with no {@code WHERE} that rewrites or empties a table, a
 * comparison with {@code NULL} that is never true, a {@code NOT IN} over a subquery that can return
 * {@code NULL} and therefore rejects everything, a {@code DROP} with no guard, and a table declared
 * without a primary key.
 *
 * <p>Every finding is advisory. The analyzer's id contains {@code heuristics}, which is what tells the
 * core that a match is a hint rather than a demonstration, so a rule that is wrong about a legitimate
 * schema cannot fail a requirement. Each finding names the file and line so a reader can check it.
 */
public final class SqlHeuristicAnalyzer implements VerificationTools.StaticAnalyzer {

    /** Analyzer id; the word "heuristics" is what keeps its findings advisory in the core. */
    public static final String ID = "sql-heuristics";

    private static final Pattern SELECT_STAR = Pattern.compile("(?is)\\bselect\\s+\\*");
    private static final Pattern UPDATE = Pattern.compile("(?is)\\bupdate\\s+[\\w\"`\\[\\].]+\\s+set\\b");
    private static final Pattern DELETE = Pattern.compile("(?is)\\bdelete\\s+from\\s+[\\w\"`\\[\\].]+");
    private static final Pattern WHERE = Pattern.compile("(?is)\\bwhere\\b");
    private static final Pattern NOT_IN_SUBQUERY = Pattern.compile("(?is)\\bnot\\s+in\\s*\\(\\s*select\\b");
    private static final Pattern NULL_COMPARISON =
            Pattern.compile("(?is)(?:[=<>!]\\s*null\\b|\\bnull\\s*(?:[=<>!]|\\bis\\s+not\\b))");
    private static final Pattern DROP = Pattern.compile("(?is)\\bdrop\\s+(?:table|column|index|view|trigger)\\b");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)\\bcreate\\s+(?:temp(?:orary)?\\s+)?table\\b");
    private static final Pattern PRIMARY_KEY = Pattern.compile("(?is)\\bprimary\\s+key\\b");

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
            findings.addAll(selectStar(scan, relative));
            findings.addAll(withoutWhere(scan, relative));
            findings.addAll(nullSemantics(scan, relative));
            findings.addAll(destructive(scan, relative));
            findings.addAll(primaryKey(scan, relative));
        }
        return List.copyOf(findings);
    }

    private List<Finding> selectStar(SqlSourceScan scan, String file) {
        return matches(SELECT_STAR, scan, file, "sql-select-star", Severity.LOW,
                FindingCategory.MAINTAINABILITY,
                "SELECT * returns every column, so adding or reordering a column changes this query's shape "
                        + "silently; name the columns it needs");
    }

    private List<Finding> withoutWhere(SqlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        findings.addAll(unbounded(UPDATE, scan, file, "sql-update-without-where",
                "UPDATE with no WHERE rewrites every row in the table"));
        findings.addAll(unbounded(DELETE, scan, file, "sql-delete-without-where",
                "DELETE with no WHERE empties the table"));
        return findings;
    }

    private List<Finding> unbounded(Pattern pattern, SqlSourceScan scan, String file, String rule, String message) {
        List<Finding> findings = new ArrayList<>();
        Matcher matcher = pattern.matcher(scan.masked());
        while (matcher.find()) {
            if (WHERE.matcher(scan.statementFrom(matcher.start())).find()) {
                continue;
            }
            findings.add(finding(rule, Severity.HIGH, FindingCategory.CORRECTNESS, file,
                    scan.lineAt(matcher.start()), message));
        }
        return findings;
    }

    private List<Finding> nullSemantics(SqlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        findings.addAll(matches(NOT_IN_SUBQUERY, scan, file, "sql-not-in-subquery", Severity.MEDIUM,
                FindingCategory.CORRECTNESS,
                "NOT IN over a subquery is false for every row when the subquery returns a NULL, so this "
                        + "predicate can silently select nothing"));
        findings.addAll(matches(NULL_COMPARISON, scan, file, "sql-null-comparison", Severity.HIGH,
                FindingCategory.CORRECTNESS,
                "comparing with NULL using = or <> is never true; use IS NULL or IS NOT NULL"));
        return findings;
    }

    private List<Finding> destructive(SqlSourceScan scan, String file) {
        return matches(DROP, scan, file, "sql-unconditional-drop", Severity.MEDIUM,
                FindingCategory.RELIABILITY,
                "DROP is unconditional; IF EXISTS keeps a re-run from failing on a database that has "
                        + "already been migrated");
    }

    private List<Finding> primaryKey(SqlSourceScan scan, String file) {
        List<Finding> findings = new ArrayList<>();
        Matcher matcher = CREATE_TABLE.matcher(scan.masked());
        while (matcher.find()) {
            String statement = scan.statementFrom(matcher.start());
            if (PRIMARY_KEY.matcher(statement).find()) {
                continue;
            }
            findings.add(finding("sql-table-without-primary-key", Severity.LOW,
                    FindingCategory.MAINTAINABILITY, file, scan.lineAt(matcher.start()),
                    "table declared without a primary key, so rows are not addressable and duplicates are "
                            + "not prevented"));
        }
        return findings;
    }

    private static List<Finding> matches(
            Pattern pattern, SqlSourceScan scan, String file, String rule, Severity severity,
            FindingCategory category, String message) {
        List<Finding> findings = new ArrayList<>();
        Matcher matcher = pattern.matcher(scan.masked());
        while (matcher.find()) {
            findings.add(finding(rule, severity, category, file, scan.lineAt(matcher.start()), message));
        }
        return findings;
    }

    private static Finding finding(
            String rule, Severity severity, FindingCategory category, String file, int line, String message) {
        return new Finding(ID, rule, severity, category, message, file, line, null, Map.of());
    }

    private static String relative(VerificationContext context, Path file) {
        Path root = context.projectRoot();
        return file.startsWith(root) ? root.relativize(file).toString() : file.toString();
    }
}

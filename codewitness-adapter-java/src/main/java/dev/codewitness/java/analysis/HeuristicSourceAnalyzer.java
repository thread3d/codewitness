package dev.codewitness.java.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic source heuristics for Java.
 *
 * <p>These rules exist because a compiler will happily accept code that is wrong in specific,
 * well known ways - discarding the result of an immutable {@code BigDecimal}, treating money as
 * {@code double}, comparing {@code BigDecimal} with {@code equals}, checking a boundary with the
 * wrong comparison, guarding shared state with check-then-act, or testing a method without asserting
 * anything. Each rule is a syntactic pattern with a documented false positive profile: findings are
 * reported with {@code MEDIUM} or {@code LOW} confidence and never on their own make a requirement
 * {@code VERIFIED}, but a {@code HIGH} finding in a file a requirement covers is reported as a
 * counterexample, because a deterministic demonstration of the defect is as good as a failing test.
 *
 * <p>This is not a replacement for a real static analyzer; it is the analyzer that is always
 * available and whose rules are auditable in one file.
 */
public final class HeuristicSourceAnalyzer implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /** Names that suggest a monetary or quantity value rather than a counter or index. */
    private static final Pattern VALUEISH = Pattern.compile(
            "(?i)(bigdecimal|amount|balance|total|price|sum|limit|money|withdrawn|fee|credit|debit|value)");

    private static final List<Rule> LINE_RULES = List.of(
            new Rule("CW-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key|auth[_-]?token|access[_-]?token)\\b\\s*=\\s*\"[^\"]{4,}\""),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a secret store."),
            new Rule("CW-SEC-002", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)(executeQuery|executeUpdate|execute)\\s*\\([^)]*\\+"),
                    "SQL statement built by string concatenation: user input can change the query. Use a parameterised statement."),
            new Rule("CW-SEC-003", Severity.MEDIUM, FindingCategory.SECURITY, true,
                    Pattern.compile("MessageDigest\\.getInstance\\(\\s*\"(MD5|SHA-?1)\""),
                    "Weak message digest (MD5/SHA-1). Use SHA-256 or better."),
            new Rule("CW-SEC-004", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(|new\\s+ProcessBuilder\\s*\\(\\s*[A-Za-z_]"),
                    "External process execution with a computed command: input reaching this call allows arbitrary command execution."),
            new Rule("CW-NUM-002", Severity.HIGH, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("\\b(?:double|float)\\s+\\w*(?i:(amount|balance|price|money|total|fee|rate))\\w*"),
                    "Binary floating point used for a monetary value: use BigDecimal to avoid rounding errors."),
            new Rule("CW-NUM-003", Severity.MEDIUM, FindingCategory.BUG, false,
                    Pattern.compile("\\w*(?i:(amount|balance|total|price|limit))\\w*\\.equals\\s*\\("),
                    "BigDecimal.equals also compares scale, so 1.0 is not equal to 1.00; use compareTo for numeric comparison."),
            new Rule("CW-CONC-002", Severity.MEDIUM, FindingCategory.CONCURRENCY, false,
                    Pattern.compile("(?i)static\\s+final\\s+SimpleDateFormat|(?i)static\\s+SimpleDateFormat"),
                    "SimpleDateFormat is not thread safe; sharing it between threads produces corrupted dates."),
            new Rule("CW-DATE-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("ChronoUnit\\.DAYS|toLocalDate\\(\\)\\s*;|LocalDate\\.now\\(\\)"),
                    "Calendar-based date arithmetic: a limit expressed over a rolling window is not the same as a limit "
                            + "per calendar day. Check this against the requirement."));
    private static final Pattern DISCARDED_RESULT = Pattern.compile(
            "^\\s*((?:[A-Za-z_$][\\w$]*\\.)*[A-Za-z_$][\\w$]*|new\\s+[\\w.$]+)\\s*\\.\\s*(add|subtract|multiply|divide|setScale|negate|abs|stripTrailingZeros)\\s*\\(.*\\)\\s*;\\s*$");
    private static final Pattern COLLECTION_RECEIVER = Pattern.compile(
            "(?i)(list|set|map|queue|deque|collection|arraylist|hashmap|hashset|builder|buffer|stack|tasks|results|findings|names|items)$");
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
    private static final Pattern TEST_METHOD = Pattern.compile(
            "@(?:org\\.junit\\.jupiter\\.api\\.)?Test\\b[\\s\\S]{0,400}?\\b(?:void|public|private|protected)\\s+\\w+\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s*\\}", Pattern.MULTILINE);
    private static final Pattern ASSERTION = Pattern.compile(
            "assert\\w*\\s*\\(|Assertions\\.|Assert\\.|fail\\s*\\(|assertThat\\s*\\(|verify\\s*\\(|expect\\s*\\(|assertThrows|assertAll");
    private static final Pattern PRIVATE_FIELD = Pattern.compile(
            "(?m)^\\s*private\\s+(?!static\\s+final|final)\\w[\\w.<>\\[\\]]*\\s+(\\w+)\\s*(?:=[^;]*)?;");
    private static final Pattern IF_STATEMENT = Pattern.compile("(?m)^\\s*if\\s*\\(");

    /** A single syntactic rule. */
    private record Rule(
            String id,
            Severity severity,
            FindingCategory category,
            boolean security,
            Pattern pattern,
            String message) {
    }

    @Override
    public String id() {
        return "heuristics";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA);
    }

    /**
     * Reports every non-security finding: correctness heuristics in main sources plus tests that
     * cannot fail.
     */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : javaFiles(context, false)) {
            analyseFile(context, file, false, findings);
        }
        findings.addAll(analyzeTests(context));
        return findings.stream().filter(f -> !isSecurityRule(f.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : javaFiles(context, false)) {
            analyseFile(context, file, false, findings);
        }
        findings.addAll(analyzeTests(context));
        return List.copyOf(findings);
    }

    /** @return {@code true} when the rule is a security rule. */
    public static boolean isSecurityRule(String ruleId) {
        return ruleId != null && ruleId.startsWith("CW-SEC");
    }

    private List<Path> javaFiles(VerificationContext context, boolean tests) {
        List<Path> files = new ArrayList<>();
        List<Path> roots = tests ? context.projectInfo().testSourceDirs() : context.projectInfo().sourceDirs();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !dev.codewitness.java.generate.JavaGeneratedTestRunner.isGeneratedSource(p))
                        .sorted()
                        .forEach(files::add);
            } catch (IOException ignored) {
                // unreadable tree: nothing to analyse
            }
        }
        return files;
    }

    private void analyseFile(VerificationContext context, Path file, boolean testTree, List<Finding> findings) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        String relative = relativePath(context, file);
        boolean mentionsBigDecimal = content.contains("BigDecimal");
        String[] lines = content.split("\\R", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.stripLeading().startsWith("//") || line.stripLeading().startsWith("*")) {
                continue;
            }
            for (Rule rule : LINE_RULES) {
                Matcher matcher = rule.pattern().matcher(line);
                if (matcher.find()) {
                    findings.add(new Finding("heuristics", rule.id(), rule.severity(), rule.category(),
                            rule.message(), relative, i + 1, null, Map.of("snippet", line.strip())));
                }
            }
            Matcher discarded = DISCARDED_RESULT.matcher(line);
            if (discarded.matches() && mentionsBigDecimal) {
                String receiver = discarded.group(1);
                String simple = receiver.contains(".")
                        ? receiver.substring(receiver.lastIndexOf('.') + 1) : receiver;
                boolean looksLikeCollection = COLLECTION_RECEIVER.matcher(simple).find();
                boolean looksLikeValue = VALUEISH.matcher(receiver).find();
                if (!looksLikeCollection && looksLikeValue) {
                    findings.add(new Finding("heuristics", "CW-NUM-001", Severity.HIGH, FindingCategory.BUG,
                            "The result of BigDecimal." + discarded.group(2) + "(...) is discarded. BigDecimal is "
                                    + "immutable, so this call changes nothing; the bug is silent and the compiler "
                                    + "does not report it.",
                            relative, i + 1, null, Map.of("snippet", line.strip())));
                }
            }
        }

        Matcher emptyCatch = EMPTY_CATCH.matcher(content);
        while (emptyCatch.find()) {
            findings.add(new Finding("heuristics", "CW-REL-001", Severity.MEDIUM, FindingCategory.RELIABILITY,
                    "Empty catch block: the failure is silently discarded.",
                    relative, lineOf(content, emptyCatch.start()), null, Map.of()));
        }

        if (!testTree && content.contains("if") && !content.contains("synchronized")) {
            Matcher field = PRIVATE_FIELD.matcher(content);
            while (field.find()) {
                String name = field.group(1);
                Pattern assignment = Pattern.compile("(?m)^\\s*(?:this\\.)?" + Pattern.quote(name) + "\\s*=[^=]");
                if (IF_STATEMENT.matcher(content).find() && assignment.matcher(content).find()) {
                    findings.add(new Finding("heuristics", "CW-CONC-001", Severity.MEDIUM,
                            FindingCategory.CONCURRENCY,
                            "Field '" + name + "' is read in a condition and written in the same class without any "
                                    + "synchronisation: two threads can pass the check before either writes, which is "
                                    + "a check-then-act race.",
                            relative, lineOf(content, field.start()), null, Map.of("field", name)));
                    break;
                }
            }
        }
    }

    /** Analyses test sources for tests that cannot fail, which is a real verification hazard. */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : javaFiles(context, true)) {
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            String relative = relativePath(context, file);
            Matcher matcher = TEST_METHOD.matcher(content);
            while (matcher.find()) {
                String body = matcher.group(1);
                if (!ASSERTION.matcher(body).find() && body.strip().length() > 0) {
                    findings.add(new Finding("heuristics", "CW-TEST-001", Severity.MEDIUM,
                            FindingCategory.MAINTAINABILITY,
                            "This test has no assertion, so it passes whatever the code does. A passing test that "
                                    + "cannot fail is worse than no test: it looks like evidence.",
                            relative, lineOf(content, matcher.start()), null, Map.of()));
                }
            }
        }
        return findings;
    }

    private static String relativePath(VerificationContext context, Path file) {
        Path root = context.projectRoot();
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }

    private static int lineOf(String content, int index) {
        int line = 1;
        for (int i = 0; i < index && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** @return the identifiers of every rule this analyzer implements, for documentation and tests. */
    public static List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        LINE_RULES.forEach(rule -> ids.add(rule.id()));
        ids.add("CW-NUM-001");
        ids.add("CW-REL-001");
        ids.add("CW-CONC-001");
        ids.add("CW-TEST-001");
        return List.copyOf(ids);
    }

    /** @return the rule severity, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        return LINE_RULES.stream().filter(r -> r.id().equals(ruleId)).map(Rule::severity)
                .findFirst().orElse(Severity.MEDIUM);
    }

}

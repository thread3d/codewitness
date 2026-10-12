package dev.codewitness.kotlin.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
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

/**
 * Deterministic source heuristics for Kotlin.
 *
 * <p>These rules exist because the Kotlin compiler will happily accept code that is wrong in specific,
 * well known ways - discarding the result of an immutable {@code BigDecimal}, treating money as
 * {@code Double}, comparing BigDecimals with {@code ==}, letting a date be truncated to a calendar day,
 * swallowing a failure with {@code runCatching}, or writing a test that asserts nothing. Each rule is a
 * syntactic pattern with a documented false positive profile: a finding is reported with {@code MEDIUM} or
 * {@code LOW} confidence and never on its own makes a requirement {@code VERIFIED}, but a {@code HIGH}
 * finding in a file a requirement covers is reported as a counterexample, because a deterministic
 * demonstration of the defect is as good as a failing test.
 *
 * <p>Everything is read through {@link KotlinSourceScan}, so a rule cannot fire on text that only looks like
 * code: a template hole, a raw string and a commented-out line are all invisible to the rules that read code,
 * and a commented-out assignment is invisible to the rules that read literals.
 *
 * <p>This is not a replacement for a real static analyzer; it is the analyzer that is always available and
 * whose rules are auditable in one file.
 */
public final class KotlinHeuristicAnalyzer implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /** Which reading of the source a rule is written against. */
    private enum Projection {
        /** Only code, with comments and every literal blanked out of the same-length text. */
        CODE,
        /** Everything except comments: needed by a rule whose pattern contains a literal. */
        LITERALS
    }

    /** Names that suggest a monetary or quantity value rather than a counter or index. */
    private static final Pattern VALUEISH = Pattern.compile(
            "(?i)(bigdecimal|amount|balance|total|price|sum|limit|money|withdrawn|fee|credit|debit|value)");

    private static final List<Rule> LINE_RULES = List.of(
            new Rule("CW-KT-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key|auth[_-]?token|access[_-]?token)\\b\\s*[:=]\\s*\"[^\"]{4,}\""),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a secret store."),
            new Rule("CW-KT-SEC-002", Severity.MEDIUM, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("MessageDigest\\.getInstance\\(\\s*\"(MD5|SHA-?1)\""),
                    "Weak message digest (MD5/SHA-1). Use SHA-256 or better."),
            new Rule("CW-KT-SEC-003", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(|ProcessBuilder\\s*\\(\\s*[A-Za-z_]"),
                    "External process execution with a computed command: input reaching this call allows arbitrary command execution."),
            new Rule("CW-KT-SEC-004", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?i)\\b(select|insert|update|delete|drop)\\b[^\\n]*(\\+|\\$\\{|\\$[A-Za-z_])"),
                    "SQL statement built by interpolation or concatenation: user input can change the query. Use a "
                            + "parameterised statement."),
            new Rule("CW-KT-NUM-002", Severity.HIGH, FindingCategory.CORRECTNESS, Projection.CODE,
                    Pattern.compile("\\b(?:val|var)\\s+\\w*(?i:(amount|balance|price|money|total|fee|rate))\\w*\\s*:[^=\\n]*\\b(?:Double|Float)\\b"),
                    "Binary floating point used for a monetary value: use BigDecimal to avoid rounding errors."),
            new Rule("CW-KT-NUM-003", Severity.MEDIUM, FindingCategory.BUG, Projection.CODE,
                    Pattern.compile("\\w*(?i:(amount|balance|total|price|limit))\\w*\\s*\\.equals\\s*\\(|"
                            + "\\w*(?i:(amount|balance|total|price|limit))\\w*\\s*==\\s*\\w*(?i:(amount|balance|total|price|limit))\\w*"),
                    "BigDecimal.equals and == compare the scale as well as the value, so 1.0 is not equal to 1.00; "
                            + "use compareTo for a numeric comparison."),
            new Rule("CW-KT-DATE-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, Projection.CODE,
                    Pattern.compile("ChronoUnit\\.DAYS|toLocalDate\\(\\)|LocalDate\\.now\\(\\)|LocalDate\\.of\\s*\\("),
                    "Calendar-based date arithmetic: a limit expressed over a rolling window is not the same as a limit "
                            + "per calendar day. Check this against the requirement."),
            new Rule("CW-KT-CONC-002", Severity.MEDIUM, FindingCategory.CONCURRENCY, Projection.CODE,
                    Pattern.compile("SimpleDateFormat"),
                    "SimpleDateFormat is not thread safe; sharing it between threads produces corrupted dates."),
            new Rule("CW-KT-REL-002", Severity.LOW, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("runCatching\\s*\\{[^{}]*}\\s*\\.\\s*(?:getOrNull|getOrDefault|getOrElse)\\s*\\("),
                    "The failure of the wrapped call is discarded: a runCatching result defaulted away turns an error "
                            + "into an ordinary value. Handle the exception or let it propagate."));

    /** {@code runningTotal.add(fee)} used as a statement: the result is lost. */
    private static final Pattern DISCARDED_RESULT = Pattern.compile(
            "^\\s*((?:[A-Za-z_][\\w]*\\.)*[A-Za-z_][\\w]*)\\s*\\.\\s*(add|subtract|multiply|divide|setScale|negate|abs|stripTrailingZeros)\\s*\\(.*\\)\\s*$");
    private static final Pattern COLLECTION_RECEIVER = Pattern.compile(
            "(?i)(list|set|map|queue|deque|collection|arraylist|hashmap|hashset|builder|buffer|stack|tasks|results|findings|names|items)$");
    /**
     * A property or local the file itself declares as a collection.
     *
     * <p>A name that merely ends in {@code -s} is not enough: {@code amounts.add(amount)} is a mutable
     * collection's own method and cannot be a discarded {@code BigDecimal} result, while
     * {@code total.add(fee)} on a {@code BigDecimal} is exactly the silent no-op this rule exists for. The
     * declaration decides, which is why the file is read rather than the name guessed at.
     */
    private static final Pattern COLLECTION_DECLARATION = Pattern.compile(
            "(?m)\\b(?:val|var)\\s+(\\w+)\\s*(?::[^=\\n]*)?=\\s*"
                    + "(?:java\\.util\\.)?(?:ArrayList|LinkedList|HashMap|HashSet|LinkedHashMap|LinkedHashSet"
                    + "|ArrayDeque|PriorityQueue|mutableListOf|mutableSetOf|mutableMapOf)\\b"
                    + "|(?m)\\b(?:val|var)\\s+(\\w+)\\s*:\\s*(?:Mutable)?(?:List|Set|Map|Collection)\\s*<");

    /** {@code catch (e: Exception) { ... }} with nothing in it. */
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}");

    /** A JUnit 5 test function and its body, which is where an assertion has to be. */
    private static final Pattern TEST_FUNCTION = Pattern.compile(
            "@(?:org\\.junit\\.jupiter\\.api\\.)?Test\\b[\\s\\S]{0,200}?\\bfun\\s+\\w+\\s*\\([^)]*\\)\\s*(?::\\s*\\w+\\s*)?\\{([\\s\\S]*?)\\n\\s*}",
            Pattern.MULTILINE);
    private static final Pattern ASSERTION = Pattern.compile(
            "assert\\w*\\s*\\(|Assertions\\.|Assert\\.|fail\\s*\\(|assertThat\\s*\\(|shouldBe\\b|shouldThrow\\b|"
                    + "expectThat\\s*\\(|assertFailsWith|assertAll|verify\\s*\\(|check\\s*\\(|require\\s*\\(");

    /** A mutable Kotlin property: {@code var balance: Long = 0} or {@code private var open = false}. */
    private static final Pattern MUTABLE_PROPERTY = Pattern.compile(
            "(?m)^\\s*(?:(?:private|internal|public|protected)\\s+)?var\\s+(\\w+)\\s*(?::[^=\\n]+)?(?:=[^=\\n]*)?$");
    private static final Pattern IF_STATEMENT = Pattern.compile("(?m)^\\s*if\\s*\\(");

    /** A single syntactic rule. */
    private record Rule(
            String id,
            Severity severity,
            FindingCategory category,
            Projection projection,
            Pattern pattern,
            String message) {
    }

    @Override
    public String id() {
        return "kotlin-heuristics";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.KOTLIN);
    }

    /** @return every non-security finding: correctness heuristics plus tests that cannot fail. */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return findAll(context).stream().filter(finding -> !isSecurityRule(finding.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : kotlinFiles(context, false)) {
            analyseFile(context, file, findings);
        }
        findings.addAll(analyzeTests(context));
        return List.copyOf(findings);
    }

    /** @return the security subset of {@link #findAll(VerificationContext)}, for the security analyzer. */
    public List<Finding> findSecurityFindings(VerificationContext context) {
        return findAll(context).stream().filter(finding -> isSecurityRule(finding.ruleId())).toList();
    }

    /** @return {@code true} when the rule is a security rule. */
    public static boolean isSecurityRule(String ruleId) {
        return ruleId != null && ruleId.startsWith("CW-KT-SEC");
    }

    private List<Path> kotlinFiles(VerificationContext context, boolean tests) {
        List<Path> files = new ArrayList<>();
        List<Path> roots = tests ? context.projectInfo().testSourceDirs() : context.projectInfo().sourceDirs();
        for (Path root : roots) {
            for (Path file : dev.codewitness.kotlin.KotlinProjectDetector.sourceFiles(root)) {
                // A generated test is scaffolding this tool wrote for this run; analysing it would report
                // this run's own file as a finding about the project.
                if (!dev.codewitness.kotlin.generate.KotlinGeneratedTestRunner.isGeneratedSource(file)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private void analyseFile(VerificationContext context, Path file, List<Finding> findings) {
        String content = read(file);
        if (content.isEmpty()) {
            return;
        }
        String relative = relativePath(context, file);
        // Two readings of the same source, both the same length as it and both keeping its newlines: rules
        // that look for statements read the code alone, and rules that read a literal read everything except
        // the comments.
        String code = KotlinSourceScan.codeOnly(content);
        String literals = KotlinSourceScan.withoutComments(content);
        boolean mentionsBigDecimal = content.contains("BigDecimal");
        java.util.Set<String> collections = declaredCollections(code);
        String[] codeLines = code.split("\\R", -1);
        String[] literalLines = literals.split("\\R", -1);

        for (int i = 0; i < codeLines.length; i++) {
            for (Rule rule : LINE_RULES) {
                String subject = rule.projection() == Projection.CODE ? codeLines[i] : literalLines[i];
                if (subject == null) {
                    continue;
                }
                if (rule.pattern().matcher(subject).find()) {
                    findings.add(new Finding(id(), rule.id(), rule.severity(), rule.category(), rule.message(),
                            relative, i + 1, null, Map.of("snippet", subject.strip())));
                }
            }
            Matcher discarded = DISCARDED_RESULT.matcher(codeLines[i]);
            if (discarded.matches() && mentionsBigDecimal) {
                String receiver = discarded.group(1);
                String simple = receiver.contains(".")
                        ? receiver.substring(receiver.lastIndexOf('.') + 1) : receiver;
                if (!COLLECTION_RECEIVER.matcher(simple).find() && !collections.contains(simple)
                        && VALUEISH.matcher(receiver).find()) {
                    findings.add(new Finding(id(), "CW-KT-NUM-001", Severity.HIGH, FindingCategory.BUG,
                            "The result of BigDecimal." + discarded.group(2) + "(...) is discarded. BigDecimal is "
                                    + "immutable, so this call changes nothing; the bug is silent and the compiler "
                                    + "does not report it.",
                            relative, i + 1, null, Map.of("snippet", codeLines[i].strip())));
                }
            }
        }

        Matcher emptyCatch = EMPTY_CATCH.matcher(code);
        while (emptyCatch.find()) {
            findings.add(new Finding(id(), "CW-KT-REL-001", Severity.MEDIUM, FindingCategory.RELIABILITY,
                    "Empty catch block: the failure is silently discarded.",
                    relative, lineOf(code, emptyCatch.start()), null, Map.of()));
        }

        if (code.contains("if") && !code.contains("synchronized") && !code.contains("@Synchronized")) {
            Matcher property = MUTABLE_PROPERTY.matcher(code);
            while (property.find()) {
                String name = property.group(1);
                Pattern assignment = Pattern.compile("(?m)^\\s*(?:this\\.)?" + Pattern.quote(name) + "\\s*=[^=]");
                if (IF_STATEMENT.matcher(code).find() && assignment.matcher(code).find()) {
                    findings.add(new Finding(id(), "CW-KT-CONC-001", Severity.MEDIUM,
                            FindingCategory.CONCURRENCY,
                            "Property '" + name + "' is read in a condition and written in the same class without "
                                    + "any synchronisation: two threads can pass the check before either writes, "
                                    + "which is a check-then-act race.",
                            relative, lineOf(code, property.start()), null, Map.of("property", name)));
                    break;
                }
            }
        }
    }

    /** Analyses test sources for tests that cannot fail, which is a real verification hazard. */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : kotlinFiles(context, true)) {
            String content = read(file);
            if (content.isEmpty()) {
                continue;
            }
            String relative = relativePath(context, file);
            String code = KotlinSourceScan.codeOnly(content);
            Matcher matcher = TEST_FUNCTION.matcher(code);
            while (matcher.find()) {
                String body = matcher.group(1);
                if (!ASSERTION.matcher(body).find() && !body.isBlank()) {
                    findings.add(new Finding(id(), "CW-KT-TEST-001", Severity.MEDIUM,
                            FindingCategory.MAINTAINABILITY,
                            "This test has no assertion, so it passes whatever the code does. A passing test that "
                                    + "cannot fail is worse than no test: it looks like evidence.",
                            relative, lineOf(code, matcher.start()), null, Map.of()));
                }
            }
        }
        return findings;
    }

    /** @return the names a file declares as a collection, so that their own methods are not mistaken. */
    private static java.util.Set<String> declaredCollections(String code) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = COLLECTION_DECLARATION.matcher(code);
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
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
        ids.add("CW-KT-NUM-001");
        ids.add("CW-KT-REL-001");
        ids.add("CW-KT-CONC-001");
        ids.add("CW-KT-TEST-001");
        return List.copyOf(ids);
    }

    /** @return the rule severity, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        return LINE_RULES.stream().filter(rule -> rule.id().equals(ruleId)).map(Rule::severity)
                .findFirst().orElse(Severity.MEDIUM);
    }
}

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                    "External process execution with a computed command: input reaching this call allows arbitrary command execution.",
                    // Code only. A generator that *emits* a ProcessBuilder has that text in a string literal, and a
                    // finding about a template is a finding about the text, not about the program: the SQL rules are
                    // the opposite case, because their evidence is the literal itself.
                    true),
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
    /** A declaration that gives a name the immutable {@code BigDecimal} type. */
    private static final Pattern BIGDECIMAL_NAME = Pattern.compile(
            "\\bBigDecimal\\s+([A-Za-z_$][\\w$]*)\\s*[=;,)]");
    private static final Pattern DISCARDED_RESULT = Pattern.compile(
            "^\\s*((?:[A-Za-z_$][\\w$]*\\.)*[A-Za-z_$][\\w$]*|new\\s+[\\w.$]+)\\s*\\.\\s*(add|subtract|multiply|divide|setScale|negate|abs|stripTrailingZeros)\\s*\\(.*\\)\\s*;\\s*$");
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
    /** A {@code @Test} method, capturing the name of the method the annotation applies to. */
    private static final Pattern TEST_METHOD_HEADER = Pattern.compile(
            "@(?:org\\.junit\\.jupiter\\.api\\.)?Test\\b[\\s\\S]{0,1000}?\\bvoid\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    /** What may stand between a parameter list and the method body: an optional {@code throws} clause. */
    private static final Pattern METHOD_BODY_OPENER = Pattern.compile(
            "^\\s*(?:throws\\s+[\\w.$]+(?:\\s*,\\s+[\\w.$]+)*\\s*)?\\{");
    private static final Pattern ASSERTION = Pattern.compile(
            "assert\\w*\\s*\\(|Assertions\\.|Assert\\.|fail\\s*\\(|assertThat\\s*\\(|verify\\s*\\(|expect\\s*\\(|assertThrows|assertAll|AssertionError\\b");
    /**
     * Replaces the contents of every string literal on a line with spaces, leaving the quotes and the code around
     * them where they were.
     *
     * <p>A rule marked code-only is asking about the program, and text inside a literal is not the program. The case
     * that made this necessary is a generator: it emits {@code new ProcessBuilder(...)} as a string, and a rule that
     * reports it is reporting on the generator's output rather than on the generator. Quotes are kept so that a rule
     * that looks for an unterminated literal still sees the same shape.
     *
     * @param line one source line
     * @return the same line with literal contents blanked
     */
    private static String blankLiterals(String line) {
        StringBuilder out = new StringBuilder(line.length());
        boolean inLiteral = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inLiteral) {
                if (escaped) {
                    escaped = false;
                    out.append(' ');
                } else if (c == '\\') {
                    escaped = true;
                    out.append(' ');
                } else if (c == '"') {
                    inLiteral = false;
                    out.append('"');
                } else {
                    out.append(' ');
                }
            } else if (c == '"') {
                inLiteral = true;
                out.append('"');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * @param content a Java source file
     * @return the character ranges occupied by the bodies of classes whose name ends in {@code Builder}
     *
     * <p>A builder is a thread-confined accumulator: it is created, filled and read by the thread that wants the
     * object, and sharing one is a bug in the caller. The check-then-act shape this rule looks for therefore means
     * nothing inside one, and flagging every builder in a codebase is how a rule teaches its readers to ignore it.
     */
    private static java.util.List<int[]> builderRanges(String content) {
        java.util.List<int[]> ranges = new java.util.ArrayList<>();
        Matcher declaration = Pattern.compile("\\bclass\\s+\\w*Builder\\b[^{]*\\{").matcher(content);
        while (declaration.find()) {
            int open = content.indexOf('{', declaration.start());
            int depth = 0;
            for (int i = open; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        ranges.add(new int[] {open, i});
                        break;
                    }
                }
            }
        }
        return ranges;
    }

    /**
     * @param ranges ranges from {@link #builderRanges}
     * @param offset a character offset
     * @return {@code true} when the offset sits inside one of them
     */
    private static boolean insideAny(java.util.List<int[]> ranges, int offset) {
        for (int[] range : ranges) {
            if (offset >= range[0] && offset <= range[1]) {
                return true;
            }
        }
        return false;
    }

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
            String message,
            boolean codeOnly) {

        /** A rule that is matched against the whole line, literals included. */
        Rule(String id, Severity severity, FindingCategory category, boolean security, Pattern pattern,
                String message) {
            this(id, severity, category, security, pattern, message, false);
        }
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
            } catch (IOException _) {
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
        Set<String> bigDecimalNames = declaredBigDecimals(content);
        String[] lines = content.split("\\R", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.stripLeading().startsWith("//") || line.stripLeading().startsWith("*")) {
                continue;
            }
            String code = null;
            for (Rule rule : LINE_RULES) {
                if (rule.codeOnly()) {
                    if (code == null) {
                        code = blankLiterals(line);
                    }
                    Matcher matcher = rule.pattern().matcher(code);
                    if (matcher.find()) {
                        findings.add(new Finding("heuristics", rule.id(), rule.severity(), rule.category(),
                                rule.message(), relative, i + 1, null, Map.of("snippet", line.strip())));
                    }
                    continue;
                }
                Matcher matcher = rule.pattern().matcher(line);
                if (matcher.find()) {
                    findings.add(new Finding("heuristics", rule.id(), rule.severity(), rule.category(),
                            rule.message(), relative, i + 1, null, Map.of("snippet", line.strip())));
                }
            }
            Matcher discarded = DISCARDED_RESULT.matcher(line);
            if (discarded.matches() && isBigDecimalReceiver(discarded.group(1), bigDecimalNames)) {
                findings.add(new Finding("heuristics", "CW-NUM-001", Severity.HIGH, FindingCategory.BUG,
                        "The result of BigDecimal." + discarded.group(2) + "(...) is discarded. BigDecimal is "
                                + "immutable, so this call changes nothing; the bug is silent and the compiler "
                                + "does not report it.",
                        relative, i + 1, null, Map.of("snippet", line.strip())));
            }
        }

        Matcher emptyCatch = EMPTY_CATCH.matcher(content);
        while (emptyCatch.find()) {
            findings.add(new Finding("heuristics", "CW-REL-001", Severity.MEDIUM, FindingCategory.RELIABILITY,
                    "Empty catch block: the failure is silently discarded.",
                    relative, lineOf(content, emptyCatch.start()), null, Map.of()));
        }

        // The scan reads the code, not the text: a field declaration that appears inside a string literal is a
        // generator's template for another language, not a field of this class. The C# scenario generator holds
        // `private ulong _state;` as text, and a rule that reports it is reporting on the C# it emits.
        String code = codeOnly(content);
        if (!testTree && code.contains("if") && !code.contains("synchronized")) {
            java.util.List<int[]> builders = builderRanges(code);
            Matcher field = PRIVATE_FIELD.matcher(code);
            while (field.find()) {
                if (insideAny(builders, field.start())) {
                    continue;
                }
                String name = field.group(1);
                Pattern assignment = Pattern.compile("(?m)^\\s*(?:this\\.)?" + Pattern.quote(name) + "\\s*=[^=]");
                if (IF_STATEMENT.matcher(code).find() && assignment.matcher(code).find()) {
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

    /**
     * Analyses test sources for tests that cannot fail, which is a real verification hazard.
     *
     * <p>The body is delimited by matching braces rather than by a regex. A test that embeds a fixture
     * source as a text block contains braces on their own line, and a lazy body capture stopped at the
     * first of them, so every assertion after an embedded brace was invisible: that reported 31
     * assertion-free tests on this project's own suite, all of which assert. The scan runs on a view
     * with comments and literal contents blanked out, so an {@code assert} inside a fixture string is
     * not mistaken for an assertion of the test around it.
     */
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
            String code = codeOnly(content);
            Matcher matcher = TEST_METHOD_HEADER.matcher(code);
            while (matcher.find()) {
                int openParen = matcher.end() - 1;
                int closeParen = matchingDelimiter(code, openParen, '(', ')');
                if (closeParen < 0) {
                    continue;
                }
                Matcher opener = METHOD_BODY_OPENER.matcher(code.substring(closeParen + 1));
                if (!opener.find()) {
                    continue;
                }
                int openBrace = closeParen + opener.end();
                int closeBrace = matchingDelimiter(code, openBrace, '{', '}');
                if (closeBrace < 0) {
                    continue;
                }
                String body = code.substring(openBrace + 1, closeBrace);
                if (!body.isBlank() && !ASSERTION.matcher(body).find()) {
                    findings.add(new Finding("heuristics", "CW-TEST-001", Severity.MEDIUM,
                            FindingCategory.MAINTAINABILITY,
                            "This test has no assertion, so it passes whatever the code does. A passing test that "
                                    + "cannot fail is worse than no test: it looks like evidence.",
                            relative, lineOf(content, matcher.start(1)), null, Map.of()));
                }
            }
        }
        return findings;
    }

    /**
     * @return the names this file declares as a {@code BigDecimal}: parameters, fields and locals.
     *
     * <p>A name is only evidence that a value is immutable when the file declares it that way. Guessing
     * from the name alone reported {@code values.add(defaultLiteral(input))} on an {@code ArrayList} as
     * a discarded {@code BigDecimal.add}, because the file imports {@code BigDecimal} for a different
     * expression and "values" reads like a quantity.
     */
    private static Set<String> declaredBigDecimals(String content) {
        if (!content.contains("BigDecimal")) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        Matcher matcher = BIGDECIMAL_NAME.matcher(codeOnly(content));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** @return {@code true} when {@code receiver} is a value the file declares as a {@code BigDecimal}. */
    private static boolean isBigDecimalReceiver(String receiver, Set<String> declared) {
        String trimmed = receiver.strip();
        if (trimmed.startsWith("new ")) {
            return trimmed.endsWith("BigDecimal");
        }
        if (trimmed.startsWith("BigDecimal.") || trimmed.startsWith("java.math.BigDecimal.")) {
            return true; // a static constant, such as BigDecimal.ZERO
        }
        String simple = trimmed.contains(".")
                ? trimmed.substring(trimmed.lastIndexOf('.') + 1) : trimmed;
        return declared.contains(simple);
    }

    /**
     * Skips the comment or literal that starts at {@code index}, so callers can count braces and
     * parentheses without being fooled by text that is not code.
     *
     * @return the index just past the skipped construct, or {@code -1} when code starts there
     */
    private static int skipNonCode(String source, int index) {
        char first = source.charAt(index);
        if (first == '/' && index + 1 < source.length()) {
            char second = source.charAt(index + 1);
            if (second == '/') {
                int end = source.indexOf('\n', index);
                return end < 0 ? source.length() : end;
            }
            if (second == '*') {
                int end = source.indexOf("*/", index + 2);
                return end < 0 ? source.length() : end + 2;
            }
        }
        if (source.startsWith("\"\"\"", index)) {
            return endOfTextBlock(source, index + 3);
        }
        if (first == '"') {
            return endOfQuoted(source, index + 1, '"');
        }
        if (first == '\'') {
            return endOfQuoted(source, index + 1, '\'');
        }
        return -1;
    }

    private static int endOfTextBlock(String source, int from) {
        int index = from;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (source.startsWith("\"\"\"", index)) {
                return index + 3;
            }
            index++;
        }
        return source.length();
    }

    private static int endOfQuoted(String source, int from, char quote) {
        int index = from;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (c == quote) {
                return index + 1;
            }
            if (c == '\n') {
                return index; // an unterminated literal must not swallow the rest of the file
            }
            index++;
        }
        return source.length();
    }

    /**
     * @return the index of the delimiter closing the one at {@code openIndex}, or {@code -1} when the
     *     text does not balance. Expects the literal-blanked view, so plain counting is enough.
     */
    private static int matchingDelimiter(String code, int openIndex, char open, char close) {
        int depth = 0;
        for (int index = openIndex; index < code.length(); index++) {
            char c = code.charAt(index);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** @return {@code source} with comments and literal contents blanked out, at the same length. */
    private static String codeOnly(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            int skipped = skipNonCode(source, index);
            if (skipped < 0) {
                code.append(source.charAt(index));
                index++;
                continue;
            }
            for (int blank = index; blank < skipped; blank++) {
                code.append(source.charAt(blank) == '\n' ? '\n' : ' ');
            }
            index = skipped;
        }
        return code.toString();
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

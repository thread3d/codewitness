package dev.codewitness.rust.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.rust.RustProjectDetector;

import java.io.IOException;
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
 * Deterministic source heuristics for Rust.
 *
 * <p>Rust removes a whole class of memory bugs, so these rules look at the failure modes that remain:
 * a {@code Result} discarded with {@code unwrap}, a panic in library code, {@code todo!()} shipped by
 * accident, a widening or narrowing {@code as} cast, a monetary quantity held as a binary float, and
 * the calendar-day arithmetic that turns a rolling window into the wrong window. Each rule is a
 * syntactic pattern with a documented false-positive profile; findings never on their own make a
 * requirement {@code VERIFIED}.
 *
 * <p>This is not a replacement for Clippy; it is the analyzer that is always available, needs no
 * installation, and whose entire rule table fits in one auditable file. Clippy's own diagnostics are
 * reported separately when the tool is present.
 */
public final class RustHeuristicAnalyzer implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    private static final Pattern ASSERT_MACRO = Pattern.compile("\\bassert(_eq|_ne)?!\\s*\\(");
    private static final Pattern TEST_ATTRIBUTE = Pattern.compile("#\\[\\s*test\\s*\\]");
    private static final Pattern FN_START = Pattern.compile("(?m)^\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+(\\w+)\\s*\\(");
    private static final Pattern ANY_ASSERTION = Pattern.compile(
            "\\bassert(_eq|_ne)?!|\\bpanic!|\\bdebug_assert|should_panic|\\.unwrap_err\\s*\\("
                    + "|expect_err\\s*\\(|assert_matches!");

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-RS-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)\\b(pass(?:word|wd)?|secret|api_?key|auth_?token|access_?token|private_?key)"
                            + "\\b\\s*[:=]\\s*\""),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a secret store."),
            new Rule("CW-RS-SEC-002", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\bunsafe\\s*\\{|\\bunsafe\\s+fn\\b"),
                    "unsafe code opts out of the borrow checker: every invariant it relies on is now the "
                            + "programmer's responsibility. Justify it or remove it."),
            new Rule("CW-RS-SEC-003", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\bCommand::new\\s*\\(|\\bstd::process::Command\\b"),
                    "External command execution: any input reaching the program name or arguments is a command injection."),
            new Rule("CW-RS-SEC-004", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\btransmute\\b|\\bfrom_utf8_unchecked\\b|\\bget_unchecked(_mut)?\\b"),
                    "Unchecked reinterpretation of memory bypasses Rust's safety guarantees; a wrong "
                            + "assumption is undefined behaviour, not a panic."),
            new Rule("CW-RS-SEC-005", Severity.MEDIUM, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)\\b(md5|sha1)\\b|\\bMd5::|\\bSha1::"),
                    "Weak message digest (MD5/SHA-1). Use SHA-256 or better."),
            new Rule("CW-RS-SEC-006", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("danger_accept_invalid_certs|danger_accept_invalid_hostnames|\\bverify\\s*\\(\\s*false"),
                    "TLS certificate verification is disabled: the connection is not authenticated."),
            new Rule("CW-RS-SEC-007", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)format!\\s*\\([^\\n]*(select|insert|update|delete|drop)\\b"
                            + "|\\b(select|insert|update|delete|drop)\\b[^\\n]*format!\\s*\\("),
                    "SQL statement built by string formatting: user input can change the query. Use a "
                            + "parameterised statement."));

    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-RS-REL-001", Severity.MEDIUM, FindingCategory.RELIABILITY, false,
                    Pattern.compile("\\.\\s*unwrap\\s*\\(\\s*\\)"),
                    "unwrap() panics when the value is Err/None: in library code that turns a recoverable "
                            + "error into a crash. Handle the error or propagate it with ?."),
            new Rule("CW-RS-REL-002", Severity.MEDIUM, FindingCategory.RELIABILITY, false,
                    Pattern.compile("\\.\\s*expect\\s*\\("),
                    "expect() panics when the value is Err/None; the message is for the developer and the "
                            + "caller gets no chance to recover."),
            new Rule("CW-RS-REL-003", Severity.HIGH, FindingCategory.RELIABILITY, false,
                    Pattern.compile("\\bpanic!\\s*\\("),
                    "panic! aborts the caller's thread: an expected failure should be a returned error, not a crash."),
            new Rule("CW-RS-REL-004", Severity.HIGH, FindingCategory.BUG, false,
                    Pattern.compile("\\b(todo|unimplemented)!\\s*\\("),
                    "todo!/unimplemented! panic at runtime: this path is not implemented."),
            new Rule("CW-RS-REL-005", Severity.LOW, FindingCategory.MAINTAINABILITY, false,
                    Pattern.compile("#\\[\\s*allow\\s*\\("),
                    "A lint is suppressed here; check that the suppression is deliberate and narrow."),
            new Rule("CW-RS-BUG-001", Severity.MEDIUM, FindingCategory.BUG, false,
                    Pattern.compile("\\bas\\s+(i8|i16|i32|u8|u16|u32|f32)\\b"),
                    "A narrowing `as` cast truncates or rounds silently; use TryFrom, or check the range first."),
            new Rule("CW-RS-NUM-001", Severity.HIGH, FindingCategory.BUG, false,
                    Pattern.compile("(?i)\\b\\w*(amount|balance|price|total|fee|money|cost)\\w*\\s*:\\s*(f32|f64)\\b"
                            + "|\\b\\w*(amount|balance|price|total|fee|money|cost)\\w*\\s*=\\s*[^=\\n]*\\d+\\.\\d+"),
                    "A monetary value is held as a binary float: rounding errors accumulate and cents go "
                            + "missing. Use integer minor units or a decimal type."),
            new Rule("CW-RS-NUM-002", Severity.MEDIUM, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("==\\s*-?\\d+\\.\\d+|\\d+\\.\\d+\\s*=="),
                    "Equality on a floating point literal is rarely what is meant; compare with a tolerance."),
            new Rule("CW-RS-DATE-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("/\\s*86_?400_?000\\b|/\\s*86_?400\\b|\\bnum_days_from_ce\\b"),
                    "Calendar-based time arithmetic: a limit expressed over a rolling window is not the same "
                            + "as a limit per calendar day. Check this against the requirement."));

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
        return "rust-heuristics";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUST);
    }

    /** Reports every non-security finding: correctness heuristics plus tests that cannot fail. */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return findAll(context).stream().filter(finding -> !isSecurityRule(finding.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : sourceFiles(context)) {
            analyseFile(context, file, findings);
        }
        findings.addAll(analyzeTests(context));
        return List.copyOf(findings);
    }

    /** @return {@code true} when the rule is a security rule. */
    public static boolean isSecurityRule(String ruleId) {
        return ruleId != null && ruleId.startsWith("CW-RS-SEC");
    }

    /** @return security findings only. */
    public List<Finding> analyzeSecurity(VerificationContext context) {
        return findAll(context).stream().filter(finding -> isSecurityRule(finding.ruleId())).toList();
    }

    private static List<Path> sourceFiles(VerificationContext context) {
        List<Path> files = new ArrayList<>();
        for (Path root : context.projectInfo().sourceDirs()) {
            files.addAll(RustProjectDetector.rustFiles(root));
        }
        return files;
    }

    private void analyseFile(VerificationContext context, Path file, List<Finding> findings) {
        String content = read(file);
        if (content.isEmpty()) {
            return;
        }
        String relative = relativePath(context, file);
        boolean[] inBlockComment = {false};
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = codeOnly(lines[i], inBlockComment);
            if (line.isBlank()) {
                continue;
            }
            for (Rule rule : CORRECTNESS_RULES) {
                if (rule.pattern().matcher(line).find()) {
                    findings.add(finding(rule, relative, i + 1, line));
                }
            }
            for (Rule rule : SECURITY_RULES) {
                if (rule.pattern().matcher(line).find()) {
                    findings.add(finding(rule, relative, i + 1, line));
                }
            }
            if (ASSERT_MACRO.matcher(line).find() && !isTestFile(relative)) {
                findings.add(new Finding("rust-heuristics", "CW-RS-BUG-002", Severity.LOW,
                        FindingCategory.CORRECTNESS,
                        "assert! is disabled by debug_assert settings in release builds: it must not be the "
                                + "only guard on a production invariant.",
                        relative, i + 1, null, Map.of("snippet", line.strip())));
            }
        }
    }

    /** Analyses test sources for tests that cannot fail, which is a real verification hazard. */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path root : context.projectInfo().testSourceDirs()) {
            for (Path file : RustProjectDetector.rustFiles(root)) {
                String content = read(file);
                if (content.isEmpty()) {
                    continue;
                }
                String relative = relativePath(context, file);
                Matcher attribute = TEST_ATTRIBUTE.matcher(content);
                Matcher function = FN_START.matcher(content);
                while (attribute.find()) {
                    // The test function is the first fn after its #[test] attribute.
                    if (!function.find(attribute.end())) {
                        break;
                    }
                    String body = bodyOf(content, function.end());
                    if (!body.isBlank() && !ANY_ASSERTION.matcher(body).find()) {
                        findings.add(new Finding("rust-heuristics", "CW-RS-TEST-001", Severity.MEDIUM,
                                FindingCategory.MAINTAINABILITY,
                                "Test '" + function.group(1) + "' has no assertion, so it passes whatever the "
                                        + "code does. A passing test that cannot fail is worse than no test.",
                                relative, lineOf(content, function.start()), null, Map.of()));
                    }
                }
            }
        }
        return findings;
    }

    /** @return the body of a function starting at {@code start}, up to the next {@code fn}. */
    private static String bodyOf(String content, int start) {
        Matcher next = FN_START.matcher(content);
        int end = content.length();
        if (next.find(start)) {
            end = next.start();
        }
        return content.substring(start, end);
    }

    private static Finding finding(Rule rule, String relative, int line, String snippet) {
        return new Finding("rust-heuristics", rule.id(), rule.severity(), rule.category(),
                rule.message(), relative, line, null, Map.of("snippet", snippet.strip()));
    }

    /**
     * @return the line with comments removed, so a rule cannot fire on a comment that only describes
     *     the hazard it is looking for.
     *
     * <p>Block comment state is carried across lines in {@code inBlockComment}. String literals are
     * preserved because several security rules deliberately match credential and SQL literals.
     */
    static String codeOnly(String line, boolean[] inBlockComment) {
        StringBuilder code = new StringBuilder(line.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inBlockComment[0]) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    inBlockComment[0] = false;
                    code.append("  ");
                    i++;
                } else {
                    code.append(' ');
                }
                continue;
            }
            if (inString) {
                code.append(c);
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
                code.append(c);
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                inBlockComment[0] = true;
                code.append("  ");
                i++;
                continue;
            }
            code.append(c);
        }
        return code.toString();
    }

    static boolean isTestFile(String relative) {
        String normalized = relative == null ? "" : relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith("tests/") || normalized.contains("/tests/");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }

    static String relativePath(VerificationContext context, Path file) {
        Path root = context.projectRoot();
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }

    static int lineOf(String content, int index) {
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
        SECURITY_RULES.forEach(rule -> ids.add(rule.id()));
        CORRECTNESS_RULES.forEach(rule -> ids.add(rule.id()));
        ids.add("CW-RS-BUG-002");
        ids.add("CW-RS-TEST-001");
        return List.copyOf(ids);
    }

    /** @return the severity of a rule, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        return SECURITY_RULES.stream().filter(rule -> rule.id().equals(ruleId)).map(Rule::severity)
                .findFirst()
                .orElseGet(() -> CORRECTNESS_RULES.stream().filter(rule -> rule.id().equals(ruleId))
                        .map(Rule::severity).findFirst().orElse(Severity.MEDIUM));
    }
}

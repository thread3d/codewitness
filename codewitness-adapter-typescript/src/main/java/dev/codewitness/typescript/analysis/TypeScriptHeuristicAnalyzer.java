package dev.codewitness.typescript.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.typescript.TypeScriptProjectDetector;

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
 * Deterministic source heuristics for TypeScript.
 *
 * <p>TypeScript's type system removes a class of mistakes at compile time, but the mistakes that make
 * it through are the ones that matter here: an escape hatch that switches the type system off
 * ({@code any}, a non-null assertion, a {@code @ts-ignore}), a loose comparison, a monetary quantity
 * held as a binary float, a silently truncated {@code parseInt}, a swallowed error, and the
 * calendar-day arithmetic that turns a rolling window into the wrong window. Each rule is a syntactic
 * pattern with a documented false-positive profile; findings never on their own make a requirement
 * {@code VERIFIED}.
 *
 * <p>This is not a replacement for ESLint or the compiler; it is the analyzer that is always available,
 * needs no installation, and whose entire rule table fits in one auditable file.
 */
public final class TypeScriptHeuristicAnalyzer
        implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    private static final Pattern TEST_CALL = Pattern.compile("(?:^|[^\\w.])(?:test|it)\\s*\\(");
    private static final Pattern ANY_ASSERTION = Pattern.compile(
            "\\bassert\\b|\\.assert\\b|\\bexpect\\s*\\(|\\bshould\\b|\\.to[A-Z]|\\.toBe|\\.toEqual");

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-TS-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)\\b(pass(?:word|wd)?|secret|api_?key|auth_?token|access_?token|private_?key)"
                            + "\\b\\s*[:=]\\s*[\"'`]"),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a secret store."),
            new Rule("CW-TS-SEC-002", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\beval\\s*\\(|\\bnew\\s+Function\\s*\\(|\\bvm\\.runIn"),
                    "Dynamic code evaluation: any input reaching eval or new Function is arbitrary code execution."),
            new Rule("CW-TS-SEC-003", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("require\\s*\\(\\s*[\"']child_process|\"node:child_process\"|from\\s+[\"']node:child_process"
                            + "|\\bexec(?:Sync)?\\s*\\(|\\bspawn(?:Sync)?\\s*\\("),
                    "External command execution: any input reaching the program name or arguments is a command injection."),
            new Rule("CW-TS-SEC-004", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)(select|insert|update|delete|drop)\\b[^\\n]*(\\+|\\$\\{)"),
                    "SQL statement built by concatenation or interpolation: user input can change the query. Use a "
                            + "parameterised statement."),
            new Rule("CW-TS-SEC-005", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\.innerHTML\\s*=|document\\.write\\s*\\(|dangerouslySetInnerHTML|insertAdjacentHTML\\s*\\("),
                    "HTML written from a string: unescaped input is cross-site scripting. Assign text content or sanitise."),
            new Rule("CW-TS-SEC-006", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("rejectUnauthorized\\s*:\\s*false|NODE_TLS_REJECT_UNAUTHORIZED|\\bverify\\s*:\\s*false"),
                    "TLS certificate verification is disabled: the connection is not authenticated."),
            new Rule("CW-TS-SEC-007", Severity.MEDIUM, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)createHash\\s*\\(\\s*[\"'](md5|sha1)[\"']|\\bmd5\\s*\\(|\\bsha1\\s*\\("),
                    "Weak message digest (MD5/SHA-1). Use SHA-256 or better."));

    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-TS-REL-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("(?<![=!<>])==(?!=)|(?<![=!])!=(?!=)"),
                    "Loose equality coerces its operands, so '0' == false and '' == 0. Use === or !==."),
            new Rule("CW-TS-TYPE-001", Severity.MEDIUM, FindingCategory.MAINTAINABILITY, false,
                    Pattern.compile(":\\s*any\\b|\\bas\\s+any\\b|<any>|\\bany\\[\\]"),
                    "`any` switches the type checker off for everything it touches; use a precise type or unknown."),
            new Rule("CW-TS-TYPE-002", Severity.MEDIUM, FindingCategory.RELIABILITY, false,
                    Pattern.compile("[A-Za-z0-9_)\\]][!]\\s*[.\\[(]"),
                    "A non-null assertion tells the compiler to trust you: at runtime it is a crash waiting for the "
                            + "value it says cannot be null. Narrow the type instead."),
            new Rule("CW-TS-REL-002", Severity.LOW, FindingCategory.MAINTAINABILITY, false,
                    Pattern.compile("@ts-(ignore|nocheck|expect-error)"),
                    "A TypeScript diagnostic is suppressed here; check that the suppression is deliberate and narrow."),
            new Rule("CW-TS-REL-003", Severity.MEDIUM, FindingCategory.RELIABILITY, false,
                    Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}"),
                    "An empty catch block discards the error: the failure disappears instead of being handled."),
            new Rule("CW-TS-REL-004", Severity.MEDIUM, FindingCategory.RELIABILITY, false,
                    Pattern.compile("throw\\s+[\"'`]"),
                    "Throwing a string loses the stack trace and cannot be matched by type; throw an Error."),
            new Rule("CW-TS-BUG-001", Severity.MEDIUM, FindingCategory.BUG, false,
                    Pattern.compile("\\bparseInt\\s*\\([^,()]*\\)"),
                    "parseInt without a radix guesses the base, so '08' and '0x10' do not mean what they look like. "
                            + "Pass 10."),
            new Rule("CW-TS-BUG-002", Severity.HIGH, FindingCategory.BUG, false,
                    Pattern.compile("\\bprocess\\.exit\\s*\\(|\\bprocess\\.abort\\s*\\("),
                    "Exiting the process abandons in-flight work and makes the code untestable; return a result instead."),
            new Rule("CW-TS-NUM-001", Severity.HIGH, FindingCategory.BUG, false,
                    Pattern.compile("(?i)\\b\\w*(amount|balance|price|total|fee|money|cost)\\w*\\s*[:=][^=\\n]*\\d+\\.\\d+"),
                    "A monetary value is held as a binary float: rounding errors accumulate and cents go missing. "
                            + "Use integer minor units or a decimal type."),
            new Rule("CW-TS-NUM-002", Severity.MEDIUM, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("===?\\s*-?\\d+\\.\\d+|\\d+\\.\\d+\\s*===?"),
                    "Equality on a floating point literal is rarely what is meant; compare with a tolerance."),
            new Rule("CW-TS-DATE-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("/\\s*86_?400_?000\\b|/\\s*86_?400\\b|\\.setDate\\s*\\(|\\.setHours\\s*\\(\\s*0"),
                    "Calendar-based time arithmetic: a limit expressed over a rolling window is not the same as a "
                            + "limit per calendar day. Check this against the requirement."));

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
        return "typescript-heuristics";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.TYPESCRIPT);
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
        return ruleId != null && ruleId.startsWith("CW-TS-SEC");
    }

    /** @return security findings only. */
    public List<Finding> analyzeSecurity(VerificationContext context) {
        return findAll(context).stream().filter(finding -> isSecurityRule(finding.ruleId())).toList();
    }

    private static List<Path> sourceFiles(VerificationContext context) {
        List<Path> files = new ArrayList<>();
        for (Path root : context.projectInfo().sourceDirs()) {
            files.addAll(TypeScriptProjectDetector.typeScriptFiles(root));
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
        }
    }

    /** Analyses test sources for tests that cannot fail, which is a real verification hazard. */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path root : context.projectInfo().testSourceDirs()) {
            for (Path file : TypeScriptProjectDetector.testFiles(root)) {
                String content = read(file);
                if (content.isEmpty()) {
                    continue;
                }
                String relative = relativePath(context, file);
                Matcher call = TEST_CALL.matcher(content);
                while (call.find()) {
                    // The body runs from this test( to the next test( or the end of the file.
                    Matcher next = TEST_CALL.matcher(content);
                    int end = content.length();
                    if (next.find(call.end())) {
                        end = next.start();
                    }
                    String body = content.substring(call.end(), end);
                    if (!body.isBlank() && !ANY_ASSERTION.matcher(body).find()) {
                        findings.add(new Finding("typescript-heuristics", "CW-TS-TEST-001", Severity.MEDIUM,
                                FindingCategory.MAINTAINABILITY,
                                "A test in " + relative + " has no assertion, so it passes whatever the code does. "
                                        + "A passing test that cannot fail is worse than no test.",
                                relative, lineOf(content, call.start()), null, Map.of()));
                    }
                }
            }
        }
        return findings;
    }

    private static Finding finding(Rule rule, String relative, int line, String snippet) {
        return new Finding("typescript-heuristics", rule.id(), rule.severity(), rule.category(),
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
        char quote = 0;
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
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                inString = true;
                quote = c;
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
        ids.add("CW-TS-TEST-001");
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

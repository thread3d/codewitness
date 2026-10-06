package dev.codewitness.python.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.python.PythonProjectDetector;

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
 * Deterministic source heuristics for Python.
 *
 * <p>These rules exist because a Python program imports and runs happily while being wrong in well
 * known ways: a mutable default argument shared between calls, a bare {@code except} that swallows
 * everything, an {@code assert} that disappears under {@code -O}, money kept as a binary float, or a
 * test with no assertion. Each rule is a syntactic pattern with a documented false-positive profile;
 * findings are {@code MEDIUM}/{@code LOW}/{@code HIGH} confidence and never on their own make a
 * requirement {@code VERIFIED}.
 *
 * <p>This is not a replacement for ruff or mypy; it is the analyzer that is always available, needs
 * no installation, and whose entire rule table fits in one auditable file.
 */
public final class PythonHeuristicAnalyzer implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    private static final Pattern MUTABLE_DEFAULT =
            Pattern.compile("def\\s+\\w+\\s*\\([^)]*=\\s*(\\[\\]|\\{\\}|set\\(\\))");
    private static final Pattern ASSERT_STATEMENT = Pattern.compile("^\\s*assert\\s+");
    private static final Pattern FLOAT_MONEY = Pattern.compile(
            "(?i)\\b\\w*(amount|balance|price|total|fee|money|cost)\\w*\\s*=\\s*[^=\\n]*\\d+\\.\\d+");

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-PY-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key|auth[_-]?token|access[_-]?token)"
                            + "\\b\\s*=\\s*[\"'][^\"']{4,}[\"']"),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a secret store."),
            new Rule("CW-PY-SEC-002", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("(?<![\\w.])eval\\s*\\(|(?<![\\w.])exec\\s*\\("),
                    "eval()/exec() executes arbitrary code: treat any value reaching it as a command injection."),
            new Rule("CW-PY-SEC-003", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\bshell\\s*=\\s*True\\b"),
                    "subprocess with shell=True passes the command to a shell: input reaching it allows command injection."),
            new Rule("CW-PY-SEC-004", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\bos\\.(system|popen)\\s*\\(|\\bos\\.exec[lv]"),
                    "os.system/os.popen runs a shell command: input reaching it allows command injection."),
            new Rule("CW-PY-SEC-005", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\b(pickle|dill|cPickle|marshal)\\.loads?\\s*\\(|\\byaml\\.load\\s*\\((?![^)]*Loader)"),
                    "Deserialising untrusted data (pickle/yaml.load without a safe Loader) can execute arbitrary code."),
            new Rule("CW-PY-SEC-006", Severity.MEDIUM, FindingCategory.SECURITY, true,
                    Pattern.compile("\\bhashlib\\.(md5|sha1)\\s*\\(|\\b(md5|sha1)\\s*\\("),
                    "Weak message digest (MD5/SHA-1). Use SHA-256 or better."),
            new Rule("CW-PY-SEC-007", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("\\bverify\\s*=\\s*False\\b"),
                    "TLS certificate verification is disabled: the connection is not authenticated."),
            new Rule("CW-PY-SEC-008", Severity.HIGH, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)\\b(execute|executemany|executescript)\\s*\\(\\s*(f[\"']|[\"'][^\"']*[\"']\\s*[%+]|[^)]*%|.*\\.format\\()"),
                    "SQL statement built by string formatting or concatenation: user input can change the query. "
                            + "Use a parameterised statement."),
            new Rule("CW-PY-SEC-009", Severity.MEDIUM, FindingCategory.SECURITY, true,
                    Pattern.compile("\\btempfile\\.mktemp\\s*\\(|\\bos\\.tmpnam\\s*\\("),
                    "Insecure temporary file creation: the name can be guessed and raced. Use tempfile.mkstemp/NamedTemporaryFile."),
            new Rule("CW-PY-SEC-010", Severity.MEDIUM, FindingCategory.SECURITY, true,
                    Pattern.compile("(?i)\\b(requests|httpx)\\.\\w+\\s*\\([^)]*\\bverify\\s*=\\s*False"),
                    "TLS verification is disabled on an HTTP client call: the peer is not authenticated."));
    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-PY-REL-001", Severity.HIGH, FindingCategory.RELIABILITY, false,
                    Pattern.compile("^\\s*except\\s*:"),
                    "Bare except swallows every error, including KeyboardInterrupt and SystemExit. Catch a specific exception."),
            new Rule("CW-PY-REL-002", Severity.MEDIUM, FindingCategory.RELIABILITY, false,
                    Pattern.compile("^\\s*except\\b[^:]*:\\s*(pass|\\.\\.\\.)\\s*$"),
                    "Exception handler does nothing: the failure is silently discarded."),
            new Rule("CW-PY-REL-003", Severity.LOW, FindingCategory.RELIABILITY, false,
                    Pattern.compile("^\\s*except\\s+Exception\\b"),
                    "Catching Exception broadly hides programming errors as well as expected failures."),
            new Rule("CW-PY-BUG-001", Severity.MEDIUM, FindingCategory.BUG, false,
                    Pattern.compile("[=!]=\\s*None\\b|\\bNone\\s*[=!]="),
                    "Comparison to None with ==/!= calls __eq__; identity (is / is not) is the correct test."),
            new Rule("CW-PY-BUG-002", Severity.MEDIUM, FindingCategory.BUG, false,
                    Pattern.compile("\\bis\\s+(?:-?\\d+(?:\\.\\d+)?|True|False|'[^']*'|\"[^\"]*\")"),
                    "Identity comparison with a literal is an implementation detail and warns at runtime; use ==."),
            new Rule("CW-PY-BUG-003", Severity.MEDIUM, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("(?<![\\w.])float\\s*\\(\\s*(?:\"[^\"]*\"|'[^']*'|\\d)"),
                    "A literal is converted to binary floating point: money and precise quantities lose accuracy."),
            new Rule("CW-PY-DATE-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, false,
                    Pattern.compile("\\b(datetime\\.)?date\\.today\\s*\\(|\\bdatetime\\.now\\s*\\([^)]*\\)\\.date\\s*\\("),
                    "Calendar-based date arithmetic: a limit expressed over a rolling window is not the same as a "
                            + "limit per calendar day. Check this against the requirement."));
    private static final Pattern TEST_FUNCTION = Pattern.compile("(?m)^\\s*def\\s+(test_\\w+)\\s*\\(");
    private static final Pattern ASSERTION = Pattern.compile(
            "\\bassert\\b|self\\.assert|pytest\\.raises|self\\.assertRaises|unittest\\.|\\braise\\b");

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
        return "python-heuristics";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
    }

    /** Reports every non-security finding: correctness heuristics plus tests that cannot fail. */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : context.projectInfo().sourceDirs().stream().flatMap(root ->
                PythonProjectDetector.pythonFiles(root).stream()).toList()) {
            analyseFile(context, file, findings);
        }
        findings.addAll(analyzeTests(context));
        return findings.stream().filter(finding -> !isSecurityRule(finding.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : context.projectInfo().sourceDirs().stream().flatMap(root ->
                PythonProjectDetector.pythonFiles(root).stream()).toList()) {
            analyseFile(context, file, findings);
        }
        findings.addAll(analyzeTests(context));
        return List.copyOf(findings);
    }

    /** @return {@code true} when the rule is a security rule. */
    public static boolean isSecurityRule(String ruleId) {
        return ruleId != null && ruleId.startsWith("CW-PY-SEC");
    }

    /** @return security findings only. */
    public List<Finding> analyzeSecurity(VerificationContext context) {
        return findAll(context).stream().filter(finding -> isSecurityRule(finding.ruleId())).toList();
    }

    private void analyseFile(VerificationContext context, Path file, List<Finding> findings) {
        String content = read(file);
        if (content.isEmpty()) {
            return;
        }
        String relative = relativePath(context, file);
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isComment(line)) {
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
            if (ASSERT_STATEMENT.matcher(line).find() && !isTestFile(relative)) {
                findings.add(new Finding("python-heuristics", "CW-PY-BUG-004", Severity.LOW,
                        FindingCategory.CORRECTNESS,
                        "assert is removed when Python runs with -O: it must not be the only guard on a "
                                + "production invariant.",
                        relative, i + 1, null, Map.of("snippet", line.strip())));
            }
            if (MUTABLE_DEFAULT.matcher(line).find()) {
                findings.add(new Finding("python-heuristics", "CW-PY-MUT-001", Severity.HIGH,
                        FindingCategory.BUG,
                        "Mutable default argument: the same list/dict/set is shared by every call, so state "
                                + "leaks between calls. Use None and create the value inside the function.",
                        relative, i + 1, null, Map.of("snippet", line.strip())));
            }
            if (FLOAT_MONEY.matcher(line).find()) {
                findings.add(new Finding("python-heuristics", "CW-PY-NUM-001", Severity.HIGH,
                        FindingCategory.BUG,
                        "A monetary value is held as a binary float: rounding errors accumulate and cents go "
                                + "missing. Use decimal.Decimal or integer cents.",
                        relative, i + 1, null, Map.of("snippet", line.strip())));
            }
        }
    }

    /** Analyses test sources for tests that cannot fail, which is a real verification hazard. */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path root : context.projectInfo().testSourceDirs()) {
            for (Path file : PythonProjectDetector.pythonFiles(root)) {
                String content = read(file);
                if (content.isEmpty()) {
                    continue;
                }
                String relative = relativePath(context, file);
                Matcher matcher = TEST_FUNCTION.matcher(content);
                while (matcher.find()) {
                    String body = bodyOf(content, matcher.end());
                    if (!ASSERTION.matcher(body).find() && !body.isBlank()) {
                        findings.add(new Finding("python-heuristics", "CW-PY-TEST-001", Severity.MEDIUM,
                                FindingCategory.MAINTAINABILITY,
                                "Test '" + matcher.group(1) + "' has no assertion, so it passes whatever the "
                                        + "code does. A passing test that cannot fail is worse than no test.",
                                relative, lineOf(content, matcher.start()), null, Map.of()));
                    }
                }
            }
        }
        return findings;
    }

    /** @return the indented body of a function starting at {@code start}, up to the next def/class. */
    private static String bodyOf(String content, int start) {
        int end = content.length();
        Matcher next = Pattern.compile("(?m)^\\s*(?:def|class)\\s+").matcher(content);
        if (next.find(start)) {
            end = next.start();
        }
        return content.substring(start, end);
    }

    private static Finding finding(Rule rule, String relative, int line, String snippet) {
        return new Finding("python-heuristics", rule.id(), rule.severity(), rule.category(),
                rule.message(), relative, line, null, Map.of("snippet", snippet.strip()));
    }

    private static boolean isComment(String line) {
        String stripped = line.stripLeading();
        return stripped.startsWith("#");
    }

    static boolean isTestFile(String relative) {
        String normalized = relative == null ? "" : relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        return normalized.contains("/test") || name.startsWith("test_") || name.endsWith("_test.py")
                || name.startsWith("tests");
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
        ids.add("CW-PY-BUG-004");
        ids.add("CW-PY-MUT-001");
        ids.add("CW-PY-NUM-001");
        ids.add("CW-PY-TEST-001");
        return List.copyOf(ids);
    }

    /** @return the security rule table, so the security analyzer shares one definition. */
    static List<Rule> securityRules() {
        return SECURITY_RULES;
    }

    /** @return the severity of a rule, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        return SECURITY_RULES.stream().filter(rule -> rule.id().equals(ruleId)).map(Rule::severity)
                .findFirst()
                .orElseGet(() -> CORRECTNESS_RULES.stream().filter(rule -> rule.id().equals(ruleId))
                        .map(Rule::severity).findFirst().orElse(Severity.MEDIUM));
    }
}

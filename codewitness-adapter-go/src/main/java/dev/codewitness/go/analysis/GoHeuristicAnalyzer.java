package dev.codewitness.go.analysis;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.go.GoDiagnostics;
import dev.codewitness.go.GoProjectDetector;
import dev.codewitness.go.GoTool;

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
 * Deterministic source heuristics for Go.
 *
 * <p>Go's compiler already rejects the programs that cannot run, so these rules look at what it
 * deliberately accepts: an error discarded into the blank identifier, a {@code panic} or
 * {@code log.Fatal} in library code, an error compared with {@code ==} instead of {@code errors.Is},
 * a suppression directive, calendar-day arithmetic expressed as a rolling window, a monetary value
 * held as a binary float, equality on a float literal, a silent float-to-int truncation, static
 * errors that cannot be unwrapped, and a test that cannot fail. Each rule is a syntactic pattern with
 * a documented false-positive profile; findings never on their own make a requirement
 * {@code VERIFIED}.
 *
 * <p>This is not a replacement for {@code go vet} or Staticcheck; it is the analyzer that is always
 * available, needs no installation, and whose entire rule table fits in one auditable file. Those
 * tools' diagnostics are reported separately when the tools are present.
 *
 * <p>Two scanners feed the rules, because Go's lexical structure makes both of them necessary:
 * <ul>
 *   <li>Comments are removed first, so a rule cannot fire on a comment that merely describes the
 *       hazard it looks for.</li>
 *   <li>String-literal <em>contents</em> are additionally blanked for every rule that is not about the
 *       literal itself. A hazard mentioned inside a string is text, not code; the credential and SQL
 *       rules are the deliberate exceptions, because for them the literal is the evidence.</li>
 * </ul>
 * The one rule that is <em>about</em> a comment - the {@code //nolint} / {@code //go:linkname}
 * suppression directive - is matched against the comment-preserving view. Reporting a suppression is
 * the rule's whole purpose, so it is the single, documented exception to "never fire inside a
 * comment".
 *
 * <p>The static-analysis phase is "compiler diagnostics plus the adapter's heuristic rule table", so
 * this analyzer also runs {@code go vet} and folds its diagnostics into the same phase. The source
 * rules remain the deterministic half that is always available; {@code go vet} is the
 * compiler-adjacent half, and a missing toolchain is reported as a fact rather than treated as a
 * failure.
 */
public final class GoHeuristicAnalyzer implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /** How a rule's haystack is prepared; see {@link #scan}. */
    private enum View {
        /** Comment-free code with string literals preserved: for rules whose evidence is the literal. */
        CODE,
        /** Comment-free code with string-literal contents blanked: for every ordinary rule. */
        MASKED,
        /** Code with comments preserved and string contents blanked: for suppression directives. */
        DIRECTIVE
    }

    /** A top-level test function: {@code func TestXxx(t *testing.T)}. */
    private static final Pattern TEST_FUNCTION =
            Pattern.compile("(?m)^func\\s+(Test\\w+)\\s*\\(\\s*\\w+\\s+\\*testing\\.T\\s*\\)");
    /** The start of any top-level function, used to bound a test's body. */
    private static final Pattern TOP_LEVEL_FUNC = Pattern.compile("(?m)^func\\s");
    /** Anything that makes a test able to fail: the standard testing API, testify, or a panic. */
    private static final Pattern ANY_ASSERTION = Pattern.compile(
            "\\bt\\.(Error|Errorf|Fatal|Fatalf|Fail|FailNow|Skip|SkipNow)\\b|\\brequire\\.|\\bassert\\."
                    + "|\\bpanic\\s*\\(|\\bt\\.Run\\s*\\(");

    /** The single rule id under which every {@code go vet} diagnostic is reported. */
    private static final String VET_ANALYZER = "go-vet";
    private static final String VET_RULE_ID = "CW-GO-VET-001";
    /**
     * The finding severity of an ordinary vet diagnostic.
     *
     * <p>{@code go vet} output has no severity label, and the finding model's {@link Severity} has no
     * WARNING constant, so a vet concern maps to MEDIUM - the warning level of this model - and a
     * message that names a build-blocking mistake maps to HIGH.
     */
    private static final Severity VET_WARNING_SEVERITY = Severity.MEDIUM;
    private static final Severity VET_ERROR_SEVERITY = Severity.HIGH;
    /** The vet messages that describe a program that cannot compile, not merely a suspicious one. */
    private static final List<String> COMPILE_ERROR_PREFIXES = List.of(
            "undefined:", "syntax error", "declared and not used", "imported and not used",
            "cannot use", "too many", "not enough", "mismatched");

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-GO-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("(?i)\\b(pass(word|wd)?|secret|api_?key|auth_?token|access_?token|private_?key)"
                            + "\\b\\s*[:=]\\s*[\"`]"),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a secret store."),
            new Rule("CW-GO-SEC-002", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\bexec\\.Command\\s*\\("),
                    "External command execution: any input reaching the program name or arguments is a command injection."),
            new Rule("CW-GO-SEC-003", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\bunsafe\\.(Pointer|Sizeof|Offsetof)\\b"),
                    "unsafe.Pointer/Sizeof/Offsetof opts out of Go's type and memory safety: every invariant it "
                            + "relies on is now the programmer's responsibility. Justify it or remove it."),
            new Rule("CW-GO-SEC-004", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(md5|sha1)\\.(New|Sum)\\b"),
                    "Weak message digest (MD5/SHA-1). Use SHA-256 or better."),
            new Rule("CW-GO-SEC-005", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("InsecureSkipVerify\\s*:\\s*true"),
                    "TLS certificate verification is disabled: the connection is not authenticated."),
            // The SQL keyword lives inside the format string or the concatenated literal, so this rule
            // needs the view that keeps literal contents.
            new Rule("CW-GO-SEC-006", Severity.HIGH, FindingCategory.SECURITY, true, View.CODE,
                    Pattern.compile("(?i)(?:fmt\\.Sprintf\\s*\\([^\\n]*(select|insert|update|delete|drop)\\b"
                            + "|\"[^\"]*(select|insert|update|delete|drop)\\b[^\"]*\"\\s*\\+)"),
                    "SQL statement built by formatting or concatenation: user input can change the query. Use a "
                            + "parameterised statement."),
            new Rule("CW-GO-SEC-007", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\brand\\.(Int|Intn|Int63|Float32|Float64|Read|Perm)\\s*\\("),
                    "math/rand is deterministic and its stream is guessable; use crypto/rand for anything security "
                            + "relevant."),
            new Rule("CW-GO-SEC-008", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("os\\.(Chmod|OpenFile|Mkdir(All)?)\\s*\\([^)]*\\b0?777\\b"),
                    "World-writable permissions (0777) let any user modify the file or directory."),
            new Rule("CW-GO-SEC-009", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("http\\.ListenAndServe(TLS)?\\s*\\("),
                    "http.ListenAndServe without read/write/idle timeouts is vulnerable to Slowloris-style "
                            + "connection exhaustion; use an http.Server with timeouts."));

    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-GO-REL-001", Severity.MEDIUM, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("_\\s*=\\s*[A-Za-z_][\\w.]*\\s*\\("),
                    "The returned value - usually an error - is discarded into the blank identifier: the failure "
                            + "disappears and the caller cannot know it happened. Handle it or propagate it."),
            new Rule("CW-GO-REL-002", Severity.HIGH, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("panic\\s*\\("),
                    "panic unwinds the goroutine and, unless recovered, crashes the process: an expected failure "
                            + "should be a returned error, not a crash."),
            new Rule("CW-GO-REL-003", Severity.HIGH, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\b(log\\.Fatal|log\\.Fatalf|log\\.Fatalln|os\\.Exit)\\s*\\("),
                    "log.Fatal/os.Exit terminates the process from library code: deferred functions do not run and "
                            + "the caller cannot recover. Return an error instead."),
            new Rule("CW-GO-REL-004", Severity.MEDIUM, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("err\\s*==\\s*[A-Za-z_]"),
                    "Comparing errors with == only matches the exact value; use errors.Is or errors.As so wrapped "
                            + "errors are still recognised."),
            new Rule("CW-GO-REL-005", Severity.LOW, FindingCategory.MAINTAINABILITY, false, View.DIRECTIVE,
                    Pattern.compile("//\\s*go:linkname|//\\s*nolint"),
                    "A lint or toolchain directive (//nolint, //go:linkname) suppresses a check here; confirm the "
                            + "suppression is deliberate and narrow."),
            new Rule("CW-GO-DATE-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, false, View.MASKED,
                    Pattern.compile("/\\s*86_?400_?000\\b"),
                    "Calendar-based time arithmetic: a limit expressed over a rolling window is not the same as a "
                            + "limit per calendar day. Check this against the requirement."),
            new Rule("CW-GO-NUM-001", Severity.HIGH, FindingCategory.BUG, false, View.MASKED,
                    Pattern.compile("(?i)\\b\\w*(amount|balance|price|total|fee|money|cost)\\w*\\s+(float32|float64)\\b"),
                    "A monetary value is held as a binary float: rounding errors accumulate and cents go missing. "
                            + "Use integer minor units or a decimal type."),
            new Rule("CW-GO-NUM-002", Severity.MEDIUM, FindingCategory.CORRECTNESS, false, View.MASKED,
                    Pattern.compile("==\\s*-?\\d+\\.\\d+|\\d+\\.\\d+\\s*=="),
                    "Equality on a floating point literal is rarely what is meant; compare with a tolerance."),
            new Rule("CW-GO-ERR-001", Severity.MEDIUM, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\berrors\\.New\\s*\\("),
                    "errors.New creates a static error that cannot be unwrapped or inspected with errors.Is; when "
                            + "this wraps another error, use fmt.Errorf with %w so the cause survives."),
            new Rule("CW-GO-BUG-001", Severity.MEDIUM, FindingCategory.BUG, false, View.MASKED,
                    Pattern.compile("\\bint\\s*\\(\\s*[^)]*\\bfloat(32|64)\\b"),
                    "An explicit float-to-int conversion truncates silently: the fractional part is discarded. "
                            + "Round or range-check first."));

    private record Rule(
            String id,
            Severity severity,
            FindingCategory category,
            boolean security,
            View view,
            Pattern pattern,
            String message) {
    }

    /** The three views of one source line that the rules are matched against. */
    private record LineViews(String code, String masked, String directives) {
    }

    @Override
    public String id() {
        return "go-heuristics";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    /**
     * Reports every non-security finding: the source heuristics, tests that cannot fail, and the
     * compiler-adjacent {@code go vet} diagnostics.
     */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return findAll(context).stream().filter(finding -> !isSecurityRule(finding.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>(sourceFindings(context));
        findings.addAll(analyzeGoVet(context));
        return List.copyOf(findings);
    }

    /**
     * @return the source-only findings: the rule table plus tests that cannot fail.
     *
     * <p>Security analysis goes through this rather than through {@link #findAll} so that reporting a
     * security finding never has the side effect of running the compiler-adjacent tool.
     */
    private List<Finding> sourceFindings(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : sourceFiles(context, context.projectInfo().sourceDirs())) {
            analyseFile(context, file, findings);
        }
        findings.addAll(analyzeTests(context));
        return List.copyOf(findings);
    }

    /** @return {@code true} when the rule is a security rule. */
    public static boolean isSecurityRule(String ruleId) {
        return ruleId != null && ruleId.startsWith("CW-GO-SEC");
    }

    /** @return security findings only; the source rule table is the only input. */
    public List<Finding> analyzeSecurity(VerificationContext context) {
        return sourceFindings(context).stream().filter(finding -> isSecurityRule(finding.ruleId())).toList();
    }

    /**
     * Runs {@code go vet ./...} and maps its diagnostics to findings.
     *
     * <p>{@code go vet} is the compiler-adjacent half of Go's static analysis: it catches the mistakes
     * the compiler accepts, such as a malformed format string or a lost struct tag. It is a tool, not a
     * parser, so it is only attempted when there is a module to vet, and a toolchain that cannot start
     * is announced on the run's progress channel instead of being thrown: a missing tool is a reported
     * fact, and the source rules above still run without it.
     *
     * @return one finding per located vet diagnostic, in the order vet emitted them, or an empty list
     *     when vet could not run
     */
    public List<Finding> analyzeGoVet(VerificationContext context) {
        if (!GoTool.hasManifest(context)) {
            return List.of();
        }
        String go = GoTool.go(context.projectInfo(), context);
        List<String> command = List.of(go, "vet", "./...");
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(GoTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            context.emit("static-analysis", "go vet could not be started: " + result.failureReason());
            return List.of();
        }
        // vet reports a concern without labelling it, so the parsed severity is the warning baseline
        // and the individual message decides whether it is really a compile error.
        List<CompilerDiagnostic> diagnostics =
                GoDiagnostics.parse(result.combinedOutput(), context.projectRoot(), DiagnosticSeverity.WARNING);
        List<Finding> findings = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            findings.add(new Finding(VET_ANALYZER, VET_RULE_ID, vetSeverity(diagnostic.message()),
                    FindingCategory.CORRECTNESS, diagnostic.message(), diagnostic.file(), diagnostic.line(),
                    null, Map.of("tool", "go vet")));
        }
        context.emit("static-analysis",
                "go vet " + result.describeOutcome() + " with " + findings.size() + " finding(s)");
        return List.copyOf(findings);
    }

    /**
     * @return the finding severity for a vet message.
     *
     * <p>Go emits build-blocking mistakes such as {@code undefined: balance} in the same unlabelled
     * stream as stylistic concerns, so the message is inspected to decide whether the program could
     * even have been built. Everything that is not one of those prefixes is a concern, not a defect.
     */
    static Severity vetSeverity(String message) {
        if (message == null) {
            return VET_WARNING_SEVERITY;
        }
        String text = message.strip();
        for (String prefix : COMPILE_ERROR_PREFIXES) {
            if (text.startsWith(prefix)) {
                return VET_ERROR_SEVERITY;
            }
        }
        return VET_WARNING_SEVERITY;
    }

    private static List<Path> sourceFiles(VerificationContext context, List<Path> roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            files.addAll(GoProjectDetector.goFiles(root));
        }
        return files;
    }

    private void analyseFile(VerificationContext context, Path file, List<Finding> findings) {
        String content = read(file);
        if (content.isEmpty()) {
            return;
        }
        String relative = relativePath(context, file);
        // state[0] carries block-comment state; state[1] carries raw-string state, because a backtick
        // raw string can span lines. Both are handed back by scan() for the next line.
        boolean[] state = {false, false};
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            LineViews views = scan(lines[i], state);
            int lineNumber = i + 1;
            // The suppression directive is reported against the comment-preserving view; see the class
            // javadoc for why this single rule is allowed to match inside a comment.
            for (Rule rule : CORRECTNESS_RULES) {
                if (rule.view() == View.DIRECTIVE && rule.pattern().matcher(views.directives()).find()) {
                    findings.add(finding(rule, relative, lineNumber, lines[i].strip()));
                }
            }
            if (views.code().isBlank()) {
                continue;
            }
            for (Rule rule : CORRECTNESS_RULES) {
                if (rule.view() != View.DIRECTIVE) {
                    addIfMatching(rule, views, relative, lineNumber, findings);
                }
            }
            for (Rule rule : SECURITY_RULES) {
                addIfMatching(rule, views, relative, lineNumber, findings);
            }
        }
    }

    private static void addIfMatching(
            Rule rule, LineViews views, String relative, int lineNumber, List<Finding> findings) {
        String haystack = rule.view() == View.CODE ? views.code() : views.masked();
        if (rule.pattern().matcher(haystack).find()) {
            findings.add(finding(rule, relative, lineNumber, views.code().strip()));
        }
    }

    /** Analyses test sources for tests that cannot fail, which is a real verification hazard. */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : sourceFiles(context, context.projectInfo().testSourceDirs())) {
            String content = read(file);
            if (content.isEmpty()) {
                continue;
            }
            String relative = relativePath(context, file);
            Matcher function = TEST_FUNCTION.matcher(content);
            while (function.find()) {
                String body = bodyOf(content, function.end());
                if (!body.isBlank() && !ANY_ASSERTION.matcher(body).find()) {
                    findings.add(new Finding("go-heuristics", "CW-GO-TEST-001", Severity.MEDIUM,
                            FindingCategory.MAINTAINABILITY,
                            "Test '" + function.group(1) + "' has no assertion, so it passes whatever the code "
                                    + "does; a passing test that cannot fail is worse than no test.",
                            relative, lineOf(content, function.start()), null,
                            Map.of("snippet", function.group(0).strip())));
                }
            }
        }
        return List.copyOf(findings);
    }

    /** @return the body of a function starting at {@code start}, up to the next top-level {@code func}. */
    private static String bodyOf(String content, int start) {
        Matcher next = TOP_LEVEL_FUNC.matcher(content);
        int end = content.length();
        if (next.find(start)) {
            end = next.start();
        }
        return content.substring(start, end);
    }

    private static Finding finding(Rule rule, String relative, int line, String snippet) {
        return new Finding("go-heuristics", rule.id(), rule.severity(), rule.category(),
                rule.message(), relative, line, null, Map.of("snippet", snippet.strip()));
    }

    /**
     * @return the line with comments removed, so a rule cannot fire on a comment that only describes
     *     the hazard it is looking for.
     *
     * <p>String literals are preserved here; the analyzer additionally derives a literal-blanked view
     * so that ordinary rules do not match text that merely sits inside a string. See {@link #scan}.
     *
     * <p>{@code inBlockComment} carries block-comment state across lines at index 0. Callers that also
     * need raw-string state across lines pass a two-element array, where index 1 carries it; a shorter
     * array simply cannot carry raw-string state and is still accepted for the comment-only use.
     */
    static String codeOnly(String line, boolean[] inBlockComment) {
        return scan(line, inBlockComment).code();
    }

    /**
     * Scans one line once and derives every view the rule table needs.
     *
     * <p>Comments are removed from {@code code} and {@code masked}; a raw string opened with a backtick
     * ends at the next backtick and is copied into {@code code} but blanked in {@code masked} and
     * {@code directives}. {@code directives} keeps {@code //} line comments (and blanks block comments)
     * so a suppression directive can be reported, while string contents are blanked in it so a URL such
     * as {@code "http://nolint"} cannot be mistaken for one.
     *
     * <p>Scanning the three views in a single pass is deliberate: comment and raw-string state must
     * advance exactly once per character, and three independent scanners would each have to re-derive
     * the same boundaries.
     *
     * @param line  one physical source line
     * @param state index 0 is block-comment state; an optional index 1 is raw-string state
     * @return the comment-stripped, literal-blanked and comment-preserving views of the line
     */
    static LineViews scan(String line, boolean[] state) {
        StringBuilder code = new StringBuilder(line.length());
        StringBuilder masked = new StringBuilder(line.length());
        StringBuilder directives = new StringBuilder(line.length());
        boolean inBlockComment = state.length > 0 && state[0];
        boolean inRawString = state.length > 1 && state[1];
        boolean inString = false;
        boolean escaped = false;
        char quote = 0;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inBlockComment) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    blank(code, masked, directives, 2);
                    i += 2;
                } else {
                    blank(code, masked, directives, 1);
                    i++;
                }
                continue;
            }
            if (inRawString) {
                // Go raw strings have no escapes: the next backtick closes the literal. Contents are
                // text, so only the code view keeps them.
                code.append(c);
                if (c == '`') {
                    inRawString = false;
                    masked.append('`');
                    directives.append('`');
                } else {
                    masked.append(' ');
                    directives.append(' ');
                }
                i++;
                continue;
            }
            if (inString) {
                code.append(c);
                if (escaped) {
                    escaped = false;
                    masked.append(' ');
                    directives.append(' ');
                } else if (c == '\\') {
                    escaped = true;
                    masked.append(' ');
                    directives.append(' ');
                } else if (c == quote) {
                    inString = false;
                    masked.append(c);
                    directives.append(c);
                } else {
                    masked.append(' ');
                    directives.append(' ');
                }
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                code.append(c);
                masked.append(c);
                directives.append(c);
                i++;
                continue;
            }
            if (c == '`') {
                inRawString = true;
                code.append(c);
                masked.append(c);
                directives.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                // A line comment: the code views stop here, while the directive view keeps the text.
                directives.append(line, i, line.length());
                break;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                inBlockComment = true;
                blank(code, masked, directives, 2);
                i += 2;
                continue;
            }
            code.append(c);
            masked.append(c);
            directives.append(c);
            i++;
        }
        if (state.length > 0) {
            state[0] = inBlockComment;
        }
        if (state.length > 1) {
            state[1] = inRawString;
        }
        return new LineViews(code.toString(), masked.toString(), directives.toString());
    }

    private static void blank(StringBuilder code, StringBuilder masked, StringBuilder directives, int count) {
        code.append(" ".repeat(count));
        masked.append(" ".repeat(count));
        directives.append(" ".repeat(count));
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
        ids.add("CW-GO-TEST-001");
        ids.add(VET_RULE_ID);
        return List.copyOf(ids);
    }

    /** @return the severity of a rule, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        if (VET_RULE_ID.equals(ruleId)) {
            // The tool's own rule is reported at the warning baseline; an individual diagnostic that
            // names a compile error is promoted to HIGH by vetSeverity.
            return VET_WARNING_SEVERITY;
        }
        return SECURITY_RULES.stream().filter(rule -> rule.id().equals(ruleId)).map(Rule::severity)
                .findFirst()
                .orElseGet(() -> CORRECTNESS_RULES.stream().filter(rule -> rule.id().equals(ruleId))
                        .map(Rule::severity).findFirst().orElse(Severity.MEDIUM));
    }
}

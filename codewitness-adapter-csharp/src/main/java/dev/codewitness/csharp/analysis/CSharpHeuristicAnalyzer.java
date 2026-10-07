package dev.codewitness.csharp.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.csharp.CSharpProjectDetector;
import dev.codewitness.csharp.CSharpSourceScan;

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
 * Deterministic source heuristics for C#.
 *
 * <p>C#'s compiler already rejects the programs that cannot run, and its nullable reference types reject
 * a growing share of the mistakes that used to reach production. What is left is what the compiler
 * deliberately accepts: an exception swallowed by an empty handler, a synchronous wait on an
 * asynchronous operation, a monetary quantity held as a binary float, a rolling-window check written as
 * a calendar-day division, a credential in a literal, a serializer told to trust type names. Each rule is
 * a syntactic pattern with a documented false-positive profile; findings never on their own make a
 * requirement {@code VERIFIED}.
 *
 * <p>The Roslyn diagnostics a build produces are <em>not</em> re-run here. The build phase already
 * compiled the project and stored the compiler's own diagnostics with the command that produced them;
 * running the compiler a second time inside static analysis would duplicate that evidence under a
 * different rule id and make it ambiguous which run a diagnostic came from. This analyzer is the
 * rule table that needs no installation and fits in one auditable file, and it runs on the same source
 * tree whatever else is installed.
 *
 * <p>Two scanners feed the rules: {@link CSharpSourceScan} produces the comment-free, literal-blanked
 * and comment-preserving views of the file, and the findings that span lines - a swallowed exception, a
 * test that cannot fail - are found by brace matching on the literal-blanked view, so a brace inside a
 * string cannot move the end of a block.
 */
public final class CSharpHeuristicAnalyzer implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /** Which of the three views a rule is matched against. See {@link CSharpSourceScan}. */
    private enum View {
        /** Comment-free code with literal contents preserved: for rules whose evidence is the literal. */
        CODE,
        /** Comment-free code with literal contents blanked: for every ordinary rule. */
        MASKED,
        /** Code with comments preserved and literal contents blanked: for suppression directives. */
        DIRECTIVE
    }

    /** A test method attribute in any of the three frameworks the adapter recognises. */
    private static final Pattern TEST_ATTRIBUTE =
            Pattern.compile("\\[\\s*(Fact|Theory|TestMethod|DataTestMethod|Test|TestCase)\\b");

    /** Anything that lets a test fail. A test with none of these cannot fail, which is worse than none. */
    private static final Pattern ANY_ASSERTION = Pattern.compile(
            "\\bAssert\\s*\\.|\\bAssert\\s*\\(|\\.Should\\s*\\(|\\bRecord\\s*\\.\\s*Exception\\b"
                    + "|\\bthrow\\b|\\[\\s*ExpectedException|\\bVerify\\s*\\(|\\bTimes\\s*\\."
                    + "|\\bMock\\s*\\.|\\bCollectionAssert\\b|\\bStringAssert\\b|\\bFluentActions\\b");

    /** A catch clause and the brace that opens its body. */
    private static final Pattern CATCH = Pattern.compile("\\bcatch\\b\\s*(?:\\([^)]*\\))?\\s*\\{");

    /** Anything that makes a swallowed exception acceptable: it was reported, wrapped or rethrown. */
    private static final Pattern HANDLED = Pattern.compile(
            "\\bthrow\\b|\\bLog\\w*\\s*\\(|\\.\\s*Log\\w*\\s*\\(|\\blogger\\b|\\bILogger\\b"
                    + "|\\bConsole\\s*\\.|\\bTrace\\s*\\.|\\bDebug\\s*\\.|\\bAssert\\s*\\."
                    + "|\\bDebugger\\s*\\.|\\bExceptionDispatchInfo\\b|\\bEventLog\\b"
                    + "|\\bTelemetryClient\\b|\\bSerilog\\b|\\bNLog\\b|\\bActivity\\s*\\.");

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-CS-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|auth[_-]?token"
                            + "|access[_-]?token|private[_-]?key|client[_-]?secret|connectionstring)\\b"
                            + "\\s*[:=]\\s*[@$]{0,2}\""),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a "
                            + "secret store."),
            new Rule("CW-CS-SEC-002", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\bProcess\\s*\\.\\s*Start\\s*\\(|\\bnew\\s+ProcessStartInfo\\b"),
                    "External command execution: any input reaching the program name or arguments is a command "
                            + "injection."),
            new Rule("CW-CS-SEC-003", Severity.HIGH, FindingCategory.SECURITY, true, View.CODE,
                    Pattern.compile("(?i)(?:\\$@?\"[^\\n]*\\b(?:select|insert|update|delete|drop)\\b"
                            + "|\\b(?:select|insert|update|delete|drop)\\b[^\\n]*\"\\s*\\+"
                            + "|\\b(?:commandtext|sql|query)\\s*=\\s*[^;\\n]*\\+)"),
                    "SQL statement built by interpolation or concatenation: user input can change the query. Use a "
                            + "parameterised statement."),
            new Rule("CW-CS-SEC-004", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("ServerCertificate(Custom)?ValidationCallback|RemoteCertificateValidationCallback"
                            + "|DangerousAcceptAnyServerCertificateValidator"),
                    "TLS certificate verification is disabled or replaced with a validator that accepts anything: "
                            + "the connection is not authenticated."),
            new Rule("CW-CS-SEC-005", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(MD5|SHA1)[A-Za-z]*\\b"),
                    "Weak message digest (MD5/SHA-1). Use SHA-256 or better."),
            new Rule("CW-CS-SEC-006", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\bBinaryFormatter\\b|\\bNetDataContractSerializer\\b|\\bLosFormatter\\b"
                            + "|\\bObjectStateFormatter\\b|TypeNameHandling\\s*\\.\\s*(All|Auto|Objects)"
                            + "|\\bnew\\s+JavaScriptSerializer\\s*\\(\\s*new\\s+SimpleTypeResolver"),
                    "Unsafe deserialisation: a formatter that reconstructs arbitrary types from the payload lets "
                            + "an attacker choose the type, which is remote code execution. Use a schema-bound "
                            + "serializer."),
            new Rule("CW-CS-SEC-007", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\bnew\\s+Random\\s*\\("),
                    "System.Random is deterministic and its stream is guessable; use RandomNumberGenerator for "
                            + "anything security relevant."),
            new Rule("CW-CS-SEC-008", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\bAssembly\\s*\\.\\s*Load(From|File)?\\s*\\("),
                    "Loading an assembly by name at run time executes code chosen by whatever supplied the name; "
                            + "prefer a fixed, build-time reference."),
            new Rule("CW-CS-SEC-009", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("DtdProcessing\\s*\\.\\s*Parse|XmlResolver\\s*=(?!\\s*null)"),
                    "XML parsing with DTD processing or a non-null resolver resolves external entities, which is "
                            + "how an XML document reads local files or SSRF targets. Disable DTDs and leave the "
                            + "resolver unset."),
            new Rule("CW-CS-SEC-010", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\bAllowAnyOrigin\\s*\\(|\\bAllowAnyHeader\\s*\\(\\s*\\)\\s*\\.\\s*AllowAnyMethod"),
                    "A permissive CORS policy lets any site call this API with the user's credentials."));

    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-CS-REL-001", Severity.MEDIUM, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\.\\s*Wait\\s*\\(\\s*\\)|\\.\\s*GetAwaiter\\s*\\(\\s*\\)|\\.\\s*Result\\s*;"),
                    "Blocking on an asynchronous operation can deadlock and always consumes a thread; await it "
                            + "instead."),
            new Rule("CW-CS-REL-002", Severity.MEDIUM, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\basync\\s+void\\b"),
                    "async void cannot be awaited and its exceptions are raised on the synchronisation context: "
                            + "return Task, except in an event handler."),
            new Rule("CW-CS-REL-003", Severity.LOW, FindingCategory.RELIABILITY, false, View.DIRECTIVE,
                    Pattern.compile("#\\s*pragma\\s+warning\\s+disable|\\[\\s*SuppressMessage\\s*\\("),
                    "A warning is suppressed here; confirm the suppression is deliberate and narrow."),
            new Rule("CW-CS-REL-004", Severity.LOW, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\bGC\\s*\\.\\s*Collect\\s*\\("),
                    "Forcing a garbage collection is almost never what is wanted and blocks the process while it "
                            + "runs."),
            new Rule("CW-CS-REL-005", Severity.LOW, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\bThread\\s*\\.\\s*Sleep\\s*\\("),
                    "Thread.Sleep blocks a thread pool thread; await Task.Delay instead."),
            new Rule("CW-CS-REL-006", Severity.MEDIUM, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\block\\s*\\(\\s*this\\s*\\)"),
                    "Locking on this exposes the lock to every caller, and any of them can deadlock the others. "
                            + "Lock on a private object."),
            new Rule("CW-CS-REL-007", Severity.LOW, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\bthrow\\s+new\\s+(System\\s*\\.\\s*)?Exception\\s*\\("),
                    "Throwing the base Exception gives the caller nothing to catch specifically; throw a "
                            + "meaningful type."),
            new Rule("CW-CS-CORRECT-001", Severity.HIGH, FindingCategory.BUG, false, View.MASKED,
                    Pattern.compile("(?i)\\b(double|float)\\s+\\w*(amount|balance|price|total|fee|money|cost|cents)\\w*"),
                    "A monetary value is held as a binary float: rounding errors accumulate and cents go missing. "
                            + "Use decimal or integer minor units."),
            new Rule("CW-CS-CORRECT-002", Severity.MEDIUM, FindingCategory.CORRECTNESS, false, View.MASKED,
                    Pattern.compile("==\\s*-?\\d+\\.\\d+|\\d+\\.\\d+\\s*=="),
                    "Equality on a floating point literal is rarely what is meant; compare with a tolerance."),
            new Rule("CW-CS-CORRECT-003", Severity.MEDIUM, FindingCategory.CORRECTNESS, false, View.MASKED,
                    Pattern.compile("/\\s*86_?400_?000\\b"),
                    "Calendar-based time arithmetic: a limit expressed over a rolling window is not the same as a "
                            + "limit per calendar day. Check this against the requirement."),
            new Rule("CW-CS-CORRECT-004", Severity.MEDIUM, FindingCategory.CORRECTNESS, false, View.MASKED,
                    Pattern.compile("\\bDateTime\\s*\\.\\s*Now\\b|\\bDateTimeOffset\\s*\\.\\s*Now\\b"),
                    "DateTime.Now depends on the machine's time zone and clock; use UtcNow for anything recorded "
                            + "or compared."));

    private record Rule(
            String id,
            Severity severity,
            FindingCategory category,
            boolean security,
            View view,
            Pattern pattern,
            String message) {
    }

    /** The three views of one source file, split into lines. */
    private record LineViews(String[] code, String[] masked, String[] directives) {
    }

    @Override
    public String id() {
        return "csharp-heuristics";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CSHARP);
    }

    /** Reports every non-security finding: the source heuristics and tests that cannot fail. */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return findAll(context).stream().filter(finding -> !isSecurityRule(finding.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>(sourceFindings(context));
        findings.addAll(analyzeTests(context));
        findings.addAll(analyzeSwallowedCatches(context));
        return List.copyOf(findings);
    }

    /** @return the findings the rule table produces, without the whole-file analyses. */
    private List<Finding> sourceFindings(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : sourceFiles(context.projectInfo().sourceDirs())) {
            analyseFile(context, file, findings);
        }
        return List.copyOf(findings);
    }

    /** @return {@code true} when the rule is a security rule. */
    public static boolean isSecurityRule(String ruleId) {
        return ruleId != null && ruleId.startsWith("CW-CS-SEC");
    }

    /** @return security findings only; the source rule table is the only input. */
    public List<Finding> analyzeSecurity(VerificationContext context) {
        return sourceFindings(context).stream().filter(finding -> isSecurityRule(finding.ruleId())).toList();
    }

    private void analyseFile(VerificationContext context, Path file, List<Finding> findings) {
        String content = read(file);
        if (content.isEmpty()) {
            return;
        }
        String relative = relativePath(context, file);
        CSharpSourceScan.Views views = CSharpSourceScan.scan(content);
        LineViews lines = split(views.code(), views.masked(), views.directives());
        for (int i = 0; i < lines.code().length; i++) {
            int lineNumber = i + 1;
            // The suppression rule is the single, documented exception to "never fire inside a comment":
            // reporting the suppression is its whole purpose, so it reads the comment-preserving view.
            for (Rule rule : CORRECTNESS_RULES) {
                if (rule.view() == View.DIRECTIVE && rule.pattern().matcher(lines.directives()[i]).find()) {
                    findings.add(finding(rule, relative, lineNumber, lines.code()[i].strip()));
                }
            }
            if (lines.code()[i].isBlank()) {
                continue;
            }
            for (Rule rule : CORRECTNESS_RULES) {
                if (rule.view() != View.DIRECTIVE) {
                    addIfMatching(rule, lines, i, relative, findings);
                }
            }
            for (Rule rule : SECURITY_RULES) {
                addIfMatching(rule, lines, i, relative, findings);
            }
        }
    }

    private static void addIfMatching(
            Rule rule, LineViews lines, int index, String relative, List<Finding> findings) {
        String haystack = rule.view() == View.CODE ? lines.code()[index] : lines.masked()[index];
        if (rule.pattern().matcher(haystack).find()) {
            findings.add(finding(rule, relative, index + 1, lines.code()[index].strip()));
        }
    }

    /**
     * Analyses test sources for tests that cannot fail.
     *
     * <p>A test with no assertion passes whatever the code does. That is a real verification hazard
     * rather than a style question: a suite of such tests looks like coverage and constrains nothing, and
     * a reader of the report is entitled to know how much of the suite is of that kind.
     */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : sourceFiles(context.projectInfo().testSourceDirs())) {
            String content = read(file);
            if (content.isEmpty()) {
                continue;
            }
            String relative = relativePath(context, file);
            String masked = CSharpSourceScan.scan(content).masked();
            Matcher attribute = TEST_ATTRIBUTE.matcher(masked);
            while (attribute.find()) {
                int bodyStart = masked.indexOf('{', attribute.end());
                if (bodyStart < 0) {
                    continue;
                }
                int bodyEnd = matchingBrace(masked, bodyStart);
                if (bodyEnd <= bodyStart) {
                    continue;
                }
                String body = masked.substring(bodyStart, bodyEnd);
                if (!body.isBlank() && !ANY_ASSERTION.matcher(body).find()) {
                    findings.add(new Finding("csharp-heuristics", "CW-CS-TEST-001", Severity.MEDIUM,
                            FindingCategory.MAINTAINABILITY,
                            "Test '" + methodName(masked, attribute.end()) + "' has no assertion, so it passes "
                                    + "whatever the code does; a passing test that cannot fail is worse than no test.",
                            relative, CSharpSourceScan.lineOf(content, attribute.start()), null,
                            Map.of("snippet", firstLine(content, attribute.start()).strip())));
                }
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Analyses the source tree for exceptions that are swallowed.
     *
     * <p>An empty or non-reporting {@code catch} block turns a failure into silence: the caller cannot
     * tell that anything went wrong, and the run that should have failed passes. The block is found by
     * brace matching on the literal-blanked view, so a brace inside a string cannot move its end, and a
     * handler that rethrows, logs or asserts is not reported.
     */
    public List<Finding> analyzeSwallowedCatches(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : sourceFiles(context.projectInfo().sourceDirs())) {
            String content = read(file);
            if (content.isEmpty()) {
                continue;
            }
            String relative = relativePath(context, file);
            String masked = CSharpSourceScan.scan(content).masked();
            Matcher matcher = CATCH.matcher(masked);
            while (matcher.find()) {
                int bodyStart = masked.indexOf('{', matcher.start());
                int bodyEnd = matchingBrace(masked, bodyStart);
                if (bodyEnd <= bodyStart) {
                    continue;
                }
                String body = masked.substring(bodyStart + 1, bodyEnd);
                if (HANDLED.matcher(body).find()) {
                    continue;
                }
                findings.add(new Finding("csharp-heuristics", "CW-CS-REL-008", Severity.MEDIUM,
                        FindingCategory.RELIABILITY,
                        "The caught exception is neither handled nor reported, so the failure disappears and the "
                                + "caller cannot know it happened. Rethrow it, wrap it, or log it.",
                        relative, CSharpSourceScan.lineOf(content, matcher.start()), null,
                        Map.of("snippet", firstLine(content, matcher.start()).strip())));
            }
        }
        return List.copyOf(findings);
    }

    /** @return the offset of the brace matching the one at {@code open}, or -1 when it is unbalanced. */
    static int matchingBrace(String masked, int open) {
        if (open < 0 || open >= masked.length() || masked.charAt(open) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** @return the name of the method whose parameter list follows {@code from}, or {@code "<unknown>"}. */
    static String methodName(String masked, int from) {
        String tail = masked.substring(Math.min(from, masked.length()));
        Matcher matcher = Pattern.compile("\\(|;|\\}").matcher(tail);
        String between;
        if (matcher.find()) {
            between = tail.substring(0, matcher.start());
        } else {
            between = tail;
        }
        int split = between.strip().lastIndexOf(' ');
        String name = split < 0 ? between.strip() : between.strip().substring(split + 1);
        return name.isBlank() ? "<unknown>" : name;
    }

    private static String firstLine(String content, int offset) {
        int line = CSharpSourceScan.lineOf(content, offset) - 1;
        String[] lines = content.split("\n", -1);
        return line >= 0 && line < lines.length ? lines[line] : "";
    }

    private static Finding finding(Rule rule, String relative, int line, String snippet) {
        return new Finding("csharp-heuristics", rule.id(), rule.severity(), rule.category(),
                rule.message(), relative, line, null, Map.of("snippet", snippet.strip()));
    }

    private static LineViews split(String code, String masked, String directives) {
        return new LineViews(code.split("\n", -1), masked.split("\n", -1), directives.split("\n", -1));
    }

    private static List<Path> sourceFiles(List<Path> roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            files.addAll(CSharpProjectDetector.sourceFiles(root));
        }
        return files;
    }

    private static String read(Path file) {
        try {
            // Line endings are normalised so that the offsets the scanner reports and the line numbers
            // derived from them agree, whatever the file's own ending is.
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException _) {
            return "";
        }
    }

    static String relativePath(VerificationContext context, Path file) {
        Path root = context.projectRoot();
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }

    /** @return the identifiers of every rule this analyzer implements, for documentation and tests. */
    public static List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        SECURITY_RULES.forEach(rule -> ids.add(rule.id()));
        CORRECTNESS_RULES.forEach(rule -> ids.add(rule.id()));
        ids.add("CW-CS-REL-008");
        ids.add("CW-CS-TEST-001");
        return List.copyOf(ids);
    }

    /** @return the severity of a rule, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        return java.util.stream.Stream.concat(SECURITY_RULES.stream(), CORRECTNESS_RULES.stream())
                .filter(rule -> rule.id().equals(ruleId)).map(Rule::severity).findFirst()
                .orElse(Severity.MEDIUM);
    }
}

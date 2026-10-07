package dev.codewitness.cpp.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.cpp.CppProjectDetector;
import dev.codewitness.cpp.CppSourceScan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic source heuristics for C and C++.
 *
 * <p>C and C++ compilers already reject the programs that cannot run, and every modern compiler warns about
 * a growing share of the rest. What is left is what a compiler deliberately accepts: a string function that
 * trusts its input, an allocation whose lifetime nobody owns, money held as a binary float, a limit
 * computed over a calendar day where the requirement says a rolling window, a test with nothing to assert.
 * Each rule is a syntactic pattern with a documented false-positive profile; findings never on their own
 * make a requirement {@code VERIFIED}.
 *
 * <p>The compiler diagnostics a build produces are <em>not</em> re-run here. The build phase already
 * compiled the project and stored the compiler's own diagnostics with the command that produced them;
 * running the compiler a second time inside static analysis would duplicate that evidence under a different
 * rule id and make it ambiguous which run a diagnostic came from. This analyzer is the rule table that needs
 * no installation and fits in one auditable file, and it runs on the same source tree whatever else is
 * installed.
 *
 * <p>Two scanners feed the rules: {@link CppSourceScan} produces the comment-free, literal-blanked and
 * comment-preserving views of the file, and the findings that span lines - an empty catch, a test that
 * cannot fail - are found by brace matching on the literal-blanked view, so a brace inside a string cannot
 * move the end of a block.
 */
public final class CppHeuristicAnalyzer
        implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /** Which of the three views a rule is matched against. See {@link CppSourceScan}. */
    private enum View {
        /** Comment-free code with literal contents preserved: for rules whose evidence is the literal. */
        CODE,
        /** Comment-free code with literal contents blanked: for every ordinary rule. */
        MASKED,
        /** Code with comments preserved and literal contents blanked: for suppression directives. */
        DIRECTIVE
    }

    /** A test case macro in any of the frameworks a C or C++ project conventionally uses. */
    private static final Pattern TEST_CASE = Pattern.compile(
            "\\b(TEST|TEST_F|TEST_P|TYPED_TEST|SCENARIO|SECTION|BOOST_AUTO_TEST_CASE|BOOST_FIXTURE_TEST_CASE"
                    + "|BOOST_DATA_TEST_CASE|DOCTEST_TEST_CASE|TEST_CASE|SUBCASE)\\s*\\(");

    /** A plain function whose name says it is a test: {@code void withdrawalTest() { ... }}. */
    private static final Pattern TEST_FUNCTION = Pattern.compile(
            "(?m)^\\s*(?:static\\s+)?(?:void|int|bool)\\s+([A-Za-z_][A-Za-z0-9_]*[Tt]est[A-Za-z0-9_]*)\\s*\\([^;{]*\\)");

    /** Anything that lets a test fail. A test with none of these cannot fail, which is worse than none. */
    private static final Pattern ANY_ASSERTION = Pattern.compile(
            "\\bassert\\s*\\(|\\bASSERT_|\\bEXPECT_|\\bCHECK\\b|\\bREQUIRE\\b|\\bCHECK_EQ\\b"
                    + "|\\bthrow\\b|\\bstd::abort\\b|\\babort\\s*\\(|\\bstd::exit\\b|\\bexit\\s*\\("
                    + "|\\bfail\\s*\\(|\\bFAIL\\b|\\bREQUIRE_EQ\\b|\\breturn\\s+1\\b|!=\\s*|==\\s*");

    /** A {@code catch} clause and the brace that opens its body. */
    private static final Pattern CATCH = Pattern.compile("\\bcatch\\b\\s*(?:\\([^)]*\\))?\\s*\\{");

    /** Anything that makes a swallowed failure acceptable: it was reported, wrapped or rethrown. */
    private static final Pattern HANDLED = Pattern.compile(
            "\\bthrow\\b|\\bstd::cerr\\b|\\bstd::cout\\b|\\bstd::clog\\b|\\bprintf\\s*\\(|\\bfprintf\\s*\\("
                    + "|\\bperror\\s*\\(|\\blog\\w*\\s*\\(|\\bLOG\\w*\\b|\\bassert\\s*\\(|\\bstd::terminate\\b"
                    + "|\\bstd::rethrow_exception\\b|\\babort\\s*\\(|\\bstd::abort\\b");

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-CPP-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|auth[_-]?token"
                            + "|access[_-]?token|private[_-]?key|client[_-]?secret)\\b\\s*=\\s*\""),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a "
                            + "secret store."),
            new Rule("CW-CPP-SEC-002", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(strcpy|strcat|sprintf|vsprintf|gets)\\s*\\("),
                    "An unbounded C string function: it writes as much as its input asks for. Use the "
                            + "std::string and std::span operations, or a bounded function whose bound you "
                            + "checked."),
            new Rule("CW-CPP-SEC-003", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(system|popen|execl|execlp|execle|execv|execvp|execvpe)\\s*\\("),
                    "External command execution: any input reaching the program name or arguments is a "
                            + "command injection. Use an API that takes an argument vector, and never a "
                            + "shell string built from input."),
            new Rule("CW-CPP-SEC-004", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(malloc|calloc|realloc|alloca)\\s*\\("),
                    "Raw allocation: the size arithmetic overflows silently and the lifetime belongs to "
                            + "whoever remembers to free it. Prefer a container or a smart pointer."),
            new Rule("CW-CPP-SEC-005", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(memset|memcpy|memmove|strncpy|strncat|snprintf|vsnprintf)\\s*\\("),
                    "A C memory or string function whose bounds are the caller's responsibility. Check the "
                            + "length against the destination, and remember that strncpy does not terminate "
                            + "what it copies."),
            new Rule("CW-CPP-SEC-006", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(printf|vprintf|syslog)\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\s*[,)]"
                            + "|\\bfprintf\\s*\\(\\s*[^,;]+,\\s*[A-Za-z_][A-Za-z0-9_]*\\s*[,)]"),
                    "Format string that is not a literal: if any part of it came from outside the program, "
                            + "the caller chooses the conversions. Use a literal format such as \"%s\"."),
            new Rule("CW-CPP-SEC-007", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(rand|rand_r|srand|random)\\s*\\(|\\bstd::rand\\b"),
                    "This pseudo-random generator is deterministic and its stream is guessable; use "
                            + "std::random_device with a real engine for anything security relevant."),
            new Rule("CW-CPP-SEC-008", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(MD5|SHA1|SHA_1)\\w*\\s*\\(|\\bEVP_md5\\s*\\(|\\bEVP_sha1\\s*\\("),
                    "Weak message digest (MD5/SHA-1). Use SHA-256 or better."),
            new Rule("CW-CPP-SEC-009", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("SSL_VERIFY_NONE|CURLOPT_SSL_VERIFYPEER\\s*,\\s*(0|false)"
                            + "|CURLOPT_SSL_VERIFYHOST\\s*,\\s*(0|false)"),
                    "TLS certificate verification is disabled: the connection is not authenticated. Remove "
                            + "the override and fix the certificate instead."),
            new Rule("CW-CPP-SEC-010", Severity.HIGH, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(strtok|gets|scanf|sscanf|fscanf)\\s*\\("),
                    "Unsafe parsing of input: the destination's size is not part of the call, so a long field "
                            + "writes past it. Read a line and parse it with the length known."),
            new Rule("CW-CPP-SEC-011", Severity.MEDIUM, FindingCategory.SECURITY, true, View.MASKED,
                    Pattern.compile("\\b(setuid|setgid|seteuid|setegid|chmod|chown)\\s*\\("),
                    "A privilege or permission change: confirm the program drops privileges rather than "
                            + "gaining them, and that the path is not attacker-chosen."),
            new Rule("CW-CPP-SEC-012", Severity.HIGH, FindingCategory.SECURITY, true, View.CODE,
                    Pattern.compile("(?i)\"[^\"\\n]*\\b(select|insert|update|delete|drop)\\b[^\"\\n]*\"\\s*\\+"
                            + "|\\b(sprintf|snprintf|strcat|strcpy)\\s*\\([^;\\n]*\\b(select|insert|update"
                            + "|delete|drop)\\b"),
                    "SQL statement built by concatenation or formatting: user input can change the query. Use "
                            + "a parameterised statement."));

    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-CPP-MEM-001", Severity.HIGH, FindingCategory.BUG, false, View.MASKED,
                    Pattern.compile("\\bfree\\s*\\("),
                    "A raw pointer is freed by hand, and double free and use after free are the failure "
                            + "modes. Prefer a smart pointer that states the ownership."),
            new Rule("CW-CPP-MEM-002", Severity.MEDIUM, FindingCategory.BUG, false, View.MASKED,
                    Pattern.compile("\\b(memcpy|memset|memmove)\\s*\\([^;\\n]*sizeof\\s*\\(\\s*"
                            + "[A-Za-z_][A-Za-z0-9_]*\\s*\\)"),
                    "sizeof of what may be a pointer rather than the array it points at: a byte count of the "
                            + "pointer's width is almost never what was meant. Use sizeof of the array, or "
                            + "std::size of it."),
            new Rule("CW-CPP-MEM-003", Severity.MEDIUM, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\b(auto_ptr|std::auto_ptr)\\b"),
                    "std::auto_ptr was removed in C++17 and silently transfers ownership on copy; use "
                            + "std::unique_ptr."),
            new Rule("CW-CPP-BOUND-001", Severity.HIGH, FindingCategory.BUG, false, View.MASKED,
                    Pattern.compile("\\bfor\\s*\\([^;\\n]*;[^;\\n]*<=\\s*[^;\\n]*\\.\\s*size\\s*\\(\\s*\\)"),
                    "A loop that runs while the index is less than or equal to a size runs one step past the "
                            + "end. Compare with < , or iterate the container."),
            new Rule("CW-CPP-NUM-001", Severity.HIGH, FindingCategory.BUG, false, View.MASKED,
                    Pattern.compile("(?i)\\b(float|double)\\s+\\w*(amount|balance|price|total|fee|money|cost"
                            + "|cents|sum)\\w*"),
                    "A monetary value is held as a binary float: rounding errors accumulate and cents go "
                            + "missing. Use a decimal type or integer minor units."),
            new Rule("CW-CPP-NUM-002", Severity.MEDIUM, FindingCategory.CORRECTNESS, false, View.MASKED,
                    Pattern.compile("==\\s*-?\\d+\\.\\d+|\\b\\d+\\.\\d+\\s*=="),
                    "Equality on a floating point literal is rarely what is meant; compare with a tolerance."),
            new Rule("CW-CPP-CORRECT-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, false, View.MASKED,
                    Pattern.compile("\\b86400\\b|\\b86_400\\b|\\b86_?400_?000\\b|\\b3600\\s*\\*\\s*24\\b"
                            + "|\\b24\\s*\\*\\s*3600\\b"),
                    "Calendar-based time arithmetic: a limit expressed over a rolling window is not the same "
                            + "as a limit per calendar day. Check this against the requirement."),
            new Rule("CW-CPP-CORRECT-002", Severity.MEDIUM, FindingCategory.CORRECTNESS, false, View.MASKED,
                    Pattern.compile("\\bstd::chrono::system_clock::now\\b|\\btime\\s*\\(\\s*(NULL|nullptr|0)\\s*\\)"
                            + "|\\bstd::localtime\\b|\\bstd::gmtime\\b|\\bstd::time\\b"),
                    "Wall-clock time depends on the machine's time zone and clock; use a steady clock for "
                            + "elapsed time and record an offset for anything stored."),
            new Rule("CW-CPP-REL-001", Severity.LOW, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\b(exit|abort|quick_exit|std::exit|std::abort)\\s*\\("),
                    "Terminating the process from library code gives the caller no way to clean up or to "
                            + "recover. Return a result and let the caller decide."),
            new Rule("CW-CPP-REL-002", Severity.LOW, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\bgoto\\b"),
                    "A goto makes the flow of control hard to follow and skips destructors; use a loop or a "
                            + "function."),
            new Rule("CW-CPP-REL-003", Severity.LOW, FindingCategory.RELIABILITY, false, View.DIRECTIVE,
                    Pattern.compile("NOLINT|cppcheck-suppress|#\\s*pragma\\s+(GCC|clang)\\s+diagnostic\\s+ignored"
                            + "|//\\s*codewitness:ignore"),
                    "A diagnostic is suppressed here; confirm the suppression is deliberate and narrow."),
            new Rule("CW-CPP-CONC-001", Severity.MEDIUM, FindingCategory.RELIABILITY, false, View.MASKED,
                    Pattern.compile("\\bstd::thread\\b|\\bpthread_create\\s*\\(|\\bstd::mutex\\b|\\bstd::atomic\\b"),
                    "A concurrency primitive: check that every piece of shared state it guards is guarded by "
                            + "it everywhere, and that the thread outlives the work it runs."));

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
        return "cpp-heuristics";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CPP);
    }

    /** Reports every non-security finding: the source heuristics, empty catches and tests that cannot fail. */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return findAll(context).stream().filter(finding -> !isSecurityRule(finding.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>(sourceFindings(context));
        findings.addAll(analyzeTests(context));
        findings.addAll(analyzeEmptyCatches(context));
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
        return ruleId != null && ruleId.startsWith("CW-CPP-SEC");
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
        CppSourceScan.Views views = CppSourceScan.scan(content);
        LineViews lines = split(views.code(), views.masked(), views.directives());
        boolean header = isHeader(file);
        for (int i = 0; i < lines.code().length; i++) {
            int lineNumber = i + 1;
            String snippet = lines.code()[i].strip();
            // The suppression rule is the single, documented exception to "never fire inside a comment":
            // reporting the suppression is its whole purpose, so it reads the comment-preserving view.
            for (Rule rule : CORRECTNESS_RULES) {
                if (rule.view() == View.DIRECTIVE && rule.pattern().matcher(lines.directives()[i]).find()) {
                    findings.add(finding(rule, relative, lineNumber, snippet));
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
            // A using-directive in a header leaks every name into every translation unit that includes it,
            // which is a property of the file rather than of the line, so the rule checks the extension too.
            if (header && USING_NAMESPACE.matcher(lines.masked()[i]).find()) {
                findings.add(new Finding(id(), "CW-CPP-CORRECT-003", Severity.MEDIUM,
                        FindingCategory.MAINTAINABILITY,
                        "A header says using namespace at file scope, so every translation unit that includes "
                                + "it gets every name in that namespace. Name the qualifier instead, or put "
                                + "the directive in the source file.",
                        relative, lineNumber, null, Map.of("snippet", snippet)));
            }
        }
    }

    /** {@code using namespace <name>;} at the start of a line, which is file scope rather than a body. */
    private static final Pattern USING_NAMESPACE =
            Pattern.compile("(?m)^\\s*using\\s+namespace\\s+[A-Za-z_][A-Za-z0-9_:]*\\s*;");

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
     * <p>A test with no assertion passes whatever the code does. That is a real verification hazard rather
     * than a style question: a suite of such tests looks like coverage and constrains nothing, and a reader
     * of the report is entitled to know how much of the suite is of that kind.
     */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : sourceFiles(context.projectInfo().testSourceDirs())) {
            String content = read(file);
            if (content.isEmpty()) {
                continue;
            }
            String relative = relativePath(context, file);
            String masked = CppSourceScan.scan(content).masked();
            Matcher testCase = TEST_CASE.matcher(masked);
            while (testCase.find()) {
                addTestFinding(findings, masked, content, testCase.end(), relative,
                        macroName(masked, testCase.end(), testCase.group(1)));
            }
            Matcher function = TEST_FUNCTION.matcher(masked);
            while (function.find()) {
                addTestFinding(findings, masked, content, function.end(), relative, function.group(1));
            }
        }
        return List.copyOf(findings);
    }

    private void addTestFinding(
            List<Finding> findings, String masked, String content, int from, String relative, String name) {
        int bodyStart = masked.indexOf('{', from);
        if (bodyStart < 0) {
            return;
        }
        int bodyEnd = matchingBrace(masked, bodyStart);
        if (bodyEnd <= bodyStart) {
            return;
        }
        String body = masked.substring(bodyStart, bodyEnd);
        if (!body.isBlank() && !ANY_ASSERTION.matcher(body).find()) {
            findings.add(new Finding(id(), "CW-CPP-TEST-001", Severity.MEDIUM,
                    FindingCategory.MAINTAINABILITY,
                    "Test '" + name + "' has no assertion, so it passes whatever the code does; a passing "
                            + "test that cannot fail is worse than no test.",
                    relative, CppSourceScan.lineOf(content, from), null,
                    Map.of("snippet", firstLine(content, from).strip())));
        }
    }

    /**
     * @return the name a test-case macro's body belongs to: the quoted name when the macro carries one
     *     (a Catch2 {@code TEST_CASE("a fee is added")}), the identifier the macro applies to when it does
     *     not (a GoogleTest {@code TEST(LedgerTest, AddsAFee)}), and the macro's own name when the tail holds
     *     neither.
     */
    static String macroName(String masked, int from, String macro) {
        String tail = masked.substring(Math.min(from, masked.length()));
        Matcher quoted = Pattern.compile("\"([^\"]*)\"").matcher(tail);
        if (quoted.find()) {
            return quoted.group(1).isBlank() ? macro : quoted.group(1);
        }
        int end = tail.length();
        for (String terminator : List.of("(", "{", ";", ")", ",")) {
            int index = tail.indexOf(terminator);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        String between = tail.substring(0, end).strip();
        return between.isEmpty() ? macro : between;
    }

    /**
     * Analyses the source tree for failures that are swallowed.
     *
     * <p>An empty or non-reporting {@code catch} block turns a failure into silence: the caller cannot tell
     * that anything went wrong, and the run that should have failed passes. The block is found by brace
     * matching on the literal-blanked view, so a brace inside a string cannot move its end, and a handler
     * that rethrows, logs or asserts is not reported.
     */
    public List<Finding> analyzeEmptyCatches(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : sourceFiles(context.projectInfo().sourceDirs())) {
            String content = read(file);
            if (content.isEmpty()) {
                continue;
            }
            String relative = relativePath(context, file);
            String masked = CppSourceScan.scan(content).masked();
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
                findings.add(new Finding(id(), "CW-CPP-CORRECT-004", Severity.MEDIUM,
                        FindingCategory.RELIABILITY,
                        "The caught exception is neither handled nor reported, so the failure disappears and "
                                + "the caller cannot know it happened. Rethrow it, wrap it, or log it.",
                        relative, CppSourceScan.lineOf(content, matcher.start()), null,
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

    private static String firstLine(String content, int offset) {
        int line = CppSourceScan.lineOf(content, offset) - 1;
        String[] lines = content.split("\n", -1);
        return line >= 0 && line < lines.length ? lines[line] : "";
    }

    private static Finding finding(Rule rule, String relative, int line, String snippet) {
        return new Finding("cpp-heuristics", rule.id(), rule.severity(), rule.category(),
                rule.message(), relative, line, null, Map.of("snippet", snippet.strip()));
    }

    private static LineViews split(String code, String masked, String directives) {
        return new LineViews(code.split("\n", -1), masked.split("\n", -1), directives.split("\n", -1));
    }

    /** @return every source file of the given roots, deduplicated so a shared root is scanned once. */
    private static List<Path> sourceFiles(List<Path> roots) {
        Set<Path> files = new LinkedHashSet<>();
        for (Path root : roots) {
            files.addAll(CppProjectDetector.sourceFiles(root));
        }
        return List.copyOf(files);
    }

    private static boolean isHeader(Path file) {
        String extension = CppProjectDetector.extensionOf(file);
        return extension.equals("h") || extension.equals("hpp") || extension.equals("hh")
                || extension.equals("hxx") || extension.equals("ipp") || extension.equals("tpp");
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
        ids.add("CW-CPP-CORRECT-003");
        ids.add("CW-CPP-CORRECT-004");
        ids.add("CW-CPP-TEST-001");
        return List.copyOf(ids);
    }

    /** @return the severity of a rule, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        return java.util.stream.Stream.concat(SECURITY_RULES.stream(), CORRECTNESS_RULES.stream())
                .filter(rule -> rule.id().equals(ruleId)).map(Rule::severity).findFirst()
                .orElse(Severity.MEDIUM);
    }
}

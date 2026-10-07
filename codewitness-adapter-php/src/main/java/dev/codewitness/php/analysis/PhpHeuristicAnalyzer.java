package dev.codewitness.php.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.php.PhpProjectDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic source heuristics for PHP.
 *
 * <p>These rules exist because PHP runs happily while being wrong in well known ways: a loose
 * {@code ==} where {@code ===} was meant, {@code strpos} compared with {@code ==}, {@code in_array}
 * without its strict flag, money kept as a binary float, a variable interpolated straight into SQL,
 * an error swallowed by {@code @} or an empty {@code catch}, or a test method with no assertion. Each
 * rule is a syntactic pattern with a documented false-positive profile; a finding is reported with
 * {@code MEDIUM}/{@code LOW}/{@code HIGH} confidence and never on its own makes a requirement
 * {@code VERIFIED} - the analyzer's id contains {@code heuristic} precisely so the engine can tell it
 * apart from a real tool.
 *
 * <p>Every rule reads the source through {@link PhpSourceScan}, so a rule cannot fire on text that only
 * looks like code: a commented-out line is invisible to all of them, text inside a string is invisible to
 * the rules that read statements, and the rules that are <em>about</em> a literal - the credential rule,
 * the interpolated-SQL rule - read the projection that keeps literal text. No finding is produced without
 * an observed match: a rule that matched nothing reports nothing at all, rather than a note that it was
 * checked and found nothing.
 *
 * <p>This is not a replacement for PHPStan or Psalm; it is the analyzer that is always available, needs
 * no installation, and whose entire rule table fits in one auditable file.
 */
public final class PhpHeuristicAnalyzer implements VerificationTools.StaticAnalyzer, VerificationTools.SecurityAnalyzer {

    /**
     * The analyzer's own id.
     *
     * <p>It contains {@code heuristic} deliberately: the core reads that word from a finding's analyzer id
     * and treats the finding as advisory, so a pattern-matching rule can never fail a requirement on its
     * own the way a real tool's finding can.
     */
    private static final String ANALYZER = "php-heuristics";

    /** Which reading of the source a rule is written against. */
    private enum Projection {
        /** Only code: comments blanked and literal contents replaced by spaces of the same length. */
        CODE,
        /** Everything except comments: needed by a rule whose pattern contains a literal. */
        LITERALS
    }

    /** A loosened comparison whose right-hand side is {@code null}: {@code ===} is meant, not {@code ==}. */
    private static final String LOOSE_EQUALS = "(?<![=!])[!=]=(?!=)";

    /** A single syntactic rule: an id, how severe it is, and where in the source it looks. */
    private record Rule(
            String id,
            Severity severity,
            FindingCategory category,
            Projection projection,
            Pattern pattern,
            String message) {
    }

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-PHP-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?i)(?:\\$(?:password|passwd|pwd|secret|api[_-]?key|auth[_-]?token|access[_-]?token|private[_-]?key)\\b\\s*=\\s*"
                            + "|define\\s*\\(\\s*['\"][A-Z0-9_]*(?:PASSWORD|SECRET|API_KEY|TOKEN|PRIVATE_KEY)[A-Z0-9_]*['\"]\\s*,\\s*)"
                            + "['\"][^'\"]{4,}['\"]"),
                    "Hardcoded credential: a secret literal is embedded in the source. Use configuration or a secret store."),
            new Rule("CW-PHP-SEC-002", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w>$])(?:eval|assert|create_function)\\s*\\("),
                    "eval()/assert()/create_function() evaluates a value as PHP code: any input reaching it executes arbitrary code."),
            new Rule("CW-PHP-SEC-003", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("\\bpreg_replace\\s*\\(\\s*['\"][^'\"]*/\\w*e\\w*['\"]"),
                    "preg_replace with the /e modifier evaluates the replacement as PHP code. Use preg_replace_callback."),
            new Rule("CW-PHP-SEC-004", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w>$])(?:shell_exec|proc_open|passthru|popen|system|exec)\\s*\\("),
                    "External command execution: input reaching this call allows arbitrary command execution. Escape with escapeshellarg and prefer an argument list."),
            new Rule("CW-PHP-SEC-005", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("`[^`]*\\$[A-Za-z_{]"),
                    "Backtick shell execution with an interpolated value: the shell receives whatever the variable holds."),
            new Rule("CW-PHP-SEC-006", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w>$])unserialize\\s*\\("),
                    "unserialize() on untrusted data constructs arbitrary objects and can run their magic methods. Use JSON, or unserialize with an allowed_classes list."),
            new Rule("CW-PHP-SEC-007", Severity.MEDIUM, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w>$])(?:md5_file|sha1_file|md5|sha1|crc32)\\s*\\("),
                    "Weak message digest (MD5/SHA-1/CRC32). Use SHA-256 or better, and password_hash for passwords."),
            new Rule("CW-PHP-SEC-008", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?:->query|->exec|->prepare|mysqli_query|mysqli_prepare|pg_query)\\s*\\(\\s*\"[^\"]*\\$[A-Za-z_{]"),
                    "SQL statement with a variable interpolated into it: user input can change the query. Use a parameterised statement."),
            new Rule("CW-PHP-SEC-009", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?:->query|->exec|->prepare|mysqli_query|mysqli_prepare|pg_query)\\s*\\(\\s*[^;\\n]{0,120}?['\"]\\s*\\.\\s*\\$"),
                    "SQL statement built by concatenating a variable onto a string: user input can change the query. Use a parameterised statement."),
            new Rule("CW-PHP-SEC-010", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("\\b(?:include|include_once|require|require_once)\\b[^;\\n]*\\$[A-Za-z_{]"),
                    "A file is included from a computed path: input reaching it allows reading and running an arbitrary file."),
            new Rule("CW-PHP-SEC-011", Severity.MEDIUM, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("\\bextract\\s*\\(\\s*\\$_(?:GET|POST|REQUEST|COOKIE|SERVER)"),
                    "extract() on request data lets a caller overwrite any variable in scope, including the ones that decide what happens next."),
            new Rule("CW-PHP-SEC-012", Severity.MEDIUM, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("CURLOPT_SSL_VERIFYPEER\\s*,\\s*(?:false|0)\\b"
                            + "|CURLOPT_SSL_VERIFYHOST\\s*,\\s*0\\b"
                            + "|['\"]verify_peer['\"]\\s*=>\\s*false"),
                    "TLS certificate verification is disabled: the connection is not authenticated."),
            new Rule("CW-PHP-SEC-013", Severity.MEDIUM, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("\\b(?:tempnam|tmpfile)\\s*\\(|['\"]/tmp/"),
                    "Insecure temporary file: a predictable name can be guessed and raced. Use tempnam and check the result, or a stream."));

    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-PHP-CORRECT-001", Severity.HIGH, FindingCategory.BUG, Projection.CODE,
                    Pattern.compile("\\b(?:strpos|stripos|strrpos|strripos|array_search)\\s*\\([^;\\n]*\\)\\s*"
                            + LOOSE_EQUALS + "\\s*(?:false|true|0|null)\\b"),
                    "strpos/array_search is compared with == to false: position 0 is falsy, so 'needle' at the start "
                            + "reads as not found. Use === false or !== false."),
            new Rule("CW-PHP-CORRECT-002", Severity.MEDIUM, FindingCategory.CORRECTNESS, Projection.CODE,
                    Pattern.compile(LOOSE_EQUALS + "\\s*null\\b"),
                    "Loose comparison with null: 0, '' and false are all == null in PHP 7 terms, which is rarely what "
                            + "was meant. Use === null / !== null."),
            new Rule("CW-PHP-CORRECT-003", Severity.MEDIUM, FindingCategory.BUG, Projection.CODE,
                    Pattern.compile("\\b(?:in_array|array_search)\\s*\\(\\s*[^;,\\n()]+,\\s*[^;,\\n()]+\\)"),
                    "in_array/array_search without the strict flag uses ==, so 0, '0', false and '' all match each "
                            + "other. Pass true as the third argument."),
            new Rule("CW-PHP-CORRECT-004", Severity.MEDIUM, FindingCategory.BUG, Projection.LITERALS,
                    Pattern.compile("(?i)\\$\\w*(?:amount|balance|total|price|money|fee|cost|subtotal)\\w*\\s*=\\s*[^=;\\n]*\\d+\\.\\d+"),
                    "A monetary or quantity value is assigned a binary floating point literal: rounding errors "
                            + "accumulate and cents go missing. Use integer cents, a string, or bcmath/GMP."),
            new Rule("CW-PHP-CORRECT-006", Severity.MEDIUM, FindingCategory.CORRECTNESS, Projection.LITERALS,
                    Pattern.compile("\\bstrtotime\\s*\\(|(?<![\\w>$])date\\s*\\(\\s*['\"][^'\"]*[Ymd][^'\"]*['\"]"),
                    "Calendar-based date arithmetic: a limit expressed over a rolling window is not the same as a "
                            + "limit per calendar day. Check this against the requirement."),
            new Rule("CW-PHP-CORRECT-007", Severity.MEDIUM, FindingCategory.PERFORMANCE, Projection.CODE,
                    Pattern.compile("\\bfor\\s*\\([^;]*;[^;]*\\bcount\\s*\\("),
                    "count() in a for-condition is re-evaluated on every iteration, and in PHP 8 it is a TypeError "
                            + "when the value is not countable. Compute it once."));

    private static final List<Rule> RELIABILITY_RULES = List.of(
            new Rule("CW-PHP-REL-001", Severity.MEDIUM, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("(?<![\\w@$])\\s*@\\s*[A-Za-z_$]"),
                    "The @ operator suppresses the diagnostic and hides the failure: a missing file, a broken "
                            + "connection and a bug all look the same. Handle the error instead."),
            new Rule("CW-PHP-REL-003", Severity.LOW, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("catch\\s*\\(\\s*\\\\?(?:Exception|Throwable)\\b"),
                    "Catching Exception/Throwable broadly hides programming errors as well as expected failures."),
            new Rule("CW-PHP-REL-004", Severity.LOW, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("(?<![\\w>$])(?:die|exit)\\b"),
                    "die/exit terminates the process: a library that calls it cannot be tested or reused, and the "
                            + "failure leaves no evidence. Throw an exception. A CLI entry script is the expected false positive."),
            new Rule("CW-PHP-REL-005", Severity.MEDIUM, FindingCategory.RELIABILITY, Projection.LITERALS,
                    Pattern.compile("error_reporting\\s*\\(\\s*0\\s*\\)"
                            + "|ini_set\\s*\\(\\s*['\"]display_errors['\"]\\s*,\\s*['\"]?0"),
                    "Error reporting is switched off: a failure then reaches the caller as a wrong value with no "
                            + "diagnostic anywhere."),
            new Rule("CW-PHP-REL-006", Severity.MEDIUM, FindingCategory.BUG, Projection.CODE,
                    Pattern.compile("\\bif\\s*\\(\\s*\\$[A-Za-z_]\\w*\\s*=(?!=)"),
                    "Assignment inside an if-condition: the condition is the assigned value, which is usually a "
                            + "typo for ==. Parenthesise the assignment if it is deliberate."),
            new Rule("CW-PHP-REL-007", Severity.LOW, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("\\bglobal\\s+\\$[A-Za-z_]"),
                    "global state makes a function depend on the order calls happened in and is invisible at the "
                            + "call site. Pass the value in."));

    /** An empty catch block, over the whole file: the failure is discarded without a trace. */
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}");

    /** A foreach that iterates by reference: the reference survives the loop and corrupts the next one. */
    private static final Pattern FOREACH_REFERENCE =
            Pattern.compile("foreach\\s*\\([^;\\n]*\\bas\\s*&\\s*\\$(\\w+)");

    /** A PHPUnit test method: named test*, or marked with the #[Test] attribute. */
    private static final Pattern TEST_METHOD = Pattern.compile(
            "(?m)(#\\[\\s*(?:\\\\?[A-Za-z_][\\w\\\\]*\\\\)?Test\\b[^\\]]*\\]\\s*)?"
                    + "(?:(?:public|protected|private|final|abstract|static)\\s+)*"
                    + "function\\s+&?\\s*(\\w+)\\s*\\(");

    /** Anything that can make a test fail; a test without one passes whatever the code does. */
    private static final Pattern ASSERTION = Pattern.compile(
            "\\bassert\\w*\\s*\\(|->expectException|->expectExceptionMessage|->expectOutputString|\\bfail\\s*\\(");

    /**
     * The name shape of the tests this adapter's generator writes, so that its own scaffolding is not
     * analysed as a finding about the project.
     *
     * <p>The generator is not referenced here on purpose - the naming is part of the adapter's fixed
     * contract, while depending on the generator class would make the rule table refuse to compile without
     * the generation phase.
     */
    private static final Pattern GENERATED_TEST = Pattern.compile("^CwSc\\w*Test\\.(?:php|phtml)$");

    @Override
    public String id() {
        return ANALYZER;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    /** @return every non-security finding: correctness and reliability heuristics, plus tests that cannot fail. */
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

    /** @return the security subset of {@link #findAll(VerificationContext)}, for the security analyzer. */
    public List<Finding> analyzeSecurity(VerificationContext context) {
        return findAll(context).stream().filter(finding -> isSecurityRule(finding.ruleId())).toList();
    }

    /** @return {@code true} when the rule is a security rule. */
    public static boolean isSecurityRule(String ruleId) {
        return ruleId != null && ruleId.startsWith("CW-PHP-SEC");
    }

    /**
     * Analyses test sources for tests that cannot fail, which is a real verification hazard: a passing
     * test that has no assertion looks like evidence and is not.
     *
     * <p>Two shapes are recognised, which are the two PHPUnit itself recognises: a method whose name
     * starts with {@code test}, and a method marked with the {@code #[Test]} attribute. A method marked
     * only by the older docblock annotation, and a Pest closure, are not recognised - a documented false
     * negative rather than a guess, because a docblock is a comment to every rule that reads code.
     */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path root : context.projectInfo().testSourceDirs()) {
            for (Path file : PhpProjectDetector.phpFiles(root)) {
                if (isGeneratedTest(file)) {
                    // A generated test is scaffolding this run wrote for itself; reporting it would be a
                    // finding about this tool's own output rather than about the project.
                    continue;
                }
                String code = PhpSourceScan.stripLiterals(read(file));
                if (code.isEmpty()) {
                    continue;
                }
                String relative = relativePath(context, file);
                Matcher matcher = TEST_METHOD.matcher(code);
                while (matcher.find()) {
                    boolean attributed = matcher.group(1) != null;
                    String name = matcher.group(2);
                    if (!attributed && !name.toLowerCase(Locale.ROOT).startsWith("test")) {
                        continue;
                    }
                    String body = methodBody(code, matcher.end() - 1);
                    if (body != null && !ASSERTION.matcher(body).find()) {
                        findings.add(new Finding(ANALYZER, "CW-PHP-TEST-001", Severity.MEDIUM,
                                FindingCategory.MAINTAINABILITY,
                                "Test '" + name + "' has no assertion, so it passes whatever the code does. A "
                                        + "passing test that cannot fail is worse than no test: it looks like evidence.",
                                relative, PhpSourceScan.lineOf(code, matcher.start()), null, Map.of()));
                    }
                }
            }
        }
        return findings;
    }

    private void analyseFile(VerificationContext context, Path file, List<Finding> findings) {
        String content = read(file);
        if (content.isEmpty()) {
            return;
        }
        String relative = relativePath(context, file);
        // Two readings of the same source, both the same length as it and both keeping its newlines: rules
        // that look for statements read the code alone, and rules that read a literal read everything
        // except the comments.
        PhpSourceScan.Views views = PhpSourceScan.scan(content);
        String[] codeLines = views.code().split("\\R", -1);
        String[] maskedLines = views.masked().split("\\R", -1);
        int lineCount = Math.min(codeLines.length, maskedLines.length);

        for (int i = 0; i < lineCount; i++) {
            for (Rule rule : CORRECTNESS_RULES) {
                report(rule, subject(rule, codeLines[i], maskedLines[i]), relative, i + 1, findings);
            }
            for (Rule rule : RELIABILITY_RULES) {
                report(rule, subject(rule, codeLines[i], maskedLines[i]), relative, i + 1, findings);
            }
            for (Rule rule : SECURITY_RULES) {
                report(rule, subject(rule, codeLines[i], maskedLines[i]), relative, i + 1, findings);
            }
        }

        Matcher emptyCatch = EMPTY_CATCH.matcher(views.masked());
        while (emptyCatch.find()) {
            findings.add(new Finding(ANALYZER, "CW-PHP-REL-002", Severity.MEDIUM, FindingCategory.RELIABILITY,
                    "Empty catch block: the failure is silently discarded.",
                    relative, PhpSourceScan.lineOf(views.masked(), emptyCatch.start()), null, Map.of()));
        }

        Matcher reference = FOREACH_REFERENCE.matcher(views.masked());
        while (reference.find()) {
            String variable = reference.group(1);
            if (!views.masked().contains("unset($" + variable + ")")) {
                findings.add(new Finding(ANALYZER, "CW-PHP-CORRECT-005", Severity.MEDIUM, FindingCategory.BUG,
                        "foreach iterates '" + variable + "' by reference and never unsets it: the reference "
                                + "survives the loop, so the next loop over the same variable assigns through it.",
                        relative, PhpSourceScan.lineOf(views.masked(), reference.start()), null,
                        Map.of("variable", variable)));
            }
        }
    }

    private static String subject(Rule rule, String codeLine, String maskedLine) {
        return rule.projection() == Projection.CODE ? maskedLine : codeLine;
    }

    private static void report(Rule rule, String line, String relative, int lineNumber, List<Finding> findings) {
        if (line != null && rule.pattern().matcher(line).find()) {
            findings.add(finding(rule, relative, lineNumber, line));
        }
    }

    /**
     * @param code the source's masked view
     * @param from the offset of the {@code (} that opens the method's parameter list
     * @return the text of the method body, or {@code null} when the declaration has no body
     *
     * <p>The braces are matched by hand rather than with a regular expression because a body may contain a
     * closure, and a lazy match to the first {@code }} would cut the body short and hide the assertion.
     */
    private static String methodBody(String code, int from) {
        int open = code.indexOf('(', from);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        int i = open;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                i++;
                break;
            }
            i++;
        }
        int brace = code.indexOf('{', i);
        if (brace < 0) {
            return null;
        }
        int braces = 0;
        for (int j = brace; j < code.length(); j++) {
            char c = code.charAt(j);
            if (c == '{') {
                braces++;
            } else if (c == '}' && --braces == 0) {
                return code.substring(brace, j + 1);
            }
        }
        return code.substring(brace);
    }

    private static Finding finding(Rule rule, String relative, int line, String snippet) {
        return new Finding(ANALYZER, rule.id(), rule.severity(), rule.category(), rule.message(),
                relative, line, null, Map.of("snippet", snippet.strip()));
    }

    /**
     * @return the project's own source files, with the test roots left out
     *
     * <p>Test sources are analysed separately, for tests that cannot fail. Counting a test fixture's
     * credential as a production finding would attribute the test's decision to the code it tests, and a
     * test root that <em>is</em> the project root - which is how this detector describes a flat project -
     * is deliberately kept, because excluding it would exclude the sources with it.
     */
    private static List<Path> sourceFiles(VerificationContext context) {
        Path projectRoot = context.projectRoot().toAbsolutePath().normalize();
        List<Path> testRoots = new ArrayList<>();
        for (Path root : context.projectInfo().testSourceDirs()) {
            Path normalized = root.toAbsolutePath().normalize();
            if (!normalized.equals(projectRoot)) {
                testRoots.add(normalized);
            }
        }
        Map<String, Path> files = new LinkedHashMap<>();
        for (Path root : context.projectInfo().sourceDirs()) {
            for (Path file : PhpProjectDetector.phpFiles(root)) {
                Path normalized = file.toAbsolutePath().normalize();
                boolean isTest = testRoots.stream().anyMatch(normalized::startsWith);
                if (!isTest) {
                    files.putIfAbsent(normalized.toString(), file);
                }
            }
        }
        return List.copyOf(files.values());
    }

    private static boolean isGeneratedTest(Path file) {
        Path name = file.getFileName();
        return name != null && GENERATED_TEST.matcher(name.toString()).matches();
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

    /** @return the identifiers of every rule this analyzer implements, for documentation and tests. */
    public static List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        SECURITY_RULES.forEach(rule -> ids.add(rule.id()));
        CORRECTNESS_RULES.forEach(rule -> ids.add(rule.id()));
        RELIABILITY_RULES.forEach(rule -> ids.add(rule.id()));
        ids.add("CW-PHP-CORRECT-005");
        ids.add("CW-PHP-REL-002");
        ids.add("CW-PHP-TEST-001");
        return List.copyOf(ids);
    }

    /** @return the severity of a rule, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        for (List<Rule> table : List.of(SECURITY_RULES, CORRECTNESS_RULES, RELIABILITY_RULES)) {
            for (Rule rule : table) {
                if (rule.id().equals(ruleId)) {
                    return rule.severity();
                }
            }
        }
        return Severity.MEDIUM;
    }
}

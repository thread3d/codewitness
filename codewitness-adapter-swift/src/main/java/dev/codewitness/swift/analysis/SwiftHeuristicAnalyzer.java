package dev.codewitness.swift.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.swift.SwiftProjectDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic source heuristics for Swift.
 *
 * <p>These rules exist because a Swift program compiles and runs happily while being wrong in well known
 * ways: a {@code !} that force-unwraps an optional the compiler could not prove non-nil and crashes on the
 * one path nobody tested, a {@code try!} that turns a recoverable failure into a trap, an {@code as!} that
 * crashes instead of reporting a type mismatch, a {@code fatalError} in library code that hands the
 * application an unrecoverable stop, a credential committed in a literal, a secret written to
 * {@code UserDefaults}, a floating-point equality that is only accidentally true, a discarded {@code try?}
 * that converts an error into {@code nil}, an empty {@code catch} that swallows the failure, a
 * nondeterministic call in code that wanted a fixed answer, {@code print} where an error had to be handled,
 * an unfinished-work marker that cannot fail a build, a {@code main.swift} in a library package, and a test
 * method that asserts nothing. Each rule is a syntactic pattern with a documented false-positive profile; a
 * finding is reported at {@code INFO}/{@code LOW}/{@code MEDIUM}/{@code HIGH} severity and never on its own
 * makes a requirement {@code VERIFIED} - the analyzer's id contains {@code heuristic} precisely so the
 * engine can tell it apart from a real tool.
 *
 * <p>Every rule reads the source through {@link SwiftSourceScan}, so a rule cannot fire on text that only
 * looks like code: a commented-out line is invisible to all of them, text inside a string is invisible to
 * the rules that read statements, and the rules that are <em>about</em> a literal - the credential rule and
 * the {@code UserDefaults} rule - read the projection that keeps literal text. The unfinished-work rule
 * reads the comment projection, so a {@code TODO} inside a string is not reported as one. This is the
 * advantage of keeping the comment view rather than reading the raw file: there is no deliberate
 * read-the-raw-text exception anywhere in this table.
 *
 * <p>A rule reports at most one finding per file: a pattern that matches forty lines is one decision about
 * that file, and forty near-identical findings would bury every other rule. The first match in source order
 * is the one reported, so a file is reported at the line where the habit first appears.
 *
 * <p>No finding is produced without an observed match: a rule that matched nothing reports nothing at all,
 * rather than a note that it was checked and found nothing. This is not a replacement for SwiftLint or
 * Swift's own static analyzer; it is the analyzer that is always available, needs no installation, and
 * whose entire rule table fits in one auditable file.
 */
public final class SwiftHeuristicAnalyzer implements VerificationTools.StaticAnalyzer {

    /**
     * The analyzer's own id.
     *
     * <p>It contains {@code heuristic} deliberately: the core reads that word from a finding's analyzer id
     * and treats the finding as advisory, so a pattern-matching rule can never fail a requirement on its
     * own the way a real tool's finding can.
     */
    private static final String ANALYZER = "swift-heuristics";

    /** The prefix every security rule shares, so the security phase can select them from one table. */
    private static final String SECURITY_PREFIX = "CW-SWIFT-SEC";

    /** Which reading of the source a rule is written against. */
    private enum Projection {
        /** Only code: comments and literal contents replaced by spaces of the same length. */
        CODE,
        /** Everything except comments: needed by a rule whose pattern contains a literal. */
        LITERALS,
        /** Only comment text: needed by a rule that is about what a developer wrote in a comment. */
        COMMENTS
    }

    /**
     * A single syntactic rule: an id, how severe it is, and where in the source it looks.
     *
     * <p>{@code projection} and {@code pattern} are {@code null} for a rule whose condition is a fact about
     * the shape around a token rather than about one line - the {@code !} of a force unwrap, an empty
     * {@code catch} body, a test with no assertion - because no single regular expression states it. Such a
     * rule is applied by its own method, and {@link #ruleIds()} and {@link #severityOf(String)} still know
     * it.
     */
    private record Rule(
            String id,
            Severity severity,
            FindingCategory category,
            Projection projection,
            Pattern pattern,
            String message) {
    }

    /**
     * The words whose trailing {@code !} is a different rule entirely, so the force-unwrap scan does not
     * report them twice: {@code try!} is its own finding, and {@code as!} is a forced downcast.
     */
    private static final List<String> CAST_KEYWORDS = List.of("try", "as", "is");

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-SWIFT-SEC-002", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w.])try!(?!=)"),
                    "try! converts the failure of a throwing call into a trap: instead of the caller being "
                            + "able to recover, the process stops at the exact line where the error was "
                            + "already a known possibility. Use try and handle the error, or make the call "
                            + "non-throwing."),
            new Rule("CW-SWIFT-SEC-007", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?i)\\b(select|insert|update|delete|drop)\\b[^\\n]*(\\+|\\\\\\()"),
                    "SQL statement built by interpolation or concatenation: user input can change the query. "
                            + "Use a parameterised statement."),
            new Rule("CW-SWIFT-SEC-003", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w.])as!(?!=)"),
                    "as! forced downcast: when the value is not the expected type the process traps rather "
                            + "than reporting the mismatch. The crash is not a security boundary, but it is "
                            + "reachable whenever the input or the cast is wrong. Use conditional casting "
                            + "(as?) or a checked initialiser."),
            new Rule("CW-SWIFT-SEC-005", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?i)\\b(?:password|passwd|secret|api[_-]?key|auth[_-]?token"
                            + "|access[_-]?token|private[_-]?key|client[_-]?secret|credential)s?\\b\\s*"
                            + "(?::\\s*[^=\\n]+)?[:=]\\s*#*\"(?:\\\\.|[^\"\\\\\\n]){4,}\"#*"),
                    "Hardcoded credential: a secret literal is embedded in the source, where it is readable "
                            + "by anyone with the repository and by every fork of it, and it cannot be "
                            + "rotated without a code change. Use configuration or a secret store."),
            new Rule("CW-SWIFT-SEC-006", Severity.MEDIUM, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?i)UserDefaults\\b[^\\n]*?\\.\\s*(?:set|setValue)\\s*\\([^\\n]*"
                            + "(?:password|passwd|secret|token|api[_-]?key|credential)"),
                    "UserDefaults stores a secret-looking value: the defaults database is an unencrypted "
                            + "plist in the app container, readable from a backup or a jailbroken device. "
                            + "Keep credentials in the Keychain instead.")
    );

    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-SWIFT-CORRECT-001", Severity.MEDIUM, FindingCategory.CORRECTNESS, Projection.CODE,
                    Pattern.compile("\\d+\\.\\d+(?:[eE][-+]?\\d+)?\\s*[=!]=|[=!]=\\s*\\d+\\.\\d+"
                            + "(?:[eE][-+]?\\d+)?"),
                    "A floating-point literal compared with == or !=: binary floating point cannot represent "
                            + "most decimal fractions exactly, so the comparison is true only when the "
                            + "computation happens to produce the same representation. Compare within an "
                            + "explicit tolerance, or compare a value that is exactly representable.")
    );

    private static final List<Rule> RELIABILITY_RULES = List.of(
            new Rule("CW-SWIFT-REL-001", Severity.LOW, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("(?<![\\w.])Date\\s*\\(\\s*\\)|(?<![\\w.])arc4random\\w*"
                            + "|\\.\\s*randomElement\\s*\\(|\\.\\s*random\\s*\\(|\\.\\s*shuffled\\s*\\("),
                    "A nondeterministic source - the current time, a random number or a random element - "
                            + "makes this code's behaviour depend on when and how often it runs, which is "
                            + "what makes a failure impossible to reproduce. Inject the value, or take it "
                            + "from a seeded generator the test can control.")
    );

    private static final List<Rule> STYLE_RULES = List.of(
            new Rule("CW-SWIFT-STYLE-001", Severity.INFO, FindingCategory.STYLE, Projection.COMMENTS,
                    Pattern.compile("\\b(?:TODO|FIXME|HACK|XXX)\\b"),
                    "Unfinished work is marked in a comment: a TODO/FIXME/HACK records a decision that has "
                            + "not been made, and a comment cannot fail a build. Track it, or finish it.")
    );

    /**
     * Rules that only apply outside an executable entry point, added per file rather than per project.
     *
     * <p>{@code fatalError} and {@code preconditionFailure} are a legitimate way for a program to stop
     * when the process is the program; in a library they hand the caller's process an unrecoverable stop
     * for a condition the caller cannot see coming. {@code print} has the same shape: a command-line tool
     * exists to write to standard output, while a library that owns the console cannot be used by a program
     * that owns it too. Both are therefore skipped in {@code main.swift} and in a file carrying
     * {@code @main}, which is exactly the file whose job is to be the program.
     */
    private static final List<Rule> LIBRARY_RULES = List.of(
            new Rule("CW-SWIFT-SEC-004", Severity.MEDIUM, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w.])(?:fatalError|preconditionFailure)\\s*\\("),
                    "fatalError/preconditionFailure in library code stops the whole process at a point the "
                            + "library cannot know is fatal: a caller that could have recovered instead "
                            + "gets a crash with no opportunity to handle it. Throw an error, or return a "
                            + "result the caller can act on."),
            new Rule("CW-SWIFT-REL-002", Severity.LOW, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("(?<![\\w.])(?:Swift\\s*\\.\\s*)?print\\s*\\("),
                    "print( writes to standard output instead of handling the condition: in a library it "
                            + "takes the console from the program that owns it, and a debug line left "
                            + "behind is not a log record. Use a logger, or return the error.")
    );

    /**
     * A {@code !} that is not part of {@code !=}.
     *
     * <p>Whether it is a force unwrap depends on what precedes it, which is why this rule is applied by a
     * scan rather than by one line pattern: the token before the {@code !} has to be a value expression,
     * and an implicitly-unwrapped optional <em>type</em> ({@code UILabel!}, {@code [Item]!}) has the same
     * shape but is not a value.
     */
    private static final Pattern BANG = Pattern.compile("!(?!=)");

    /** {@code try?} as a complete statement, which is a {@code try?} whose result nobody reads. */
    private static final Pattern DISCARDED_TRY = Pattern.compile("(?m)^[ \\t]*try\\?[^\\n]*$");

    /**
     * A {@code catch} whose body is empty.
     *
     * <p>{@code [^{}]*} is the catch pattern binding ({@code catch let error as MyError}); it cannot cross
     * a brace, so the match cannot leap from one catch to a later empty closure. A body holding only a
     * comment matches as well, which is the right reading: a comment does not handle the failure.
     */
    private static final Pattern EMPTY_CATCH = Pattern.compile("\\bcatch\\b[^{}]*\\{\\s*\\}");

    /**
     * A Swift test function: the XCTest naming convention or a Swift Testing {@code @Test} attribute,
     * with any attributes and modifiers before it and any generic clause after the name.
     */
    private static final Pattern TEST_FUNCTION = Pattern.compile(
            "(?m)^[ \\t]*(?:@[\\w.]+(?:\\([^)]*\\))?[ \\t]+)*"
                    + "(?:(?:public|private|internal|fileprivate|open|static|final|override|mutating|nonisolated)"
                    + "[ \\t]+)*func[ \\t]+(\\w+)(?:[ \\t]*<[^>\\n]*>)?[ \\t]*\\(");

    /** Anything that can make a test fail; a test without one passes whatever the code does. */
    private static final Pattern ASSERTION = Pattern.compile(
            "\\bXCTAssert\\w*|\\bXCTFail\\b|\\bXCTUnwrap\\b|#expect\\s*\\(|#require\\s*\\(|\\bIssue\\s*\\.\\s*record");

    /**
     * A top-level statement in a file, used only to tell a {@code main.swift} that executes something from
     * one that holds nothing but imports and declarations. The match is anchored at the start of the line,
     * so an indented body is never mistaken for top-level code.
     */
    private static final Pattern TOP_LEVEL_STATEMENT = Pattern.compile(
            "^(?:[A-Za-z_]\\w*(?:\\s*\\.\\s*\\w+)*\\s*[(=]|(?:let|var)\\s+\\w+[^=\\n]*=|"
                    + "if\\s|for\\s|while\\s|repeat\\s*\\{|guard\\s|switch\\s|do\\s*\\{)");

    private static final Rule FORCE_UNWRAP_RULE = new Rule("CW-SWIFT-SEC-001", Severity.HIGH,
            FindingCategory.SECURITY, null, null,
            "Force unwrap of an optional: the value is taken out of its optional without a check, so the "
                    + "one path where it is nil traps instead of being handled. The compiler could not prove "
                    + "it non-nil, which is exactly the case the crash happens on. Unwrap it with if let/"
                    + "guard let, or give it a default.");

    private static final Rule DISCARDED_TRY_RULE = new Rule("CW-SWIFT-BUG-001", Severity.MEDIUM,
            FindingCategory.BUG, null, null,
            "A try? whose result nobody reads: the error is converted to nil and thrown away, so the caller "
                    + "cannot tell success from failure and nothing anywhere records that something went "
                    + "wrong. Handle the error, or make the call's failure impossible.");

    private static final Rule EMPTY_CATCH_RULE = new Rule("CW-SWIFT-BUG-002", Severity.MEDIUM,
            FindingCategory.BUG, null, null,
            "Empty catch block: the failure is silently discarded, so the caller receives a wrong value "
                    + "instead of an error and nothing anywhere records that something went wrong. Handle "
                    + "it, log it, or let it propagate.");

    private static final Rule LIBRARY_MAIN_RULE = new Rule("CW-SWIFT-MAIN-001", Severity.LOW,
            FindingCategory.MAINTAINABILITY, null, null,
            "A main.swift with executable top-level code in a package that declares no executable target: "
                    + "top-level statements exist only to be a program's entry point, and a library module "
                    + "that runs code on import cannot be embedded, tested in isolation, or linked more "
                    + "than once. Move the code behind a function the real executable calls.");

    private static final Rule UNASSERTED_TEST_RULE = new Rule("CW-SWIFT-TEST-001", Severity.MEDIUM,
            FindingCategory.MAINTAINABILITY, null, null,
            "Test method with no assertion, so it passes whatever the code does. A passing test that cannot "
                    + "fail is worse than no test: it looks like evidence.");

    @Override
    public String id() {
        return ANALYZER;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SWIFT);
    }

    /** @return every non-security finding: correctness, reliability and style, plus tests that cannot fail. */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return findAll(context).stream().filter(finding -> !isSecurityRule(finding.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        // Asked once for the run rather than once per file: whether the package declares an executable
        // target is a fact about the project, and a large tree would otherwise re-read its manifest for
        // every source file in it.
        boolean libraryPackage = libraryPackage(context);
        for (Path file : sourceFiles(context)) {
            analyseFile(context, file, libraryPackage, findings);
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
        return ruleId != null && ruleId.startsWith(SECURITY_PREFIX);
    }

    /**
     * Analyses one source file and appends what it found.
     *
     * <p>Three readings of the same source, all the same length as it and all keeping its newlines: rules
     * that look for statements read the code alone, rules that read a literal read everything except the
     * comments, and the unfinished-work rule reads only the comment text.
     */
    private static void analyseFile(
            VerificationContext context, Path file, boolean libraryPackage, List<Finding> findings) {
        SwiftSourceScan.Views views = SwiftSourceScan.scan(file);
        if (views.code().isEmpty()) {
            return;
        }
        String relative = relativePath(context, file);
        String[] codeLines = views.code().split("\\R", -1);
        String[] literalLines = views.literals().split("\\R", -1);
        String[] commentLines = views.comments().split("\\R", -1);
        boolean library = isLibraryCode(file, views.code());

        List<Rule> rules = new ArrayList<>();
        rules.addAll(SECURITY_RULES);
        rules.addAll(CORRECTNESS_RULES);
        rules.addAll(RELIABILITY_RULES);
        rules.addAll(STYLE_RULES);
        if (library) {
            rules.addAll(LIBRARY_RULES);
        }
        int lineCount = Math.min(codeLines.length, Math.min(literalLines.length, commentLines.length));
        for (Rule rule : rules) {
            String[] lines = switch (rule.projection()) {
                case CODE -> codeLines;
                case LITERALS -> literalLines;
                case COMMENTS -> commentLines;
            };
            for (int i = 0; i < lineCount; i++) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    findings.add(finding(rule, context, file, relative, i + 1, lines[i]));
                    break;
                }
            }
        }

        findForceUnwrap(views.code(), context, file, relative, findings);
        findDiscardedTry(views.code(), context, file, relative, findings);
        findEmptyCatch(views.code(), context, file, relative, findings);
        findLibraryMain(context, file, relative, views.code(), libraryPackage, findings);
    }

    /**
     * Analyses test sources for methods that cannot fail, which is a real verification hazard: a passing
     * test with no assertion looks like evidence and is not.
     *
     * <p>A test method's body is delimited by brace matching on the code-only view, so a nested closure or
     * a multi-line statement does not end it early. The assertion has to appear in the method itself; a
     * test that delegates its checks to a helper is therefore a documented false positive, and the
     * alternative - treating any call as evidence of a check - would report nothing at all.
     */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path root : context.projectInfo().testSourceDirs()) {
            for (Path file : SwiftProjectDetector.swiftFiles(root)) {
                String code = SwiftSourceScan.scan(file).code();
                if (code.isEmpty()) {
                    continue;
                }
                findUnassertedTests(code, context, file, relativePath(context, file), findings);
            }
        }
        return findings;
    }

    /**
     * Reports the first {@code !} that unwraps a value rather than annotating a type, and stops: one
     * finding per rule per file.
     *
     * <p>The token before the {@code !} decides. A lowercase identifier, a {@code )} or a {@code ]} can be
     * a value expression; an identifier that starts with an upper-case letter is treated as a type name
     * ({@code UILabel!}, {@code Int!}), which is how an implicitly unwrapped optional is declared and is
     * not a force unwrap. A {@code )} or {@code ]} whose opener follows a {@code :}, a {@code ,}, a
     * {@code (} or a {@code ->} closes a function or collection <em>type</em> in a declaration, so it is
     * skipped; an opener after {@code =} or after the value it belongs to is an expression. The cost of the
     * upper-case test is a false negative for a force-unwrapped value whose name happens to be capitalised,
     * which Swift's naming conventions make rare; the cost of dropping it would be a HIGH finding on every
     * {@code @IBOutlet} in the project.
     */
    private static void findForceUnwrap(
            String code, VerificationContext context, Path file, String relative, List<Finding> findings) {
        Matcher matcher = BANG.matcher(code);
        while (matcher.find()) {
            int at = matcher.start();
            if (at == 0) {
                continue;
            }
            char previous = code.charAt(at - 1);
            boolean unwraps;
            if (Character.isWhitespace(previous)) {
                // Postfix operators bind to their operand with no space; a spaced ! is the prefix not.
                continue;
            } else if (isWordChar(previous)) {
                String word = code.substring(wordStart(code, at), at);
                if (CAST_KEYWORDS.contains(word) || Character.isUpperCase(word.charAt(0))) {
                    continue;
                }
                unwraps = true;
            } else if (previous == ')') {
                unwraps = !isTypeContext(code, matchingOpen(code, at - 1, '(', ')'));
            } else if (previous == ']') {
                unwraps = !isTypeContext(code, matchingOpen(code, at - 1, '[', ']'));
            } else {
                continue;
            }
            if (unwraps) {
                findings.add(structural(FORCE_UNWRAP_RULE, context, file, relative, lineOf(code, at),
                        Map.of("snippet", snippet(code, lineStartOf(code, at)))));
                return;
            }
        }
    }

    /** Reports the first {@code try?} statement whose value is not consumed by the enclosing expression. */
    private static void findDiscardedTry(
            String code, VerificationContext context, Path file, String relative, List<Finding> findings) {
        Matcher matcher = DISCARDED_TRY.matcher(code);
        while (matcher.find()) {
            int lineStart = lineStartOf(code, matcher.start());
            if (isContinuation(previousCodeLine(code, lineStart))) {
                // `let value =\n    try? call()` starts a line with try? but the result is assigned.
                continue;
            }
            findings.add(structural(DISCARDED_TRY_RULE, context, file, relative,
                    lineOf(code, matcher.start()), Map.of("snippet", snippet(code, lineStart))));
            return;
        }
    }

    /** Reports the first {@code catch} whose body handles nothing. */
    private static void findEmptyCatch(
            String code, VerificationContext context, Path file, String relative, List<Finding> findings) {
        Matcher matcher = EMPTY_CATCH.matcher(code);
        if (matcher.find()) {
            int lineStart = lineStartOf(code, matcher.start());
            findings.add(structural(EMPTY_CATCH_RULE, context, file, relative, lineOf(code, matcher.start()),
                    Map.of("snippet", snippet(code, lineStart))));
        }
    }

    /**
     * Reports a {@code main.swift} that executes at top level in a package declaring no executable target.
     *
     * <p>Both halves are required. A package that declares an executable target is expected to have a
     * {@code main.swift}, and a {@code main.swift} holding only imports and declarations executes nothing.
     * When the manifest cannot be read the answer is "not a library package", so a package whose targets
     * are described programmatically is not reported on a guess about its layout.
     */
    private static void findLibraryMain(
            VerificationContext context, Path file, String relative, String code, boolean libraryPackage,
            List<Finding> findings) {
        if (!libraryPackage) {
            return;
        }
        Path name = file.getFileName();
        int line = topLevelStatementLine(code);
        if (name == null || !name.toString().equals("main.swift") || line < 0) {
            return;
        }
        findings.add(structural(LIBRARY_MAIN_RULE, context, file, relative, line,
                Map.of("snippet", lineText(code, line))));
    }

    /** Reports the first test method whose body contains no assertion. */
    private static void findUnassertedTests(
            String code, VerificationContext context, Path file, String relative, List<Finding> findings) {
        Matcher matcher = TEST_FUNCTION.matcher(code);
        while (matcher.find()) {
            String name = matcher.group(1);
            String declaration = code.substring(Math.max(0, matcher.start() - 200), matcher.end());
            if (!name.startsWith("test") && !declaration.contains("@Test")) {
                continue;
            }
            int openParen = matcher.end() - 1;
            int afterParameters = matchingParen(code, openParen);
            if (afterParameters < 0) {
                continue;
            }
            int brace = code.indexOf('{', afterParameters);
            if (brace < 0 || brace - afterParameters > 500) {
                // A declaration with no body (a protocol requirement) followed by nothing this rule can
                // attribute to the test.
                continue;
            }
            String signatureTail = code.substring(afterParameters, brace);
            if (signatureTail.indexOf('}') >= 0 || signatureTail.indexOf(';') >= 0) {
                continue;
            }
            String body = code.substring(brace + 1, matchingBrace(code, brace));
            if (!ASSERTION.matcher(body).find()) {
                findings.add(structural(UNASSERTED_TEST_RULE, context, file, relative,
                        lineOf(code, matcher.start()),
                        Map.of("test", name, "snippet", snippet(code, lineStartOf(code, matcher.start())))));
                return;
            }
        }
    }

    /**
     * @return {@code true} when the project's manifest declares no executable target
     *
     * <p>Read as text with the same two spellings SwiftPM has used: {@code .executableTarget} for a target
     * and {@code .executable(name:)} for a product. A manifest that constructs its target list
     * programmatically mentions neither and is treated as a library package, which can only add a LOW
     * advisory about a file the manifest itself did not describe.
     */
    private static boolean libraryPackage(VerificationContext context) {
        String manifest = read(context.projectRoot().resolve("Package.swift"));
        if (manifest.isEmpty()) {
            return false;
        }
        return !manifest.contains(".executableTarget") && !manifest.contains(".executable(");
    }

    /**
     * @return {@code true} when the file is library code rather than an executable's entry point
     *
     * <p>A {@code main.swift} exists to hold top-level code, and a type carrying {@code @main} is the
     * program's entry point; in both, stopping the process or printing to the console is the file's job.
     * Everywhere else it is a library's decision imposed on its caller.
     */
    private static boolean isLibraryCode(Path file, String code) {
        Path name = file.getFileName();
        if (name != null && name.toString().equals("main.swift")) {
            return false;
        }
        return !code.contains("@main");
    }

    /** @return the 1-based line of the first statement at the start of a line, or {@code -1} when none. */
    private static int topLevelStatementLine(String code) {
        String[] lines = code.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (TOP_LEVEL_STATEMENT.matcher(lines[i]).find()) {
                return i + 1;
            }
        }
        return -1;
    }

    /** @return the trimmed text of a 1-based line of {@code code}. */
    private static String lineText(String code, int line) {
        String[] lines = code.split("\\R", -1);
        return line - 1 < lines.length ? lines[line - 1].strip() : "";
    }

    /**
     * @return the offset of the open bracket that matches the close bracket at {@code close}, or
     *     {@code -1} when there is none
     */
    private static int matchingOpen(String code, int close, char open, char closeChar) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == closeChar) {
                depth++;
            } else if (c == open && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return {@code true} when the bracket opened at {@code open} is part of a type rather than a value
     *
     * <p>In a type position - a parameter, a property or a return type - the bracket follows {@code :},
     * {@code ,}, {@code (} or {@code ->}; in an expression it follows the value it belongs to (a word
     * character, a {@code )}, a {@code ]} or a string literal) or the {@code =} that begins the value. A
     * missing opener is not a type position, so the {@code !} is still reported.
     */
    private static boolean isTypeContext(String code, int open) {
        if (open <= 0) {
            return false;
        }
        int i = open - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        char before = code.charAt(i);
        return before == ':' || before == ',' || before == '(' || before == '>';
    }

    /**
     * @return the offset of the parenthesis that closes the one at {@code open}, just past it, or
     *     {@code -1} when it is never closed
     */
    private static int matchingParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** @return the offset of the brace that closes the one at {@code open}, or the end of the code. */
    private static int matchingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    /**
     * @param previous the trimmed previous line, or {@code null} at the start of the file
     * @return {@code true} when the next line continues the previous one rather than starting a statement
     */
    private static boolean isContinuation(String previous) {
        if (previous == null || previous.isEmpty()) {
            return false;
        }
        if (previous.equals("return") || previous.equals("throw") || previous.equals("yield")) {
            return true;
        }
        return "=([,.+-*/&|?%^<>:!".indexOf(previous.charAt(previous.length() - 1)) >= 0;
    }

    /** @return the trimmed text of the code line before {@code lineStart}, or {@code null} at the top. */
    private static String previousCodeLine(String code, int lineStart) {
        if (lineStart <= 0) {
            return null;
        }
        int end = lineStart - 1;
        while (end > 0 && (code.charAt(end - 1) == '\n' || code.charAt(end - 1) == '\r')) {
            end--;
        }
        int start = end;
        while (start > 0 && code.charAt(start - 1) != '\n') {
            start--;
        }
        return code.substring(start, end).strip();
    }

    /**
     * @return the project's own source files, with the test roots left out
     *
     * <p>Test sources are analysed separately, for tests that cannot fail. Counting a test fixture's
     * credential as a production finding would attribute the test's decision to the code it tests, and a
     * test root that <em>is</em> the project root - which is how the detector describes a tree with no
     * conventional layout - is deliberately kept, because excluding it would exclude the sources with it.
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
            for (Path file : SwiftSourceScan.sourceFiles(root)) {
                Path normalized = file.toAbsolutePath().normalize();
                boolean isTest = testRoots.stream().anyMatch(normalized::startsWith);
                if (!isTest) {
                    files.putIfAbsent(normalized.toString(), file);
                }
            }
        }
        return List.copyOf(files.values());
    }

    private static Finding finding(
            Rule rule, VerificationContext context, Path file, String relative, int line, String snippet) {
        return new Finding(ANALYZER, rule.id(), rule.severity(), rule.category(), rule.message(),
                relative, line, moduleName(context, file), Map.of("snippet", snippet.strip()));
    }

    /** @return a finding for a rule whose condition spans more than one line. */
    private static Finding structural(
            Rule rule, VerificationContext context, Path file, String relative, int line,
            Map<String, String> attributes) {
        return new Finding(ANALYZER, rule.id(), rule.severity(), rule.category(), rule.message(),
                relative, line, file == null ? null : moduleName(context, file), attributes);
    }

    /**
     * @return the SwiftPM module the file belongs to - the directory under {@code Sources} or
     *     {@code Tests} - or {@code null} when the tree has no conventional layout to read it from
     */
    private static String moduleName(VerificationContext context, Path file) {
        if (context == null || file == null) {
            return null;
        }
        Path root = context.projectRoot().toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return null;
        }
        Path relative = root.relativize(normalized);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            String element = relative.getName(i).toString();
            if (element.equals("Sources") || element.equals("Source") || element.equals("src")
                    || element.equals("Tests") || element.equals("Test") || element.equals("test")) {
                return relative.getName(i + 1).toString();
            }
        }
        return null;
    }

    private static String relativePath(VerificationContext context, Path file) {
        Path root = context.projectRoot();
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }

    /** @return the 1-based line number of {@code offset}. */
    private static int lineOf(String text, int offset) {
        return SwiftSourceScan.lineOf(text, offset);
    }

    /** @return the offset of the first character of the line holding {@code offset}. */
    private static int lineStartOf(String text, int offset) {
        int i = offset;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /** @return the trimmed text of the line that starts at {@code lineStart}. */
    private static String snippet(String text, int lineStart) {
        int i = lineStart;
        while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
            i++;
        }
        return text.substring(lineStart, i).strip();
    }

    /** @return the offset of the first character of the identifier that ends at {@code at}. */
    private static int wordStart(String text, int at) {
        int i = at;
        while (i > 0 && isWordChar(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }

    /** @return the identifiers of every rule this analyzer implements, for documentation and tests. */
    public static List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        all().forEach(rule -> ids.add(rule.id()));
        return List.copyOf(ids);
    }

    /** @return the severity of a rule, for tests and documentation. */
    public static Severity severityOf(String ruleId) {
        for (Rule rule : all()) {
            if (rule.id().equals(ruleId)) {
                return rule.severity();
            }
        }
        return Severity.MEDIUM;
    }

    /** @return every rule this analyzer implements, in the order {@link #ruleIds()} reports them. */
    private static List<Rule> all() {
        List<Rule> rules = new ArrayList<>(SECURITY_RULES);
        rules.addAll(CORRECTNESS_RULES);
        rules.addAll(RELIABILITY_RULES);
        rules.addAll(STYLE_RULES);
        rules.addAll(LIBRARY_RULES);
        rules.add(FORCE_UNWRAP_RULE);
        rules.add(DISCARDED_TRY_RULE);
        rules.add(EMPTY_CATCH_RULE);
        rules.add(LIBRARY_MAIN_RULE);
        rules.add(UNASSERTED_TEST_RULE);
        return List.copyOf(rules);
    }
}

package dev.codewitness.ruby.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.ruby.RubyProjectDetector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic source heuristics for Ruby.
 *
 * <p>These rules exist because a Ruby program runs happily while being wrong in well known ways: a
 * {@code rescue} that swallows the failure it caught, a bare {@code rescue} that catches everything
 * including {@code SignalException}, {@code eval} on a string built from input, a shell command built by
 * interpolation, an assignment inside an {@code if} where a comparison was meant, {@code == nil} where
 * {@code nil?} is the idiom, a {@code while true} with no way out, a {@code method_missing} without
 * {@code respond_to_missing?}, a {@code File.open} that leaks its handle, a hard-coded {@code sleep}, and
 * a method or a class nobody can hold in their head. Each rule is a syntactic pattern with a documented
 * false-positive profile; a finding is reported at {@code INFO}/{@code LOW}/{@code MEDIUM}/{@code HIGH}/
 * {@code CRITICAL} severity and never on its own makes a requirement {@code VERIFIED} - the analyzer's id
 * contains {@code heuristic} precisely so the engine can tell it apart from a real tool.
 *
 * <p>Every rule reads the source through {@link RubySourceScan}, so a rule cannot fire on text that only
 * looks like code: a commented-out line is invisible to all of them, text inside a string is invisible to
 * the rules that read statements, and the rules that are <em>about</em> a literal - the credential rule,
 * the interpolated-SQL rule - read the projection that keeps literal text. The one deliberate exception is
 * the {@code TODO}/{@code FIXME}/{@code HACK} rule, which reads the raw file: those markers live in
 * comments, and the projection blanks comments, so a rule written against the projection could never see
 * the thing it is about. It therefore also matches a marker inside a string literal, which is the price of
 * reading raw text and is why it is reported at {@code INFO}.
 *
 * <p>A rule reports at most one finding per file: a pattern that matches forty lines is one decision about
 * that file, and forty near-identical findings would bury every other rule. The first match in source
 * order is the one reported, so a file is reported at the line where the habit first appears.
 *
 * <p>No finding is produced without an observed match: a rule that matched nothing reports nothing at all,
 * rather than a note that it was checked and found nothing. This is not a replacement for RuboCop or
 * Sorbet; it is the analyzer that is always available, needs no installation, and whose entire rule table
 * fits in one auditable file.
 */
public final class RubyHeuristicAnalyzer implements VerificationTools.StaticAnalyzer {

    /**
     * The analyzer's own id.
     *
     * <p>It contains {@code heuristic} deliberately: the core reads that word from a finding's analyzer id
     * and treats the finding as advisory, so a pattern-matching rule can never fail a requirement on its
     * own the way a real tool's finding can.
     */
    private static final String ANALYZER = "ruby-heuristics";

    /** Which reading of the source a rule is written against. */
    private enum Projection {
        /** Only code: comments blanked and literal contents replaced by spaces of the same length. */
        CODE,
        /** Everything except comments: needed by a rule whose pattern contains a literal. */
        LITERALS
    }

    /**
     * A single syntactic rule: an id, how severe it is, and where in the source it looks.
     *
     * <p>{@code projection} and {@code pattern} are {@code null} for a rule whose condition is a fact about
     * a whole declaration rather than about one line - a method that runs past a length, a class that does
     * - because no single regular expression states it. Such a rule is applied by its own method, and
     * {@link #ruleIds()} and {@link #severityOf(String)} still know it.
     */
    private record Rule(
            String id,
            Severity severity,
            FindingCategory category,
            Projection projection,
            Pattern pattern,
            String message) {
    }

    /** A method that runs past this many lines is long enough that a reader cannot hold it at once. */
    private static final int METHOD_LINE_LIMIT = 40;

    /** A class or module that runs past this many lines is doing more than one job. */
    private static final int CLASS_LINE_LIMIT = 250;

    private static final List<Rule> SECURITY_RULES = List.of(
            new Rule("CW-RB-SEC-001", Severity.CRITICAL, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?i)\\b(?:password|passwd|secret|api[_-]?key|auth[_-]?token|"
                            + "access[_-]?token|private[_-]?key)\\b\\s*=\\s*[\"'][^\"']{4,}[\"']"),
                    "Hardcoded credential: a secret literal is embedded in the source, where it is readable "
                            + "by anyone with the repository and by every fork of it. Use configuration or a "
                            + "secret store."),
            new Rule("CW-RB-SEC-002", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?<![\\w.])(?:eval|instance_eval|class_eval|module_eval)\\s*\\(\\s*"
                            + "(?:[^\"'\\s)&]|[\"'][^\"'\\n]*#\\{)"),
                    "eval/instance_eval/class_eval on a computed value evaluates it as Ruby code: whatever "
                            + "the expression holds executes with the process's privileges. Pass a literal, "
                            + "or write the parser the input actually needs."),
            new Rule("CW-RB-SEC-003", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("(?<![\\w.])(?:Kernel\\s*\\.\\s*)?(?:system|exec|spawn)\\s*\\(\\s*"
                            + "(?:[^\"'\\s)&]|[\"'][^\"'\\n]*#\\{)"
                            + "|`[^`\\n]*#\\{|%x[^\\w\\s][^\\n]*#\\{"),
                    "Shell execution with a computed command: the shell receives whatever the interpolation "
                            + "or the variable holds, so a value from a request becomes a command. Use the "
                            + "argument-list form of the call and never pass the command through a shell."),
            new Rule("CW-RB-SEC-004", Severity.HIGH, FindingCategory.SECURITY, Projection.LITERALS,
                    Pattern.compile("\\b(?:execute|exec_query|where|find_by_sql|select_all|update_all|"
                            + "delete_all|order|group|having)\\s*\\([^\\n]{0,200}?"
                            + "(?:#\\{|\\w\\s*\\+\\s*[\"']|[\"']\\s*\\+\\s*\\w|\\.format\\s*\\("
                            + "|[\"'][^\"'\\n]*[\"']\\s*%[a-z])"),
                    "SQL statement built by interpolation, concatenation or formatting: user input can "
                            + "change the query, and quotes in it change the statement rather than the data. "
                            + "Use the parameterised form - where(\"id = ?\", id) - or an arel builder."),
            new Rule("CW-RB-SEC-005", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w.])Marshal\\s*\\.\\s*(?:load|restore)\\b"),
                    "Marshal.load reconstructs arbitrary objects from its input, and constructing an object "
                            + "runs its code. Data from a request, a cache or a file must not be unmarshalled: "
                            + "use a format that carries data only, or sign the payload."),
            new Rule("CW-RB-SEC-006", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("(?<![\\w.])(?:YAML|Psych)\\s*\\.\\s*load\\b"
                            + "(?![^\\n]*permitted_classes)"),
                    "YAML.load deserialises arbitrary YAML, and a document can name any class to construct; "
                            + "before Psych 4 that runs the class's code. Use YAML.safe_load with an explicit "
                            + "list of permitted classes."),
            new Rule("CW-RB-SEC-007", Severity.HIGH, FindingCategory.SECURITY, Projection.CODE,
                    Pattern.compile("\\.\\s*(?:send|public_send)\\s*\\(?\\s*(?![:\"'])[@$a-zA-Z_]"
                            + "|\\.\\s*constantize\\b"),
                    "Dynamic dispatch on a computed name: when the name comes from a request it reaches any "
                            + "method of the object, including the ones that read files, run commands or "
                            + "construct arbitrary classes. Use an allow-list of names."));

    /**
     * The XSS rule, kept out of the table because it applies only to a Rails-like project: {@code raw} and
     * {@code html_safe} are Rails' escaping switches, and a file that calls them in a project with no
     * Rails is either not view code or is not the framework this rule is written for.
     */
    private static final Rule RAILS_XSS = new Rule("CW-RB-SEC-008", Severity.MEDIUM, FindingCategory.SECURITY,
            Projection.LITERALS,
            Pattern.compile("\\braw\\s*\\(?\\s*(?:[^\"'\\s)]|[\"'][^\"'\\n]*#\\{)"
                    + "|#\\{[^\\n]*\\.html_safe\\b"),
            "Markup is marked safe for a value that is built by interpolation or passed straight in: "
                    + "html_safe/raw switch off Rails' escaping, so whatever the value holds is rendered as "
                    + "markup. Escape it, or build the markup from a template.");

    private static final List<Rule> CORRECTNESS_RULES = List.of(
            new Rule("CW-RB-CORRECT-001", Severity.LOW, FindingCategory.CORRECTNESS, Projection.CODE,
                    Pattern.compile("[=!]=\\s*nil\\b|\\bnil\\s*[=!]="),
                    "Comparison with nil using == or != calls the object's own ==, and a class that overrides "
                            + "it can answer either way. nil? asks the object itself and is the idiom."),
            new Rule("CW-RB-BUG-001", Severity.MEDIUM, FindingCategory.BUG, Projection.CODE,
                    Pattern.compile("\\w+\\s*=(?!=|~|>)[^\\n]*\\s(?:and|or)\\s"),
                    "A statement that assigns and then uses and/or: and and or bind looser than =, so "
                            + "'ok = save or raise' assigns the result of save and the raise is not part of "
                            + "the test. Use && and ||."));

    private static final List<Rule> RELIABILITY_RULES = List.of(
            new Rule("CW-RB-REL-001", Severity.LOW, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("(?<![\\w.])rescue\\s+Exception\\b"
                            + "|(?<![\\w.])rescue\\s*(?:=>\\s*\\w+)?\\s*$"),
                    "Rescue without a class, or of Exception itself, catches everything below StandardError: "
                            + "programming errors and the signals that should stop the process look like "
                            + "recoverable failures. Name the errors this code knows how to handle."),
            new Rule("CW-RB-REL-004", Severity.LOW, FindingCategory.RELIABILITY, Projection.CODE,
                    Pattern.compile("(?<![\\w$])\\$[A-Za-z_]\\w*\\s*"
                            + "(?:[-+*/%&^]|<{2}|>{2}|\\|\\||&&)?=(?!=|~|>)"),
                    "Assignment to a global variable: any code in the process can read and write it, so "
                            + "behaviour depends on what ran earlier. Ruby reads an unassigned global as nil, "
                            + "so the mistake is silent. Pass the value in, or use a constant."),
            new Rule("CW-RB-REL-006", Severity.MEDIUM, FindingCategory.BUG, Projection.CODE,
                    Pattern.compile("(?<![\\w.])(?:if|elsif|unless|while|until)\\b[^\\n]*?"
                            + "(?<![=!<>+\\-*/%&|^~])=(?!=|~|>)"),
                    "Assignment inside a condition: the condition is the assigned value, which is usually a "
                            + "typo for ==. Parenthesise the assignment if it is deliberate, because the "
                            + "next reader will read it as a comparison."),
            new Rule("CW-RB-REL-007", Severity.LOW, FindingCategory.PERFORMANCE, Projection.CODE,
                    Pattern.compile("(?<![\\w.])sleep\\s*\\(?\\s*\\d+(?:\\.\\d+)?\\b"),
                    "sleep with a hard-coded argument: a fixed wait is either too short to be reliable or "
                            + "long enough to make the suite slow, and neither is visible at the call site. "
                            + "Poll for the condition with a timeout, or name the delay."));

    /**
     * Writing to standard output from a library, kept out of the table because it is only a defect in
     * library code: a script or a rake task exists to print, and a test fixture may print on purpose.
     */
    private static final Rule CONSOLE_OUTPUT = new Rule("CW-RB-STYLE-001", Severity.LOW,
            FindingCategory.STYLE, Projection.CODE,
            Pattern.compile("(?<![\\w.])(?:puts|pp)\\b|(?<![\\w.])p(?=\\s*\\(|\\s+[^\\s=])"),
            "puts/p/pp writes to standard output: a library that owns the console cannot be used by a "
                    + "program that owns it too, and a debug line left behind is not a log record. Use "
                    + "Logger, or remove the line.");

    /** The rules whose applicability depends on the project or the file rather than on the source alone. */
    private static final List<Rule> CONDITIONAL_RULES = List.of(RAILS_XSS, CONSOLE_OUTPUT);

    /**
     * A {@code rescue} whose body is empty or {@code nil}: the failure is discarded without a trace.
     *
     * <p>The pattern spans lines because the body is what makes it a defect, and it accepts either the
     * one-line modifier form ({@code read rescue nil}) or a block whose only statement before {@code end}
     * is nothing or {@code nil}. A comment left where the body would be counts as empty, which is the
     * right reading: a comment does not handle the failure.
     */
    private static final Pattern SWALLOWED_RESCUE = Pattern.compile(
            "(?<![\\w.])rescue\\b[^\\n]*?(?:nil\\b|[\\n;]\\s*(?:nil\\b\\s*)?end\\b)");

    /** A loop with no condition to leave on: {@code while true} or {@code loop do}. */
    private static final Pattern UNBOUNDED_LOOP = Pattern.compile(
            "(?<![\\w.])while\\s+true\\b|(?<![\\w.])loop\\s+do\\b");

    /**
     * A way out of a loop.
     *
     * <p>{@code break} is the one the rule's name says; a {@code return}, {@code raise}, {@code throw} or
     * {@code exit} leaves the loop just as finally, and reporting a loop as unbounded because it leaves
     * through a {@code return} would be a false positive about a shape no reader would call a bug.
     */
    private static final Pattern LOOP_EXIT = Pattern.compile(
            "(?<![\\w.])break\\b|(?<![\\w.])return\\b|(?<![\\w.])raise\\b|(?<![\\w.])throw\\b|(?<![\\w.])exit\\b");

    /** A method declaration, whose body this class measures by the indentation of the lines under it. */
    private static final Pattern METHOD_DECLARATION = Pattern.compile("(?m)^[ \\t]*def\\b[^\\n]*");

    /** A class or module declaration, measured the same way. */
    private static final Pattern TYPE_DECLARATION = Pattern.compile("(?m)^[ \\t]*(?:class|module)\\b[^\\n]*");

    /** The definition that answers messages nothing else answers. */
    private static final Pattern METHOD_MISSING = Pattern.compile("(?<![\\w.])def\\s+method_missing\\b");

    /** Its required companion, without which {@code respond_to?} lies about what the object answers. */
    private static final Pattern RESPOND_TO_MISSING =
            Pattern.compile("(?<![\\w.])def\\s+respond_to_missing\\?");

    /** A file opened by name, whose handle the caller has to close. */
    private static final Pattern FILE_OPEN = Pattern.compile("(?<![\\w.])File\\s*\\.\\s*open\\b");

    /** A block opener: the brace form or {@code do}. */
    private static final Pattern BLOCK_OPENER = Pattern.compile("\\{|(?<![\\w.])do\\b");

    /**
     * A marker of unfinished work.
     *
     * <p>Read against the raw file, not a projection: the markers live in comments, and every projection
     * this adapter has blanks comments, so the rule cannot be written any other way.
     */
    private static final Pattern PENDING_TAG = Pattern.compile("\\b(?:TODO|FIXME|HACK|XXX)\\b");

    private static final Rule SWALLOWED_RESCUE_RULE = new Rule("CW-RB-REL-002", Severity.MEDIUM,
            FindingCategory.RELIABILITY, Projection.CODE, SWALLOWED_RESCUE,
            "Rescue with an empty body: the failure is discarded without a trace, so the caller receives a "
                    + "wrong value instead of an error and nothing anywhere records that something went "
                    + "wrong. Handle it, log it, or let it propagate.");

    private static final Rule UNBOUNDED_LOOP_RULE = new Rule("CW-RB-REL-003", Severity.MEDIUM,
            FindingCategory.RELIABILITY, Projection.CODE, UNBOUNDED_LOOP,
            "Loop with no way out: neither break nor return nor raise appears between the loop and the end "
                    + "of its block, so the method runs until the process is killed. Give the loop a "
                    + "condition, or an exit when the work is done.");

    private static final Rule FILE_OPEN_RULE = new Rule("CW-RB-REL-005", Severity.MEDIUM,
            FindingCategory.RELIABILITY, Projection.CODE, FILE_OPEN,
            "File.open without a block: the handle is closed only when the object is garbage collected, and "
                    + "until then the process holds an open descriptor - which a long-running service runs "
                    + "out of. Pass a block, so the file is closed even when the body raises.");

    private static final Rule METHOD_MISSING_RULE = new Rule("CW-RB-BUG-002", Severity.MEDIUM,
            FindingCategory.BUG, Projection.CODE, METHOD_MISSING,
            "method_missing is defined without respond_to_missing?: respond_to? then answers false for "
                    + "everything the method handles, so duck-typing checks and the libraries that ask "
                    + "before calling take the wrong branch.");

    private static final Rule LONG_METHOD_RULE = new Rule("CW-RB-MAIN-002", Severity.LOW,
            FindingCategory.MAINTAINABILITY, null, null,
            "Long method: the declaration and body run past the " + METHOD_LINE_LIMIT + " lines this "
                    + "adapter treats as the limit. A method that long usually holds more than one decision, "
                    + "and a reader cannot hold them all at once.");

    private static final Rule LONG_TYPE_RULE = new Rule("CW-RB-MAIN-003", Severity.LOW,
            FindingCategory.MAINTAINABILITY, null, null,
            "Long class or module: it runs past the " + CLASS_LINE_LIMIT + " lines this adapter treats as "
                    + "the limit, so it is doing more than one job and its parts cannot be tested apart.");

    private static final Rule PENDING_TAG_RULE = new Rule("CW-RB-MAIN-001", Severity.INFO,
            FindingCategory.MAINTAINABILITY, null, PENDING_TAG,
            "Unfinished work is marked in a comment: a TODO/FIXME/HACK records a decision that has not been "
                    + "made, and a comment cannot fail a build. Track it, or finish it.");

    private static final Rule TEST_RULE = new Rule("CW-RB-TEST-001", Severity.MEDIUM,
            FindingCategory.MAINTAINABILITY, null, null,
            "Example with no expectation, so it passes whatever the code does. A passing example that cannot "
                    + "fail is worse than no example: it looks like evidence.");

    /**
     * An RSpec example, or the minitest-spec equivalent.
     *
     * <p>Only the block form is recognised. {@code it_behaves_like} and the other shared-example helpers
     * start no example of their own, and a one-line {@code it { is_expected.to be_valid }} has its
     * expectation on the same line, which the body check below reads as part of the body.
     */
    private static final Pattern SPEC_EXAMPLE = Pattern.compile(
            "(?m)^[ \\t]*(?:it|specify|example)(?![\\w!?])[ \\t]*[( \\t]*[\"':{]");

    /** Anything that can make an example fail; an example without one passes whatever the code does. */
    private static final Pattern EXPECTATION = Pattern.compile(
            "\\bexpect\\s*[( ]|\\.should\\b|\\bmust_\\w+|\\bwont_\\w+|\\bassert\\w*|\\brefute\\w*"
                    + "|\\bis_expected\\b|\\braise_error\\b|\\bhave_\\w+|\\bthrow_symbol\\b");

    /**
     * The name shape of the tests this adapter's generator writes, so that its own scaffolding is not
     * analysed as a finding about the project.
     *
     * <p>The generator is not referenced here on purpose - the naming is part of the adapter's fixed
     * contract, while depending on the generator class would make the rule table refuse to compile without
     * the generation phase.
     */
    private static final Pattern GENERATED_SPEC = Pattern.compile("^cw_sc_.+_spec\\.rb$",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String id() {
        return ANALYZER;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUBY);
    }

    /** @return every non-security finding: correctness and reliability heuristics, plus tests that cannot fail. */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return findAll(context).stream().filter(finding -> !isSecurityRule(finding.ruleId())).toList();
    }

    /** @return every finding this analyzer can produce, security rules included. */
    public List<Finding> findAll(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        // Asked once for the run rather than once per file: the answer is a fact about the project, and a
        // large tree would otherwise re-read its Gemfile for every source file in it.
        boolean railsProject = railsLike(context);
        for (Path file : sourceFiles(context)) {
            analyseFile(context, file, railsProject, findings);
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
        return ruleId != null && ruleId.startsWith("CW-RB-SEC");
    }

    /**
     * Analyses test sources for examples that cannot fail, which is a real verification hazard: a passing
     * example with no expectation looks like evidence and is not.
     *
     * <p>An example's body is the indented block under its declaration line, which is how Ruby itself
     * delimits it: the example ends at the first following line indented no further than the {@code it}
     * that opened it. An example written with braces has its body on the declaration's own line, which is
     * read as such; a shared example included by name is not examined at all - a documented false negative
     * rather than a guess.
     */
    public List<Finding> analyzeTests(VerificationContext context) {
        List<Finding> findings = new ArrayList<>();
        for (Path root : context.projectInfo().testSourceDirs()) {
            for (Path file : RubyProjectDetector.rubyFiles(root)) {
                if (isGeneratedTest(file)) {
                    // A generated spec is scaffolding this run wrote for itself; reporting it would be a
                    // finding about this tool's own output rather than about the project.
                    continue;
                }
                String code = RubySourceScan.stripLiterals(RubyProjectDetector.readString(file));
                if (code.isEmpty()) {
                    continue;
                }
                String relative = relativePath(context, file);
                Matcher matcher = SPEC_EXAMPLE.matcher(code);
                while (matcher.find()) {
                    int headerStart = lineStartOf(code, matcher.start());
                    int headerEnd = lineEnd(code, headerStart);
                    // A block written with braces puts the expectation on the declaration's own line, so the
                    // body is that line; the do..end form puts it on the lines below.
                    String body = code.substring(headerStart, headerEnd).contains("{")
                            ? code.substring(headerStart, headerEnd)
                            : code.substring(headerEnd,
                                    blockEnd(code, headerStart, indentationOf(code, headerStart)));
                    if (!EXPECTATION.matcher(body).find()) {
                        findings.add(structural(TEST_RULE, relative, lineOf(code, matcher.start()),
                                Map.of("example", snippet(code, headerStart))));
                        break;
                    }
                }
            }
        }
        return findings;
    }

    /**
     * Analyses one source file and appends what it found.
     *
     * <p>Two readings of the same source, both the same length as it and both keeping its newlines: rules
     * that look for statements read the code alone, and rules that read a literal read everything except
     * the comments.
     */
    private static void analyseFile(
            VerificationContext context, Path file, boolean railsProject, List<Finding> findings) {
        String content = RubyProjectDetector.readString(file);
        if (content.isEmpty()) {
            return;
        }
        String relative = relativePath(context, file);
        RubySourceScan.Views views = RubySourceScan.scan(content);
        String[] codeLines = views.code().split("\\R", -1);
        String[] maskedLines = views.masked().split("\\R", -1);
        int lineCount = Math.min(codeLines.length, maskedLines.length);

        List<Rule> rules = new ArrayList<>(CORRECTNESS_RULES);
        rules.addAll(RELIABILITY_RULES);
        rules.addAll(SECURITY_RULES);
        if (railsProject) {
            rules.add(RAILS_XSS);
        }
        if (isLibrarySource(relative)) {
            rules.add(CONSOLE_OUTPUT);
        }
        for (Rule rule : rules) {
            String[] lines = rule.projection() == Projection.CODE ? maskedLines : codeLines;
            for (int i = 0; i < lineCount; i++) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    findings.add(finding(rule, relative, i + 1, lines[i]));
                    break;
                }
            }
        }

        findSwallowedRescue(views.masked(), relative, findings);
        findUnboundedLoops(views.masked(), relative, findings);
        findLongDeclarations(views.masked(), relative, findings);
        findMissingRespondTo(views.masked(), relative, findings);
        findLeakedFileHandle(views.masked(), relative, findings);
        findPendingTag(content, relative, findings);
    }

    /** Reports the first rescue whose body discards the failure, and stops: one finding per rule per file. */
    private static void findSwallowedRescue(String masked, String relative, List<Finding> findings) {
        Matcher matcher = SWALLOWED_RESCUE.matcher(masked);
        if (matcher.find()) {
            findings.add(structural(SWALLOWED_RESCUE_RULE, relative, lineOf(masked, matcher.start()),
                    Map.of("snippet", snippet(masked, lineStartOf(masked, matcher.start())))));
        }
    }

    /** Reports the first loop with no way out of its own block. */
    private static void findUnboundedLoops(String masked, String relative, List<Finding> findings) {
        Matcher matcher = UNBOUNDED_LOOP.matcher(masked);
        while (matcher.find()) {
            int headerStart = lineStartOf(masked, matcher.start());
            int end = blockEnd(masked, headerStart, indentationOf(masked, headerStart));
            if (!LOOP_EXIT.matcher(masked.substring(matcher.end(), end)).find()) {
                findings.add(structural(UNBOUNDED_LOOP_RULE, relative, lineOf(masked, matcher.start()),
                        Map.of("snippet", snippet(masked, headerStart))));
                return;
            }
        }
    }

    /** Reports the first method and the first class or module whose measured size passes the limit. */
    private static void findLongDeclarations(String masked, String relative, List<Finding> findings) {
        Matcher method = METHOD_DECLARATION.matcher(masked);
        while (method.find()) {
            int headerStart = lineStartOf(masked, method.start());
            int lines = countLines(masked, headerStart,
                    blockEnd(masked, headerStart, indentationOf(masked, headerStart)));
            if (lines > METHOD_LINE_LIMIT) {
                findings.add(structural(LONG_METHOD_RULE, relative, lineOf(masked, headerStart),
                        Map.of("lines", String.valueOf(lines), "snippet", snippet(masked, headerStart))));
                break;
            }
        }
        Matcher type = TYPE_DECLARATION.matcher(masked);
        while (type.find()) {
            int headerStart = lineStartOf(masked, type.start());
            int lines = countLines(masked, headerStart,
                    blockEnd(masked, headerStart, indentationOf(masked, headerStart)));
            if (lines > CLASS_LINE_LIMIT) {
                findings.add(structural(LONG_TYPE_RULE, relative, lineOf(masked, headerStart),
                        Map.of("lines", String.valueOf(lines), "snippet", snippet(masked, headerStart))));
                break;
            }
        }
    }

    /** Reports {@code method_missing} when the object cannot answer {@code respond_to?} truthfully. */
    private static void findMissingRespondTo(String masked, String relative, List<Finding> findings) {
        Matcher missing = METHOD_MISSING.matcher(masked);
        if (!missing.find()) {
            return;
        }
        if (RESPOND_TO_MISSING.matcher(masked).find()) {
            return;
        }
        findings.add(structural(METHOD_MISSING_RULE, relative, lineOf(masked, missing.start()),
                Map.of("snippet", snippet(masked, lineStartOf(masked, missing.start())))));
    }

    /**
     * Reports {@code File.open} whose result is not passed a block.
     *
     * <p>The block has to follow the argument list, so the call's own parentheses are matched by counting
     * rather than by a pattern: an argument may itself be a call, and a lazy match to the first {@code )}
     * would read the block as absent and report a correct program. A call written without parentheses has
     * its arguments and its block on the same line, so its block marker is looked for there.
     */
    private static void findLeakedFileHandle(String masked, String relative, List<Finding> findings) {
        Matcher matcher = FILE_OPEN.matcher(masked);
        while (matcher.find()) {
            int after = matcher.end();
            boolean block;
            if (after < masked.length() && masked.charAt(after) == '(') {
                block = blockFollows(masked, matchingParen(masked, after));
            } else {
                block = BLOCK_OPENER.matcher(masked.substring(after, lineEnd(masked, after))).find();
            }
            if (!block) {
                findings.add(structural(FILE_OPEN_RULE, relative, lineOf(masked, matcher.start()),
                        Map.of("snippet", snippet(masked, lineStartOf(masked, matcher.start())))));
                return;
            }
        }
    }

    /** @return {@code true} when a block opens at the first non-blank character from {@code after}. */
    private static boolean blockFollows(String text, int after) {
        int i = after;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i < text.length() && (text.charAt(i) == '{'
                || (text.startsWith("do", i)
                && (i + 2 >= text.length() || !isWordChar(text.charAt(i + 2)))));
    }

    /**
     * Reports the first marker of unfinished work, reading the raw file.
     *
     * <p>A tag inside a string literal therefore matches as well. That is the documented cost of reading
     * text the projection cannot see, and it is why this rule is reported at {@code INFO}.
     */
    private static void findPendingTag(String content, String relative, List<Finding> findings) {
        Matcher matcher = PENDING_TAG.matcher(content);
        if (matcher.find()) {
            findings.add(structural(PENDING_TAG_RULE, relative, lineOf(content, matcher.start()),
                    Map.of("tag", matcher.group(), "snippet",
                            snippet(content, lineStartOf(content, matcher.start())))));
        }
    }

    /**
     * @return {@code true} when the project is Rails-like, which is what makes {@code html_safe} and
     *     {@code raw} the escaping switches they are
     *
     * <p>The question is asked of the tree rather than of the language, because ERB views and
     * {@code html_safe} mean nothing outside the framework. A Gemfile that mentions Rails in a comment
     * counts, which can only turn the rule on for a project that calls Rails' methods anyway.
     */
    private static boolean railsLike(VerificationContext context) {
        Path root = context.projectRoot();
        if (Files.isDirectory(root.resolve("app/views")) || Files.isRegularFile(root.resolve("config/application.rb"))) {
            return true;
        }
        return RubyProjectDetector.readString(root.resolve("Gemfile")).contains("rails");
    }

    /** @return {@code true} when a file is library code rather than a script, a rake task or a config. */
    private static boolean isLibrarySource(String relative) {
        String normalized = relative == null ? "" : relative.replace('\\', '/');
        return normalized.startsWith("lib/") || normalized.contains("/lib/")
                || normalized.startsWith("app/") || normalized.contains("/app/");
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
            for (Path file : RubySourceScan.sourceFiles(root)) {
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
        if (name == null) {
            return false;
        }
        // The generator writes one spec per scenario into `<test root>/generated/`, so that directory is
        // the discriminator: it does not depend on how a scenario id happens to be spelled. The name is
        // checked as well, so a hand-written spec a developer happens to keep in that directory is still
        // analysed - the directory alone would skip it.
        Path parent = file.getParent();
        boolean inGeneratedDirectory = parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("generated");
        return inGeneratedDirectory && GENERATED_SPEC.matcher(name.toString()).matches();
    }

    private static Finding finding(Rule rule, String relative, int line, String snippet) {
        return new Finding(ANALYZER, rule.id(), rule.severity(), rule.category(), rule.message(),
                relative, line, null, Map.of("snippet", snippet.strip()));
    }

    /** @return a finding for a rule whose condition spans more than one line. */
    private static Finding structural(Rule rule, String relative, int line, Map<String, String> attributes) {
        return new Finding(ANALYZER, rule.id(), rule.severity(), rule.category(), rule.message(),
                relative, line, null, attributes);
    }

    /**
     * @param text        the source's masked view
     * @param headerStart the offset of the first character of a declaration or a loop header
     * @param indent      that line's indentation
     * @return the offset of the first following non-blank line indented no further, which is where the
     *     block ends, or the end of the file when no such line exists
     *
     * <p>Ruby has no braces to match, and counting {@code end} keywords would need a parser; the
     * indentation of the line that closes the block states the same thing in every normally formatted
     * file. A continuation line indented back to the header's column ends the measurement early, which
     * under-reports a long block rather than inventing one.
     */
    private static int blockEnd(String text, int headerStart, int indent) {
        int lineStart = lineEnd(text, headerStart);
        while (lineStart < text.length()) {
            lineStart++;
            int end = lineEnd(text, lineStart);
            if (end > lineStart && !text.substring(lineStart, end).isBlank()
                    && indentationOf(text, lineStart) <= indent) {
                return lineStart;
            }
            // Continue at this line's newline, so the increment above lands on the next line's first
            // character. Advancing by one character instead would re-test the same line from every
            // position, and a line indented deeper than the header would match as soon as the remaining
            // indentation fell to the header's own - truncating the block after two spaces.
            lineStart = end;
        }
        return text.length();
    }

    /**
     * @param text  the source's masked view
     * @param open  the offset of the {@code (} that opens an argument list
     * @return the offset just past the parenthesis that closes it, or the end of the file when it is never
     *     closed
     */
    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return text.length();
    }

    /** @return the number of lines the text spans from {@code from} up to, but not including, {@code to}. */
    private static int countLines(String text, int from, int to) {
        int lines = 1;
        for (int i = from; i < to && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** @return the 1-based line number of {@code offset}. */
    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** @return the offset of the first character of the line holding {@code offset}. */
    private static int lineStartOf(String text, int offset) {
        int i = offset;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /** @return the offset of the newline that ends the line holding {@code offset}, or the end of the text. */
    private static int lineEnd(String text, int offset) {
        int i = offset;
        while (i < text.length() && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** @return the number of leading spaces and tabs on the line that starts at {@code lineStart}. */
    private static int indentationOf(String text, int lineStart) {
        int i = lineStart;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i - lineStart;
    }

    /** @return the trimmed text of the line that starts at {@code lineStart}. */
    private static String snippet(String text, int lineStart) {
        return text.substring(lineStart, lineEnd(text, lineStart)).strip();
    }

    private static String relativePath(VerificationContext context, Path file) {
        Path root = context.projectRoot();
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** @return the identifiers of every rule this analyzer implements, for documentation and tests. */
    public static List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        SECURITY_RULES.forEach(rule -> ids.add(rule.id()));
        CORRECTNESS_RULES.forEach(rule -> ids.add(rule.id()));
        RELIABILITY_RULES.forEach(rule -> ids.add(rule.id()));
        CONDITIONAL_RULES.forEach(rule -> ids.add(rule.id()));
        ids.add(SWALLOWED_RESCUE_RULE.id());
        ids.add(UNBOUNDED_LOOP_RULE.id());
        ids.add(FILE_OPEN_RULE.id());
        ids.add(METHOD_MISSING_RULE.id());
        ids.add(LONG_METHOD_RULE.id());
        ids.add(LONG_TYPE_RULE.id());
        ids.add(PENDING_TAG_RULE.id());
        ids.add(TEST_RULE.id());
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
        rules.addAll(CONDITIONAL_RULES);
        rules.add(SWALLOWED_RESCUE_RULE);
        rules.add(UNBOUNDED_LOOP_RULE);
        rules.add(FILE_OPEN_RULE);
        rules.add(METHOD_MISSING_RULE);
        rules.add(LONG_METHOD_RULE);
        rules.add(LONG_TYPE_RULE);
        rules.add(PENDING_TAG_RULE);
        rules.add(TEST_RULE);
        return rules;
    }
}

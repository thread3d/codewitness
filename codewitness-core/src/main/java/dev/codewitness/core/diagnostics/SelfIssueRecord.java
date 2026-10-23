package dev.codewitness.core.diagnostics;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * One observed fault in CodeWitness, in the shape written to the self-issue journal.
 *
 * <p>The record is deliberately plain: strings, enums and no live exception object. The journal is a
 * file that outlives the process, and a stack trace is recorded as text so that it can still be read
 * after the JVM that produced it is gone.
 *
 * <p>{@link #id()} is derived from the category, the exception type, the application frame and the
 * message. Repeating the same failure therefore reuses one identifier instead of creating a new row
 * every run. The identifier is stable enough to quote in a fix request
 * ({@code fix CWI-1A2B3C4D}) and intentionally excludes line numbers: moving the failing line does
 * not turn an old issue into a new one.
 *
 * @param schemaVersion    journal schema version, so a future reader can migrate rather than guess
 * @param id               stable identifier, for example {@code CWI-1A2B3C4D}
 * @param recordedAt       ISO-8601 instant at which this occurrence was observed
 * @param severity         how much of the tool's work this fault affected
 * @param category         what kind of fault it is
 * @param message          one-line description written by the reporting call site
 * @param errorType        exception class name, or {@code null} when no exception was thrown
 * @param detail           full stack trace, or {@code null}
 * @param phase            pipeline phase when known, for example {@code build}
 * @param strategy         verification strategy when known
 * @param adapter          language adapter when known
 * @param requirement      requirement being handled when known
 * @param runId            verification run when known
 * @param command          command line that produced the occurrence
 * @param project          project root the run was working on
 * @param codewitnessVersion version of the tool that observed the fault
 * @param javaVersion      JVM version the tool was running on
 * @param thread           thread that observed the fault
 * @param source           first application frame that can be acted on, for example
 *                         {@code VerificationEngine.verify(VerificationEngine.java:218)}
 */
public record SelfIssueRecord(
        int schemaVersion,
        String id,
        String recordedAt,
        SelfIssueSeverity severity,
        SelfIssueCategory category,
        String message,
        String errorType,
        String detail,
        String phase,
        String strategy,
        String adapter,
        String requirement,
        String runId,
        String command,
        String project,
        String codewitnessVersion,
        String javaVersion,
        String thread,
        String source) {

    /** The only schema this build writes. A reader must not assume fields it has not seen. */
    public static final int SCHEMA_VERSION = 1;

    /** Context key for the pipeline phase. */
    public static final String PHASE = "phase";
    /** Context key for the verification strategy. */
    public static final String STRATEGY = "strategy";
    /** Context key for the language adapter. */
    public static final String ADAPTER = "adapter";
    /** Context key for the requirement being handled. */
    public static final String REQUIREMENT = "requirement";
    /** Context key for the verification run. */
    public static final String RUN_ID = "runId";
    /** Context key for the command line. */
    public static final String COMMAND = "command";
    /** Context key for the project root. */
    public static final String PROJECT = "project";
    /** Context key for the CodeWitness version. */
    public static final String VERSION = "codewitnessVersion";

    public SelfIssueRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(category, "category");
        message = message == null || message.isBlank() ? category.name() : message;
        errorType = nullIfBlank(errorType);
        detail = nullIfBlank(detail);
        phase = nullIfBlank(phase);
        strategy = nullIfBlank(strategy);
        adapter = nullIfBlank(adapter);
        requirement = nullIfBlank(requirement);
        runId = nullIfBlank(runId);
        command = nullIfBlank(command);
        project = nullIfBlank(project);
        codewitnessVersion = nullIfBlank(codewitnessVersion);
        javaVersion = nullIfBlank(javaVersion);
        thread = nullIfBlank(thread);
        source = nullIfBlank(source);
    }

    /**
     * Builds a record from a reporting call site.
     *
     * <p>This is the only supported constructor for callers that do not already have a journal row:
     * it derives the stable identifier, the source frame and the stack trace consistently, so two
     * call sites cannot accidentally disagree about what "the same issue" means.
     *
     * @param category category of fault
     * @param severity severity of fault
     * @param message  one-line description
     * @param error    exception observed, or {@code null} for a declared limitation
     * @param context  optional fields such as {@link #PHASE} and {@link #RUN_ID}
     * @return the record to append to a journal
     */
    public static SelfIssueRecord create(
            SelfIssueCategory category,
            SelfIssueSeverity severity,
            String message,
            Throwable error,
            Map<String, String> context) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Map<String, String> values = context == null ? Map.of() : context;

        String errorType = error == null ? null : error.getClass().getName();
        StackTraceElement frame = applicationFrame(error);
        String source = frame == null ? null : describe(frame);
        String sourceMethod = frame == null ? null : frame.getClassName() + "#" + frame.getMethodName();
        String detail = error == null ? null : stackTrace(error);
        String text = message == null || message.isBlank()
                ? (error == null ? category.name() : error.toString())
                : message;
        String phase = values.get(PHASE);
        String id = identifier(category, errorType, sourceMethod, text, phase);

        return new SelfIssueRecord(
                SCHEMA_VERSION,
                id,
                Instant.now().toString(),
                severity,
                category,
                text,
                errorType,
                detail,
                phase,
                values.get(STRATEGY),
                values.get(ADAPTER),
                values.get(REQUIREMENT),
                values.get(RUN_ID),
                values.get(COMMAND),
                values.get(PROJECT),
                values.get(VERSION),
                System.getProperty("java.version"),
                Thread.currentThread().getName(),
                source);
    }

    /**
     * @return a copy carrying the command line when it does not already have one
     *
     * <p>The engine knows what happened but not how the process was invoked; the CLI knows the command
     * but delegates the recording. Filling the gap here keeps the journal useful without making every
     * engine call site thread the command line through.
     */
    public SelfIssueRecord withCommand(String invocation) {
        if (invocation == null || invocation.isBlank() || command != null) {
            return this;
        }
        return new SelfIssueRecord(schemaVersion, id, recordedAt, severity, category, message, errorType,
                detail, phase, strategy, adapter, requirement, runId, invocation, project,
                codewitnessVersion, javaVersion, thread, source);
    }

    /**
     * @return a stable short identifier for a fault, derived from the facts that make it the same
     *     fault across runs
     *
     * <p>When the fault has an application frame, the message is deliberately left out of the hash: a
     * message often contains a run directory, a temporary path or a timing value that changes every
     * run, and hashing it would turn one defect into a new entry on every execution. The source frame
     * plus the exception type is the durable identity. A declared gap has no frame, so its message is
     * the only thing that distinguishes it and is included.
     */
    static String identifier(
            SelfIssueCategory category, String errorType, String sourceMethod, String message,
            String phase) {
        String stableMessage = sourceMethod == null ? nullToEmpty(message) : "";
        String fingerprint = String.join("|",
                category.name(),
                nullToEmpty(errorType),
                nullToEmpty(sourceMethod),
                nullToEmpty(phase),
                stableMessage);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory for every JVM; this branch exists only so the compiler is happy.
            return "CWI-" + Integer.toHexString(fingerprint.hashCode()).toUpperCase(java.util.Locale.ROOT);
        }
        return "CWI-" + HexFormat.of().withUpperCase().formatHex(digest, 0, 4);
    }

    /** @return the first stack frame in CodeWitness code, or {@code null}. */
    private static StackTraceElement applicationFrame(Throwable error) {
        if (error == null) {
            return null;
        }
        for (StackTraceElement frame : error.getStackTrace()) {
            String className = frame.getClassName();
            if (className.startsWith("dev.codewitness.")
                    && !className.startsWith("dev.codewitness.core.diagnostics.")) {
                return frame;
            }
        }
        return null;
    }

    private static String describe(StackTraceElement frame) {
        String file = frame.getFileName();
        String location = file == null ? "unknown source" : file + (frame.getLineNumber() >= 0
                ? ":" + frame.getLineNumber() : "");
        return frame.getClassName() + "." + frame.getMethodName() + "(" + location + ")";
    }

    private static String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            error.printStackTrace(printer);
        }
        return writer.toString();
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

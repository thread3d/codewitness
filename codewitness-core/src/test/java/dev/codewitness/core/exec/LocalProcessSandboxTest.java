package dev.codewitness.core.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local sandbox is a resource-safety boundary, not a security boundary. These tests pin the
 * safety properties it does promise: permitted working directories only, a sanitised environment, a
 * hard timeout, capped output and never throwing for a process that cannot start.
 */
class LocalProcessSandboxTest {

    private static LocalProcessSandbox sandbox(Path root) {
        return new LocalProcessSandbox(List.of(root), Map.of("PATH", "/usr/bin:/bin"), false);
    }

    private static ProcessSpec spec(Path workingDirectory, String... command) {
        return ProcessSpec.builder(List.of(command), workingDirectory)
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    @DisplayName("the sandbox reports its identity and isolation level")
    void identityAndIsolationLevel(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);

        assertEquals("local-process", sandbox.id());
        assertEquals(IsolationLevel.PROCESS, sandbox.isolationLevel());
        assertTrue(sandbox.description().contains("NOT a security boundary"), sandbox.description());
        assertTrue(sandbox.description().contains("network access is not blocked"), sandbox.description());
    }

    @Test
    @DisplayName("the run's own environment variables reach every child process")
    void runEnvironmentReachesChildProcesses(@TempDir Path root) {
        // A database fixture's URL is not a host credential, so it is merged over the allow-list rather than
        // filtered by it. This is what lets a project's tests in any language reach the same database: the
        // engine never has to thread the value into that language's tool.
        LocalProcessSandbox sandbox = new LocalProcessSandbox(List.of(root),
                Map.of("PATH", "/usr/bin:/bin"), false,
                Map.of("CODEWITNESS_DATABASE_URL", "jdbc:sqlite:/tmp/ledger.db"));

        assertEquals("jdbc:sqlite:/tmp/ledger.db", sandbox.baseEnvironment().get("CODEWITNESS_DATABASE_URL"));

        ProcessResult result = sandbox.runner()
                .run(spec(root, "/bin/sh", "-c", "printf %s \"$CODEWITNESS_DATABASE_URL\""));

        assertTrue(result.success(), result.stderr());
        assertEquals("jdbc:sqlite:/tmp/ledger.db", result.stdout());
        assertFalse(sandbox.baseEnvironment().containsKey("SECRET_TOKEN"),
                "the run's own variables do not widen the host allow-list: " + sandbox.baseEnvironment());
    }

    @Test
    @DisplayName("permits() accepts paths under its roots and rejects everything else")
    void permitsOnlyRoots(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);

        assertTrue(sandbox.permits(root));
        assertTrue(sandbox.permits(root.resolve("nested/dir")));
        assertFalse(sandbox.permits(root.getParent().resolve("somewhere-else")));
    }

    @Test
    @DisplayName("a working directory outside the sandbox roots is refused, not executed")
    void refusesWorkingDirectoryOutsideRoots(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root.resolve("allowed"));
        Path outside = root.resolve("outside");

        ProcessResult result = sandbox.runner().run(spec(outside, "/bin/echo", "hi"));

        assertFalse(result.started());
        assertFalse(result.success());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("outside the sandbox roots"), result.failureReason());
    }

    @Test
    @DisplayName("a missing working directory is refused with a reason")
    void refusesMissingWorkingDirectory(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);

        ProcessResult result = sandbox.runner().run(spec(root.resolve("nope"), "/bin/echo", "hi"));

        assertFalse(result.started());
        assertTrue(result.failureReason().contains("does not exist"), result.failureReason());
    }

    @Test
    @DisplayName("a command that cannot be started yields started() == false, never an exception")
    void commandThatCannotStartIsReported(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);

        ProcessResult result = sandbox.runner().run(
                spec(root, "/definitely/not/a/real/binary-codewitness-xyz"));

        assertFalse(result.started());
        assertFalse(result.success());
        assertNotNull(result.failureReason(), "a reason must be recorded");
        assertTrue(result.failureReason().contains("Exception"), result.failureReason());
        assertEquals(-1, result.exitCode());
        assertFalse(result.timedOut());
    }

    @Test
    @DisplayName("a command slower than its timeout is killed and reported as timed out")
    void slowCommandIsKilledAndReportedAsTimedOut(@TempDir Path root) throws InterruptedException {
        LocalProcessSandbox sandbox = sandbox(root);
        // /bin/cat blocks on stdin without spawning a grandchild, so the kill is immediate and the test
        // stays fast and deterministic.
        ProcessSpec slow = ProcessSpec.builder(List.of("/bin/cat"), root)
                .timeout(Duration.ofMillis(250))
                .build();

        long before = System.nanoTime();
        ProcessResult result = sandbox.runner().run(slow);
        long millis = (System.nanoTime() - before) / 1_000_000;

        assertTrue(result.started());
        assertTrue(result.timedOut());
        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
        assertTrue(result.describeOutcome().contains("timed out"), result.describeOutcome());
        assertTrue(millis < 10_000, "the sandbox must not wait for the full sleep, took " + millis + "ms");
    }

    @Test
    @DisplayName("a successful command reports exit code and output")
    void successfulCommandReportsExitCodeAndOutput(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);

        ProcessResult result = sandbox.runner().run(spec(root, "/bin/sh", "-c", "echo hello; echo oops >&2"));

        assertTrue(result.started());
        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello"), result.stdout());
        assertTrue(result.stderr().contains("oops"), result.stderr());
        assertTrue(result.combinedOutput().contains("hello"));
        assertTrue(result.displayCommand().contains("/bin/sh"));
        assertNull(result.failureReason());
    }

    @Test
    @DisplayName("a non-zero exit code is reported without throwing")
    void nonZeroExitCodeIsReported(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);

        ProcessResult result = sandbox.runner().run(spec(root, "/bin/sh", "-c", "exit 3"));

        assertTrue(result.started());
        assertFalse(result.success());
        assertEquals(3, result.exitCode());
        assertTrue(result.describeOutcome().contains("exit code 3"), result.describeOutcome());
    }

    @Test
    @DisplayName("AWS_SECRET_ACCESS_KEY must not reach the child process")
    void environmentIsSanitised(@TempDir Path root) {
        // Design decision: the project under verification is treated as hostile, so only an explicit
        // allow-list of environment variables is forwarded. Credentials must never leak into a build.
        LocalProcessSandbox sandbox = new LocalProcessSandbox(List.of(root),
                Map.of("PATH", "/usr/bin:/bin", "AWS_SECRET_ACCESS_KEY", "super-secret-value"), false);
        assertFalse(sandbox.baseEnvironment().containsKey("AWS_SECRET_ACCESS_KEY"));

        ProcessResult result = sandbox.runner().run(
                spec(root, "/bin/sh", "-c", "echo [$AWS_SECRET_ACCESS_KEY]"));

        assertTrue(result.started());
        assertFalse(result.stdout().contains("super-secret-value"), result.stdout());
        assertEquals("[]", result.stdout().trim());
    }

    @Test
    @DisplayName("the baseline environment carries the sandbox marker and drops MAVEN_OPTS")
    void baselineEnvironmentIsMinimal(@TempDir Path root) {
        LocalProcessSandbox sandbox = new LocalProcessSandbox(List.of(root),
                Map.of("PATH", "/usr/bin:/bin", "MAVEN_OPTS", "-Xmx1g"), false);

        Map<String, String> environment = sandbox.baseEnvironment();
        assertEquals("local-process", environment.get("CODEWITNESS_SANDBOX"));
        assertFalse(environment.containsKey("MAVEN_OPTS"));
        assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
    }

    @Test
    @DisplayName("a spec-level environment variable is merged over the baseline")
    void specEnvironmentIsMerged(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);
        ProcessSpec withEnv = ProcessSpec.builder(List.of("/bin/sh", "-c", "echo $CODEWITNESS_TEST_VAR"), root)
                .env("CODEWITNESS_TEST_VAR", "expected")
                .timeout(Duration.ofSeconds(5))
                .build();

        ProcessResult result = sandbox.runner().run(withEnv);

        assertEquals("expected", result.stdout().trim());
    }

    @Test
    @DisplayName("output larger than the cap is truncated but the process still completes")
    void oversizedOutputIsTruncatedButProcessCompletes(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);
        ProcessSpec chatty = ProcessSpec.builder(List.of("/bin/sh", "-c",
                        "i=0; while [ $i -lt 2000 ]; do printf 'abcdefghij'; i=$((i+1)); done"), root)
                .timeout(Duration.ofSeconds(10))
                .maxOutputBytes(100)
                .build();

        ProcessResult result = sandbox.runner().run(chatty);

        assertTrue(result.started());
        assertFalse(result.timedOut(), "draining truncated output must not deadlock the process");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("output truncated at 100 bytes"), result.stdout());
        assertTrue(result.stdout().length() < 500, "stdout must be capped, was " + result.stdout().length());
    }

    @Test
    @DisplayName("ProcessRunner.denying never executes and explains itself")
    void denyingRunnerExplainsItself(@TempDir Path root) {
        ProcessRunner runner = ProcessRunner.denying("execution disabled for this test");

        ProcessResult result = runner.run(spec(root, "/bin/echo", "hi"));

        assertFalse(result.started());
        assertEquals("execution disabled for this test", result.failureReason());
    }

    @Test
    @DisplayName("an empty command is rejected by ProcessSpec before execution")
    void emptyCommandIsRejected() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ProcessSpec.builder(List.of(), Path.of(".")).build()).getMessage().contains("must not be empty"));
    }

    @Test
    @DisplayName("ProcessResult.tail returns the last lines of the combined output")
    void processResultTail(@TempDir Path root) {
        LocalProcessSandbox sandbox = sandbox(root);

        ProcessResult result = sandbox.runner().run(spec(root, "/bin/sh", "-c", "printf 'a\\nb\\nc\\nd\\n'"));

        assertEquals("c\nd", result.tail(2));
        assertEquals("a\nb\nc\nd", result.tail(10));
    }
}

package dev.codewitness.java;

import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.java.build.MavenBuildSystem;
import dev.codewitness.java.coverage.JacocoCoverageProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build tool killed at its timeout is not a finished observation.
 *
 * <p>Maven writes surefire and JaCoCo reports for the work it completed before the kill, so a killed
 * run leaves behind exactly the artefacts the providers used to read as a complete run. These tests
 * make the tool write those artefacts and then report the timeout, and require that neither is used.
 */
class JavaTimeoutTest {

    @TempDir
    Path tmp;

    private static final String SUREFIRE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="1" time="0.01">
              <testcase classname="com.example.FooTest" name="passes" time="0.01"/>
            </testsuite>
            """;

    private static final String JACOCO_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <report name="sample">
              <package name="com/example">
                <class name="com/example/Foo" sourcefilename="Foo.java">
                  <counter type="LINE" missed="1" covered="3"/>
                </class>
                <counter type="LINE" missed="1" covered="3"/>
              </package>
              <counter type="LINE" missed="1" covered="3"/>
            </report>
            """;

    @Test
    @DisplayName("a killed build is not executed, even though a surefire report was written")
    void killedBuildIsNotParsedAsASuite() throws IOException {
        Path root = projectDirectory();
        Path report = root.resolve("target/surefire-reports/TEST-cw.xml");
        VerificationContext context = context(root, sandbox(() -> write(report, SUREFIRE_XML)));

        TestResult result = new JavaTestProvider(new BuildToolLauncher(context.runner()), new MavenBuildSystem())
                .runTests(context);

        assertFalse(result.executed(), "a killed run must not read as a finished suite: " + result.describe());
        assertTrue(Files.isRegularFile(report), "the partial report existed and had to be ignored");
        assertTrue(result.describe().contains("timeout"), result.describe());
        assertTrue(result.note().contains("was killed"), result.note());
    }

    @Test
    @DisplayName("a killed JaCoCo run is not measured, even though a partial XML report was written")
    void killedJacocoRunIsNotMeasured() throws IOException {
        Path root = projectDirectory();
        Path report = root.resolve("target/site/jacoco/jacoco.xml");
        VerificationContext context = context(root, sandbox(() -> write(report, JACOCO_XML)));

        CoverageReport coverage =
                new JacocoCoverageProvider(new BuildToolLauncher(context.runner())).measure(context);

        assertFalse(coverage.measured(), "a killed run must not be measured: " + coverage.describe());
        assertTrue(Files.isRegularFile(report), "the partial report existed and had to be ignored");
        assertTrue(coverage.note().contains("timeout"), coverage.note());
        assertTrue(coverage.note().contains("was killed"), coverage.note());
    }

    private Path projectDirectory() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Files.createDirectories(root.resolve("src/test/java"));
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n", StandardCharsets.UTF_8);
        return root;
    }

    private VerificationContext context(Path root, ExecutionSandbox sandbox) {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .addModule(new ModuleInfo("sample", root, "jar",
                        List.of(root.resolve("src/main/java")), List.of(root.resolve("src/test/java")), "25"))
                .build();
        return TestContexts.context(project, tmp.resolve("run"), TestContexts.config(List.of()), sandbox);
    }

    /** A sandbox whose runner performs {@code onRun} and reports a process killed by its timeout. */
    private static ExecutionSandbox sandbox(Runnable onRun) {
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "timeout-double";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: runs nothing, reports a timeout";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    onRun.run();
                    return timedOut(spec);
                };
            }
        };
    }

    private static ProcessResult timedOut(ProcessSpec spec) {
        return new ProcessResult(spec.command(), spec.workingDirectory(), true, -1,
                "Tests run: 1, Failures: 0", "partial output before the kill",
                Duration.ofSeconds(5), true, null);
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

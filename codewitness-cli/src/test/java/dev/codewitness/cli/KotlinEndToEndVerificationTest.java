package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of a deliberately defective Kotlin project.
 *
 * <p>This is the Kotlin counterpart of {@link CSharpEndToEndVerificationTest}: it proves that the evidence
 * chain works when the generated test is Kotlin rather than Java. That chain is the part unit tests cannot
 * demonstrate - a boundary case is generated as a Kotlin class, the Kotlin compiler the project already uses
 * compiles it inside the project's own build, the JUnit XML report carries the failure back, and the
 * counterexample protocol line inside that failure becomes the report's counterexample. A defect behind a
 * requirement is found, a requirement with no executable verification stays {@code UNVERIFIED}, and a
 * requirement that is genuinely discharged is reported {@code VERIFIED}.
 *
 * <p>It needs a Maven executable; the fixture is a Maven build with the Kotlin plugin, so the compiler and
 * the standard library arrive from the repository the way any other dependency does. The first run on a
 * machine that has never built Kotlin therefore downloads them, exactly as the C# end-to-end test relies on
 * the runner having restored a test host before.
 */
@Tag("e2e")
class KotlinEndToEndVerificationTest extends EndToEndSupport {

    @Test
    @DisplayName("a Kotlin defect behind a requirement is found and attributed, and an executable requirement passes")
    void verifiesKotlinProject(@TempDir Path output) throws IOException {
        assumeBuildToolAvailable();
        Path fixture = requireFixture("kotlin-withdrawal-ledger");

        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "20m",
                "--no-mutation",
                "--quiet");

        JsonNode report = readReport(output);
        assertEquals("FAILED", report.path("status").asText(),
                "the fixture contains deliberately introduced defects, so the run must not pass");

        JsonNode rolling = requirement(report, "R-001");
        assertEquals("FAILED", rolling.path("status").asText(),
                "R-001 requires a rolling 24 hour window; the fixture applies the limit per calendar day");
        assertTrue(rolling.path("rationale").asText().contains("counterexample"),
                "the verdict must say why it failed: " + rolling.path("rationale").asText());

        JsonNode counterexample = counterexampleFor(report, "R-001");
        assertNotNull(counterexample, "a counterexample must be recorded for R-001");
        JsonNode inputs = counterexample.path("inputs");
        assertTrue(inputs.has("t1") && inputs.has("t2"),
                "the counterexample must carry the exact instants that reproduce the violation: " + inputs);
        assertTrue(new java.math.BigDecimal(counterexample.path("actual").asText())
                        .compareTo(new java.math.BigDecimal("1000")) > 0,
                "the total observed across midnight must exceed the 1,000 limit: "
                        + counterexample.path("actual").asText());
        assertTrue(counterexample.path("reproduction").path("command").toString().contains("mvn"),
                "the replay command must be a real Maven invocation of the generated Kotlin test: "
                        + counterexample.path("reproduction").path("command"));
        assertTrue(counterexample.path("reproduction").path("command").toString().contains("Cw_"),
                "the replay must name the generated class the reader can open: "
                        + counterexample.path("reproduction").path("command"));

        JsonNode negative = requirement(report, "R-003");
        assertEquals("FAILED", negative.path("status").asText(),
                "R-003 requires negative amounts to be rejected; the fixture accepts them");

        assertEquals("VERIFIED", requirement(report, "R-002").path("status").asText(),
                "R-002 is discharged by an executed generated Kotlin test, which is the only path to VERIFIED");

        assertEquals("UNVERIFIED", requirement(report, "R-004").path("status").asText(),
                "R-004 declares no scenario, so CodeWitness has no way to execute it and must say so");

        assertEquals(1, exitCode, "a found counterexample must produce exit code 1 (a requirement failed)");
    }

    @Test
    @DisplayName("a Kotlin project that does not compile is BLOCKED, never reported as incorrect code")
    void compileErrorIsBlockedNotFailed(@TempDir Path project, @TempDir Path output) throws IOException {
        assumeBuildToolAvailable();
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>broken</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <kotlin.version>2.2.20</kotlin.version>
                    <kotlin.compiler.execution.strategy>in-process</kotlin.compiler.execution.strategy>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.jetbrains.kotlin</groupId>
                      <artifactId>kotlin-stdlib</artifactId>
                      <version>${kotlin.version}</version>
                    </dependency>
                  </dependencies>
                  <build>
                    <sourceDirectory>src/main/kotlin</sourceDirectory>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>${kotlin.version}</version>
                        <executions>
                          <execution>
                            <id>compile</id>
                            <phase>compile</phase>
                            <goals><goal>compile</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """, StandardCharsets.UTF_8);
        // A return type that does not match, so that compilation - not the code's behaviour - is what fails.
        Files.createDirectories(project.resolve("src/main/kotlin"));
        Files.writeString(project.resolve("src/main/kotlin/Broken.kt"), """
                package broken

                class Value {
                    fun of(): Long = "not a number"
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("requirements.md"), """
                # R-001 The value must be an integer

                The value returned must be representable as an integer.
                """, StandardCharsets.UTF_8);

        int exitCode = Main.execute("verify", project.toString(),
                "--requirements", project.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "5m", "--no-mutation", "--quiet");

        JsonNode report = readReport(output);
        assertEquals("BLOCKED", requirement(report, "R-001").path("status").asText(),
                "a compile error must be reported as BLOCKED, not as a code defect");
        assertEquals(2, exitCode, "a blocked verification must produce exit code 2");
        assertTrue(report.path("limitations").toString().contains("could not be built"),
                "the report must explain that the build, not the code, is the problem");
    }

    /** Reads the JSON report written by the most recent run. */
    private JsonNode readReport(Path output) throws IOException {
        List<Path> runs = runDirectories(output);
        assertFalse(runs.isEmpty(), "a run directory must have been created");
        Path report = runs.get(0).resolve("report.json");
        assertTrue(Files.isRegularFile(report), "the canonical JSON report must exist at " + report);
        return new ObjectMapper().readTree(Files.readString(report, StandardCharsets.UTF_8));
    }
}

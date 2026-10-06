package dev.codewitness.java.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the reactor POM's surefire {@code argLine} against a regression that silently disables
 * coverage.
 *
 * <p>JaCoCo's {@code prepare-agent} goal attaches its agent by setting the Maven property
 * {@code argLine} at runtime, and surefire only picks that up through late replacement
 * ({@code @{argLine}}). Writing the native-access flag as a literal {@code <argLine>} instead
 * overrides the property, no {@code jacoco.exec} is produced, and CodeWitness's coverage phase
 * reports {@code coverage not measured}. That is exactly what happened to this project's own
 * self-verification run, so the configuration is asserted here rather than left to a full coverage
 * run to notice.
 */
class SurefireArgLineConfigurationTest {

    @Test
    @DisplayName("surefire argLine is built from @{argLine} so JaCoCo's agent is not overridden")
    void surefireArgLineDefersToJacocoProperty() throws IOException {
        Path pom = reactorPom();
        String xml = Files.readString(pom, StandardCharsets.UTF_8);

        assertTrue(xml.contains("@{argLine}"),
                "the surefire argLine in " + pom + " must include @{argLine}; otherwise "
                        + "jacoco:prepare-agent cannot attach its agent and coverage stays unmeasurable");
        assertFalse(xml.matches("(?s).*<argLine>\\s*--enable-native-access[^<]*</argLine>.*"),
                "a literal <argLine> that is not built from @{argLine} would drop the JaCoCo agent");
    }

    @Test
    @DisplayName("an empty argLine property is declared so a plain mvn test still works")
    void emptyArgLinePropertyIsDeclared() throws IOException {
        String xml = Files.readString(reactorPom(), StandardCharsets.UTF_8);
        assertTrue(xml.matches("(?s).*<argLine>\\s*</argLine>.*"),
                "an empty <argLine> default property keeps a plain mvn test working while still "
                        + "letting jacoco:prepare-agent replace it");
    }

    /** Finds the reactor POM by walking up from the module directory, so the test is cwd independent. */
    private static Path reactorPom() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("pom.xml");
            if (Files.isRegularFile(candidate) && isReactorRoot(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not locate the reactor POM above " + Path.of("").toAbsolutePath());
    }

    /** The reactor root is the only POM that aggregates the modules, which distinguishes it from a
     *  module POM that merely names {@code codewitness-parent} as its parent. */
    private static boolean isReactorRoot(Path pom) {
        try {
            return Files.readString(pom, StandardCharsets.UTF_8).contains("<module>codewitness-core</module>");
        } catch (IOException e) {
            return false;
        }
    }
}

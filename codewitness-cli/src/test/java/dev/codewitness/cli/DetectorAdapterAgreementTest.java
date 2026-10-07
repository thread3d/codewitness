package dev.codewitness.cli;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.LanguageDetector;
import dev.codewitness.core.spi.LanguageDetectorRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the agreement between the detectors and the adapters, which only this module can see.
 *
 * <p>{@code codewitness-language-detectors} recognises languages nothing can verify, and a detector may
 * declare the id of an adapter language so that a language which later gained an adapter is not reported
 * twice. That declaration is a claim about a piece of code the detectors module does not depend on, so the
 * claim can only be checked where both are on the classpath: here, where the adapters are.
 *
 * <p>Both registries are discovered through {@link java.util.ServiceLoader}, the same mechanism the
 * command line wiring uses, so a detector or an adapter that is not on the classpath fails this test
 * rather than silently going unchecked.
 */
class DetectorAdapterAgreementTest {

    @Test
    @DisplayName("every adapter language a detector claims is an adapter that really exists")
    void declaredAdapterLanguagesExist() {
        LanguageAdapterRegistry adapters = new LanguageAdapterRegistry().discover();
        LanguageDetectorRegistry detectors = new LanguageDetectorRegistry().discover();

        assertTrue(adapters.ids().contains("csharp"),
                "the C# adapter must be on the classpath of the CLI: " + adapters.ids());

        for (LanguageDetector detector : detectors.all()) {
            detector.verifiedAsAdapterLanguage().ifPresent(language -> assertTrue(
                    adapters.byId(language).isPresent(),
                    detector.getClass().getName() + " declares that an adapter verifies \"" + language
                            + "\", but no such adapter is registered; the report would then describe that "
                            + "language as present and unverifiable while an adapter claims it, or the "
                            + "detector would be suppressed by nothing"));
        }
    }

    @Test
    @DisplayName("a csharp project is verified rather than reported as present and unverifiable")
    void verifiedLanguagesAreNotReportedTwice(@TempDir Path project) throws IOException {
        write(project, "Ledger/Ledger.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"></Project>\n");
        LanguageDetectorRegistry detectors = new LanguageDetectorRegistry().discover();

        List<String> covered = detectors.detect(project, Set.of(LanguageId.CSHARP)).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(covered.isEmpty(),
                "the adapter claimed this tree, so nothing about it is unverifiable: " + covered);

        // The detector still speaks for the rest of the .NET family, which the adapter does not claim. The
        // covered set is empty here because nothing claimed this tree: the C# adapter requires C# evidence,
        // and an F# project has none.
        Path fsharp = project.resolve("fsharp");
        write(fsharp, "Ledger.fsproj", "<Project Sdk=\"Microsoft.NET.Sdk\"></Project>\n");
        List<String> uncovered = detectors.detect(fsharp, Set.of()).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(uncovered.contains("C# or F#"),
                "an F# project is still recognised and still not verifiable: " + uncovered);
    }

    private static void write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}

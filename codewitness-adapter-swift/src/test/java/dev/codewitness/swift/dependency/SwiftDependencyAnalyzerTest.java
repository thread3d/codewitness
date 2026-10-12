package dev.codewitness.swift.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.swift.SwiftTestContexts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inventory of a SwiftPM package's resolved dependencies.
 *
 * <p>The two {@code Package.resolved} layouts are in the wild and both must be read: version 1 nests the
 * pins under {@code object} and names the URL {@code repositoryURL}, while versions 2 and 3 put the pins
 * at the top level and call it {@code location}. A pin that tracks a revision carries no version but is
 * still a resolved package, and a pin that declares neither is recorded unresolved rather than given a
 * version an advisory service would be asked about.
 */
class SwiftDependencyAnalyzerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the version 1 shape nests its pins under object and reads repositoryURL")
    void readsTheVersionOneShape(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.write(root, "Package.swift", "// swift-tools-version:5.9\n");
        SwiftTestContexts.write(root, "Package.resolved", """
                {
                  "object": {
                    "pins": [
                      {
                        "package": "Alamofire",
                        "repositoryURL": "https://github.com/Alamofire/Alamofire.git",
                        "state": { "branch": null, "revision": "abc123", "version": "5.9.1" }
                      }
                    ]
                  },
                  "version": 1
                }
                """);
        VerificationContext context = SwiftTestContexts.context(root, run);

        DependencyReport report = new SwiftDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        assertEquals("swiftpm", report.tool());
        assertEquals(1, report.totalDependencies(), report.coordinates().toString());
        PackageCoordinate expected = PackageCoordinate.swiftPackage(
                "https://github.com/Alamofire/Alamofire.git", "5.9.1");
        assertEquals(List.of(expected), report.coordinates());
        assertEquals("https://github.com/Alamofire/Alamofire.git@5.9.1",
                report.directDependencies().getFirst());
        assertTrue(report.note().contains("inventory only"), report.note());
        assertTrue(report.note().contains("Package.resolved"), report.note());
        assertTrue(report.note().contains("SwiftPM"), report.note());
        assertEquals(1, report.dependenciesByModule().size(), report.dependenciesByModule().toString());
        assertEquals(report.directDependencies(),
                report.dependenciesByModule().values().iterator().next(),
                "the package's dependencies are attributed to its own module: " + report.dependenciesByModule());
    }

    @Test
    @DisplayName("the version 2/3 shape puts its pins at the top level, with location and a revision fallback")
    void readsTheVersionTwoAndThreeShape(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.write(root, "Package.swift", "// swift-tools-version:5.9\n");
        SwiftTestContexts.write(root, "Package.resolved", """
                {
                  "pins": [
                    {
                      "identity": "swift-argument-parser",
                      "kind": "remoteSourceControl",
                      "location": "https://github.com/apple/swift-argument-parser.git",
                      "state": { "revision": "1c2c1e0", "version": "1.3.0" }
                    },
                    {
                      "identity": "revision-only",
                      "location": "https://example.com/rev.git",
                      "state": { "revision": "deadbeef" }
                    },
                    {
                      "identity": "unresolved",
                      "location": "https://example.com/none.git",
                      "state": { "branch": "main" }
                    },
                    { "location": "https://example.com/no-state.git" }
                  ],
                  "version": 3
                }
                """);
        VerificationContext context = SwiftTestContexts.context(root, run);

        DependencyReport report = new SwiftDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        assertEquals(4, report.totalDependencies(), report.coordinates().toString());
        assertTrue(report.directDependencies().contains(
                        "https://github.com/apple/swift-argument-parser.git@1.3.0"),
                report.directDependencies().toString());
        assertTrue(report.directDependencies().contains("https://example.com/rev.git@deadbeef"),
                "a pin that tracks a revision names a version an advisory service can be asked about: "
                        + report.directDependencies());
        assertEquals(2, report.coordinates().stream().filter(PackageCoordinate::resolved).count(),
                "a branch is not a version, and a pin with no state at all resolved nothing: "
                        + report.coordinates());
        assertTrue(report.coordinates().stream()
                        .filter(coordinate -> "example.com/none".equals(coordinate.name()))
                        .allMatch(coordinate -> coordinate.version().isEmpty()),
                "a branch name must never be promoted to a version: " + report.coordinates());
        assertTrue(report.note().contains("2 of the 4 pin(s) name neither a version nor a revision"),
                report.note());
    }

    @Test
    @DisplayName("both pin shapes are found, and neither is guessed at when the document carries neither")
    void findsThePinsArrayOfEitherShape() throws IOException {
        assertNull(SwiftDependencyAnalyzer.pins(null));
        assertNull(SwiftDependencyAnalyzer.pins(MAPPER.readTree("[1,2]")),
                "a resolved file is an object, so an array has no pins");
        assertNull(SwiftDependencyAnalyzer.pins(MAPPER.readTree("{}")));
        assertNull(SwiftDependencyAnalyzer.pins(MAPPER.readTree("{\"pins\":{}}")),
                "a pins key that is not an array is not a pins array");

        JsonNode top = SwiftDependencyAnalyzer.pins(MAPPER.readTree("{\"pins\":[]}"));
        assertNotNull(top);
        assertTrue(top.isArray());
        JsonNode legacy = SwiftDependencyAnalyzer.pins(
                MAPPER.readTree("{\"object\":{\"pins\":[{\"package\":\"x\"}]}}"));
        assertNotNull(legacy);
        assertEquals(1, legacy.size());
    }

    @Test
    @DisplayName("a pin is named by its location, then its repository URL, then its identity, then its package")
    void namesAPinFromTheBestIdentityItHas() throws IOException {
        JsonNode location = MAPPER.readTree("""
                {"identity":"identity-name","location":"https://x.git",
                 "state":{"version":"1.0.0"}}""");
        PackageCoordinate preferred = SwiftDependencyAnalyzer.coordinate(location);
        assertEquals("x", preferred.name(),
                "the URL is reduced to the host-and-path identity the SwiftURL ecosystem matches on, so it "
                        + "beats the bare name");
        assertEquals("https://x.git@1.0.0", preferred.display(),
                "the display keeps the location the pin recorded");
        assertEquals(PackageCoordinate.SWIFT_URL, preferred.ecosystem());
        assertEquals("1.0.0", preferred.version());

        JsonNode repository = MAPPER.readTree("""
                {"package":"Legacy","repositoryURL":"https://legacy.git","state":{"revision":"r1"}}""");
        PackageCoordinate v1 = SwiftDependencyAnalyzer.coordinate(repository);
        assertEquals("legacy", v1.name());
        assertEquals("r1", v1.version());

        JsonNode identity = MAPPER.readTree("{\"identity\":\"Bare\",\"state\":{\"version\":\"2.0.0\"}}");
        assertEquals("Bare", SwiftDependencyAnalyzer.coordinate(identity).name(),
                "a pin with no URL still names its package, and dropping it would under-report the graph");

        JsonNode legacyPackage = MAPPER.readTree("{\"package\":\"Old\",\"state\":{}}");
        assertEquals("Old", SwiftDependencyAnalyzer.coordinate(legacyPackage).name());

        assertNull(SwiftDependencyAnalyzer.coordinate(MAPPER.readTree("{\"state\":{\"version\":\"1\"}}")),
                "a pin with no identity at all is left out rather than given an invented name");
        assertNull(SwiftDependencyAnalyzer.coordinate(MAPPER.readTree("[1,2]")));
    }

    @Test
    @DisplayName("a pin that carries no identity is counted in the note rather than invented")
    void unnamedPinsAreCounted(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.write(root, "Package.swift", "// swift-tools-version:5.9\n");
        SwiftTestContexts.write(root, "Package.resolved", """
                {
                  "pins": [
                    { "state": { "version": "1.0.0" } },
                    { "location": "https://example.com/real.git", "state": { "version": "2.0.0" } }
                  ],
                  "version": 2
                }
                """);
        VerificationContext context = SwiftTestContexts.context(root, run);

        DependencyReport report = new SwiftDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        assertEquals(1, report.totalDependencies(), report.coordinates().toString());
        assertTrue(report.note().contains("1 pin(s) carried no location"), report.note());
    }

    @Test
    @DisplayName("a manifest with no lock file is an inventory of zero, not a failure and not a claim about the manifest")
    void aManifestWithoutALockIsAnInventoryOfZero(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.write(root, "Package.swift", "// swift-tools-version:5.9\n");
        VerificationContext context = SwiftTestContexts.context(root, run);

        DependencyReport report = new SwiftDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        assertEquals(0, report.totalDependencies(), report.coordinates().toString());
        assertTrue(report.directDependencies().isEmpty());
        assertTrue(report.note().contains("declares no external dependencies"), report.note());
        assertTrue(report.note().contains("no Package.resolved"), report.note());
        assertTrue(report.note().contains("not resolved them yet"),
                "the note must not turn an unresolved package into a claim about its manifest: "
                        + report.note());
    }

    @Test
    @DisplayName("a malformed lock file is not analysed, never an empty inventory")
    void aMalformedLockIsNotAnalysed(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.write(root, "Package.swift", "// swift-tools-version:5.9\n");
        SwiftTestContexts.write(root, "Package.resolved", "{ this is not json");
        VerificationContext context = SwiftTestContexts.context(root, run);

        DependencyReport report = new SwiftDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed(), "an unreadable lock file must not read as a project with no deps");
        assertTrue(report.note().contains("could not be parsed"), report.note());
        assertTrue(report.coordinates().isEmpty(), report.coordinates().toString());
    }

    @Test
    @DisplayName("a lock file with no pins array is not analysed either")
    void aLockWithoutPinsIsNotAnalysed(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.write(root, "Package.swift", "// swift-tools-version:5.9\n");
        SwiftTestContexts.write(root, "Package.resolved", "{\"version\":2}\n");
        VerificationContext context = SwiftTestContexts.context(root, run);

        DependencyReport report = new SwiftDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed());
        assertTrue(report.note().contains("carries no pins array"), report.note());
    }

    @Test
    @DisplayName("a tree with neither file is not analysed, because it is not a SwiftPM package")
    void neitherFileIsNotAnalysed(@TempDir Path root, @TempDir Path run) {
        VerificationContext context = SwiftTestContexts.context(root, run);

        DependencyReport report = new SwiftDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed());
        assertTrue(report.note().contains("neither Package.resolved nor Package.swift"), report.note());
    }

    @Test
    @DisplayName("the analyzer reads the lock file directly, claims Swift projects and runs nothing")
    void runsNothingAndSupportsOnlySwift(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.write(root, "Package.swift", "// swift-tools-version:5.9\n");
        SwiftTestContexts.write(root, "Package.resolved", "{\"pins\":[]}\n");
        VerificationContext context = SwiftTestContexts.context(root, run);
        SwiftDependencyAnalyzer analyzer = new SwiftDependencyAnalyzer();

        assertEquals("swiftpm", analyzer.id());
        assertEquals(List.of(), analyzer.dependencyCommand(context),
                "swift package resolve would rewrite the very file this analysis is a report about");
        assertTrue(analyzer.supports(context.projectInfo()));

        ProjectInfo javaProject = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .build();
        assertFalse(analyzer.supports(javaProject),
                "the Java adapter has its own dependency analyzer; two would both run");
    }
}

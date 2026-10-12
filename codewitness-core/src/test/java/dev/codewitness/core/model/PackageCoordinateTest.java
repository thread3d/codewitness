package dev.codewitness.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading Maven coordinates out of a dependency inventory. */
class PackageCoordinateTest {

    @Test
    void readsGroupArtifactAndVersionFromAResolvedCoordinate() {
        List<PackageCoordinate> parsed = PackageCoordinate.parseMaven(
                List.of("com.fasterxml.jackson.core:jackson-databind:jar:2.17.1:compile"));

        assertEquals(1, parsed.size());
        PackageCoordinate coordinate = parsed.getFirst();
        assertEquals("Maven", coordinate.ecosystem());
        assertEquals("com.fasterxml.jackson.core:jackson-databind", coordinate.name());
        assertEquals("2.17.1", coordinate.version());
    }

    @Test
    void aClassifierShiftsTheVersionToTheSecondToLastElement() {
        // Maven prints groupId:artifactId:type:classifier:version:scope when a classifier is declared.
        List<PackageCoordinate> parsed = PackageCoordinate.parseMaven(
                List.of("com.example:fixtures:test-jar:tests:1.4.0:test"));

        assertEquals("1.4.0", parsed.getFirst().version());
        assertEquals("com.example:fixtures", parsed.getFirst().name());
    }

    @Test
    void skipsAnythingItCannotReadRatherThanGuessing() {
        List<PackageCoordinate> parsed = PackageCoordinate.parseMaven(List.of(
                "requests==2.31.0",
                "serde",
                "",
                "a:b:c:d",
                "com.example::jar:1.0:compile",
                "org.slf4j:slf4j-api:jar:1.7.36:compile"));

        assertEquals(1, parsed.size(), "only the well formed coordinate should survive: " + parsed);
        assertEquals("org.slf4j:slf4j-api", parsed.getFirst().name());
    }

    @Test
    void toleratesSurroundingWhitespace() {
        List<PackageCoordinate> parsed =
                PackageCoordinate.parseMaven(List.of("  org.yaml:snakeyaml:jar:2.2:compile  "));

        assertEquals("org.yaml:snakeyaml", parsed.getFirst().name());
        assertEquals("2.2", parsed.getFirst().version());
        assertTrue(parsed.getFirst().display().startsWith("org.yaml"));
    }

    @Test
    void aSwiftLocationIsReducedToTheIdentityTheEcosystemMatchesOn() {
        // Package.resolved records the URL a human clones; the SwiftURL ecosystem names host/path.
        for (String location : List.of(
                "https://github.com/apple/swift-nio.git",
                "https://github.com/apple/swift-nio/",
                "git@github.com:apple/swift-nio.git",
                "ssh://git@github.com/apple/swift-nio.git")) {
            assertEquals("github.com/apple/swift-nio", PackageCoordinate.swiftIdentity(location), location);
        }
        assertEquals("github.com/sparkle-project/Sparkle",
                PackageCoordinate.swiftIdentity("https://github.com/sparkle-project/Sparkle.git"),
                "the ecosystem preserves the case a package is registered under, so it is not lowercased");
        assertEquals("Alamofire", PackageCoordinate.swiftIdentity("Alamofire"),
                "a bare identity is not a URL and is left alone rather than mangled");
    }

    @Test
    void aSwiftCoordinateQueriesTheNormalisedNameAndDisplaysTheRecordedLocation() {
        PackageCoordinate coordinate =
                PackageCoordinate.swiftPackage("https://github.com/apple/swift-nio.git", "2.50.0");

        assertEquals(PackageCoordinate.SWIFT_URL, coordinate.ecosystem());
        assertEquals("github.com/apple/swift-nio", coordinate.name(),
                "the raw URL never matches a SwiftURL advisory, so the queryable name is normalised");
        assertEquals("https://github.com/apple/swift-nio.git@2.50.0", coordinate.display(),
                "the report still shows the location the pin recorded");
        assertTrue(coordinate.resolved());
    }
}

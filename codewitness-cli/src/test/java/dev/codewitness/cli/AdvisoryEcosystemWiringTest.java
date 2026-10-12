package dev.codewitness.cli;

import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.spi.VerificationTools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every ecosystem the shipped adapters inventory must be one the advisory reproduction lookup answers
 * for, and an ecosystem nothing claims must stay unanswered rather than being guessed at.
 *
 * <p>This is the composition-root half of the advisory hook. The core deliberately names no ecosystem:
 * when an advisory is attributed to a requirement, it asks the registered dependency analyzer that
 * claims the advisory's ecosystem for a dependency-path hint and command. That only works if every
 * bundled analyzer declares the ecosystem its coordinates are filed under, which can be checked in the
 * one module that has all of them on its classpath.
 *
 * <p>The last case is the point of the hook for a future language: an analyzer that declares a novel
 * ecosystem is picked up with no core change, and an ecosystem nothing declares produces no invented
 * recipe.
 */
class AdvisoryEcosystemWiringTest {

    /** The ecosystems the bundled adapters resolve versions in, as the advisory service spells them. */
    private static final Set<String> SHIPPED = Set.of(
            PackageCoordinate.MAVEN,
            PackageCoordinate.NPM,
            PackageCoordinate.PYPI,
            PackageCoordinate.CRATES_IO,
            PackageCoordinate.GO,
            PackageCoordinate.NUGET,
            PackageCoordinate.CONAN_CENTER,
            PackageCoordinate.PACKAGIST,
            PackageCoordinate.RUBYGEMS,
            PackageCoordinate.SWIFT_URL);

    @Test
    @DisplayName("every shipped ecosystem is claimed by a registered dependency analyzer")
    void everyShippedEcosystemIsClaimed() {
        var providers = CliSupport.wiring().providers();

        Set<String> claimed = new LinkedHashSet<>();
        for (VerificationTools.DependencyAnalyzer analyzer : providers.dependencyAnalyzers()) {
            claimed.addAll(analyzer.ecosystems());
        }

        assertEquals(SHIPPED, claimed,
                "the ecosystems the adapters inventory and the ones the advisory recipe lookup can answer "
                        + "for must be the same set; a missing one means advisories against that language "
                        + "arrive with no reproduction recipe");
        for (String ecosystem : SHIPPED) {
            assertTrue(providers.dependencyAnalyzerFor(ecosystem).isPresent(),
                    "no registered analyzer claims " + ecosystem);
        }
    }

    @Test
    @DisplayName("each shipped ecosystem's registered analyzer names its own tool")
    void eachEcosystemNamesItsOwnTool() {
        var providers = CliSupport.wiring().providers();

        assertTrue(hint(providers, PackageCoordinate.PACKAGIST,
                        PackageCoordinate.packagist("symfony/http-foundation", "4.0.0"))
                        .contains("composer why symfony/http-foundation"),
                "the PHP analyzer must supply Composer's own command");
        assertTrue(hint(providers, PackageCoordinate.RUBYGEMS, PackageCoordinate.rubygem("rack", "2.0.0"))
                        .contains("gem dependency rack"),
                "the Ruby analyzer must supply RubyGems' own command");
        assertTrue(hint(providers, PackageCoordinate.NUGET, PackageCoordinate.nuget("Newtonsoft.Json", "12.0.1"))
                        .contains("dotnet nuget why"),
                "the C# analyzer must supply NuGet's own command");
        assertTrue(hint(providers, PackageCoordinate.CONAN_CENTER, PackageCoordinate.conan("openssl", "1.1.1"))
                        .contains("conan graph info"),
                "the C/C++ analyzer must supply Conan's own command");
        assertTrue(hint(providers, PackageCoordinate.SWIFT_URL,
                        PackageCoordinate.swiftPackage("https://github.com/apple/swift-nio.git", "2.50.0"))
                        .contains("swift package show-dependencies"),
                "the Swift analyzer must supply SwiftPM's own command");
        assertTrue(hint(providers, PackageCoordinate.GO, PackageCoordinate.go("github.com/pkg/errors", "v0.9.1"))
                        .contains("go mod why -m github.com/pkg/errors"),
                "the Go analyzer must supply the go tool's own command");
    }

    @Test
    @DisplayName("an ecosystem nothing claims is left unanswered rather than guessed at")
    void anUnclaimedEcosystemIsNotGuessedAt() {
        var providers = CliSupport.wiring().providers();
        String ecosystem = "AnEcosystemNoAdapterClaims";

        assertTrue(providers.dependencyAnalyzerFor(ecosystem).isEmpty(),
                "the core must not answer for an ecosystem no analyzer declares");
        assertEquals("", new HintlessAnalyzer().dependencyPathHint(
                PackageCoordinate.of(ecosystem, "pkg", "1.0")),
                "the SPI's default is an empty hint, not a Maven-shaped guess");
        assertTrue(new HintlessAnalyzer().dependencyPathCommand(null,
                        PackageCoordinate.of(ecosystem, "pkg", "1.0"))
                .isEmpty(),
                "the SPI's default is an empty command, not an invented one");
        assertTrue(new HintlessAnalyzer().ecosystems().isEmpty(),
                "an analyzer that reports no queryable coordinates claims no ecosystem");
    }

    private static String hint(
            dev.codewitness.core.spi.VerificationProviderRegistry providers,
            String ecosystem,
            PackageCoordinate coordinate) {
        VerificationTools.DependencyAnalyzer analyzer = providers.dependencyAnalyzerFor(ecosystem)
                .orElseThrow(() -> new AssertionError("no analyzer claims " + ecosystem));
        return analyzer.dependencyPathHint(coordinate);
    }

    /**
     * An analyzer that implements the required methods only, so the test pins the SPI defaults a new
     * language inherits before it answers anything.
     */
    private static final class HintlessAnalyzer implements VerificationTools.DependencyAnalyzer {

        @Override
        public String id() {
            return "hintless";
        }

        @Override
        public boolean supports(dev.codewitness.core.model.ProjectInfo project) {
            return false;
        }

        @Override
        public dev.codewitness.core.model.DependencyReport analyseDependencies(
                dev.codewitness.core.spi.VerificationContext context) {
            return dev.codewitness.core.model.DependencyReport.notAnalysed(id(), "test double");
        }
    }
}

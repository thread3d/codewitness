package dev.codewitness.support.dependency;

import dev.codewitness.core.model.PackageCoordinate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-ecosystem dependency-path hints an adapter supplies for an advisory's reproduction recipe.
 *
 * <p>The core knows none of these strings; an adapter chooses one and the strategy appends it. The test
 * pins two things that matter for honesty: every hint names the ecosystem's own tool, and the one
 * executable recipe is only built when there is a recorded build command to hang it on.
 */
class DependencyPathHintsTest {

    @Test
    @DisplayName("each ecosystem's hint names its own tool and the package where the tool takes one")
    void everyHintNamesItsOwnTool() {
        PackageCoordinate npm = PackageCoordinate.npm("@acme/thing", "1.2.3");
        assertEquals(" (npm: `npm ls @acme/thing`)", DependencyPathHints.npm(npm));
        assertEquals(" (cargo: `cargo tree -i serde`)",
                DependencyPathHints.crate(PackageCoordinate.crate("serde", "1.0.0")));
        assertEquals(" (go: `go mod why -m github.com/pkg/errors`)",
                DependencyPathHints.go(PackageCoordinate.go("github.com/pkg/errors", "v0.9.1")));
        assertEquals(" (Composer: `composer why symfony/http-foundation`)",
                DependencyPathHints.packagist(PackageCoordinate.packagist("symfony/http-foundation", "4.0.0")));
        assertEquals(" (RubyGems: `gem dependency rack --reverse-dependencies`)",
                DependencyPathHints.rubygem(PackageCoordinate.rubygem("rack", "2.0.0")));
        assertEquals(" (pip: `pip show requests`)",
                DependencyPathHints.pypi(PackageCoordinate.python("requests", "2.31.0")));
    }

    @Test
    @DisplayName("tools with no per-package path command are named without inventing a package argument")
    void toolsWithoutAPerPackageCommandAreNamedPlainly() {
        // dotnet nuget why needs .NET 9 and a project argument; the hint says so rather than guessing
        // a project file. Conan and SwiftPM print the whole graph rather than one package's path.
        assertTrue(DependencyPathHints.nuget(PackageCoordinate.nuget("Newtonsoft.Json", "12.0.1"))
                .contains("dotnet nuget why"), "the NuGet tool is named");
        assertTrue(DependencyPathHints.conan(PackageCoordinate.conan("openssl", "1.1.1"))
                .contains("conan graph info"), "the Conan tool is named");
        assertTrue(DependencyPathHints.swift(
                        PackageCoordinate.swiftPackage("https://github.com/apple/swift-nio.git", "2.50.0"))
                .contains("swift package show-dependencies"), "the SwiftPM tool is named");
    }

    @Test
    @DisplayName("the Maven recipe hangs dependency:tree on the build command the run observed")
    void theMavenRecipeUsesTheRecordedBuildCommand() {
        PackageCoordinate coordinate = PackageCoordinate.of(PackageCoordinate.MAVEN,
                "com.fasterxml.jackson.core:jackson-databind", "2.17.1");

        assertEquals(List.of("mvn", "-B", "dependency:tree",
                        "-Dincludes=com.fasterxml.jackson.core:jackson-databind"),
                DependencyPathHints.mavenCommand(List.of("mvn"), coordinate));
        assertEquals(List.of("/opt/maven/bin/mvn", "-B", "dependency:tree",
                        "-Dincludes=com.fasterxml.jackson.core:jackson-databind"),
                DependencyPathHints.mavenCommand(List.of("/opt/maven/bin/mvn"), coordinate),
                "the project's own recorded executable is the one that was observed to work");
    }

    @Test
    @DisplayName("no recorded build command means no invented Maven recipe")
    void noRecordedBuildCommandMeansNoRecipe() {
        PackageCoordinate coordinate = PackageCoordinate.of(PackageCoordinate.MAVEN,
                "com.fasterxml.jackson.core:jackson-databind", "2.17.1");

        assertEquals(List.of(), DependencyPathHints.mavenCommand(List.of(), coordinate));
        assertEquals(List.of(), DependencyPathHints.mavenCommand((List<String>) null, coordinate));
    }
}

package dev.codewitness.support.dependency;

import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.spi.VerificationContext;

import java.util.List;

/**
 * The ecosystem-specific half of a dependency-advisory counterexample: how a human shows why a resolved
 * dependency is on the project's graph.
 *
 * <p>This catalog lives in adapter support rather than in the core because every entry names a language's
 * own tool. An adapter answers
 * {@link dev.codewitness.core.spi.VerificationTools.DependencyAnalyzer#dependencyPathHint(PackageCoordinate)}
 * and {@code dependencyPathCommand} with the entry for the ecosystem its coordinates belong to, and the
 * core never learns which tool belongs to which ecosystem. That is the extension point a new language
 * uses: declaring its ecosystem and choosing or phrasing a hint is all it takes for advisories against
 * that language to arrive with a usable reproduction recipe.
 *
 * <p>Every hint is advice for a human, not something CodeWitness executes - a project may use a different
 * package manager than the one named, and the text says which tool it is so that a reader can tell. The
 * one exception is {@link #mavenCommand}, which builds an executable recipe because Maven is the one
 * ecosystem whose dependency-path invocation can be hung on the build command the run actually observed.
 */
public final class DependencyPathHints {

    private DependencyPathHints() {
    }

    /** @return the npm hint for a package name. */
    public static String npm(PackageCoordinate coordinate) {
        return " (npm: `npm ls " + coordinate.name() + "`)";
    }

    /** @return the PyPI hint; {@code pip show} names the installed distribution and where it came from. */
    public static String pypi(PackageCoordinate coordinate) {
        return " (pip: `pip show " + coordinate.name() + "`)";
    }

    /** @return the crates.io hint; {@code -i} inverts the tree to show what requires the crate. */
    public static String crate(PackageCoordinate coordinate) {
        return " (cargo: `cargo tree -i " + coordinate.name() + "`)";
    }

    /** @return the Go hint; {@code go mod why -m} names the import path that needs the module. */
    public static String go(PackageCoordinate coordinate) {
        return " (go: `go mod why -m " + coordinate.name() + "`)";
    }

    /**
     * @return the NuGet hint.
     *
     * <p>{@code dotnet nuget why} is the only NuGet command that prints a dependency path rather than a
     * flat package list; it needs .NET 9 or later, which the hint states so a reader on an older SDK is
     * not sent to a command that does not exist there.
     */
    public static String nuget(PackageCoordinate coordinate) {
        return " (NuGet: `dotnet nuget why` on .NET 9 or later)";
    }

    /** @return the Conan hint; {@code conan graph info} prints the resolved dependency graph. */
    public static String conan(PackageCoordinate coordinate) {
        return " (Conan: `conan graph info .`)";
    }

    /** @return the Packagist hint; {@code composer why} names what requires the package. */
    public static String packagist(PackageCoordinate coordinate) {
        return " (Composer: `composer why " + coordinate.name() + "`)";
    }

    /**
     * @return the RubyGems hint.
     *
     * <p>Bundler has no reverse-dependency command of its own; {@code gem dependency
     * --reverse-dependencies} is the one that names the gems requiring a gem.
     */
    public static String rubygem(PackageCoordinate coordinate) {
        return " (RubyGems: `gem dependency " + coordinate.name() + " --reverse-dependencies`)";
    }

    /** @return the SwiftPM hint; {@code show-dependencies} prints the resolved package graph. */
    public static String swift(PackageCoordinate coordinate) {
        return " (SwiftPM: `swift package show-dependencies`)";
    }

    /**
     * @return the Maven recipe, built on the build executable the run observed working on this project.
     *
     * <p>Maven is the one ecosystem whose path command can be hung on the recorded build command, so it
     * returns an executable recipe rather than prose. When no build command was recorded there is nothing
     * to hang it on, and an empty list is the honest answer: a guessed {@code mvn} could name an
     * executable this project never used.
     *
     * @param context    the run context, whose build state carries the recorded command
     * @param coordinate the vulnerable dependency
     * @return argv style command, or an empty list when the project recorded no build command
     */
    public static List<String> mavenCommand(VerificationContext context, PackageCoordinate coordinate) {
        List<String> build = context.state().build() == null ? List.of() : context.state().build().command();
        return mavenCommand(build, coordinate);
    }

    /**
     * @param recordedBuildCommand the build command the run observed, or an empty list when there was none
     * @param coordinate           the vulnerable dependency
     * @return the dependency-path recipe hung on that command, or an empty list when there is no command
     *     to hang it on
     */
    static List<String> mavenCommand(List<String> recordedBuildCommand, PackageCoordinate coordinate) {
        if (recordedBuildCommand == null || recordedBuildCommand.isEmpty()) {
            return List.of();
        }
        return List.of(recordedBuildCommand.getFirst(), "-B", "dependency:tree",
                "-Dincludes=" + coordinate.name());
    }
}

package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared helpers for the end-to-end tests: locating fixtures, finding runs and reading parts of the
 * canonical report.
 */
abstract class EndToEndSupport {

    /** @return the fixture directory, failing fast when the repository layout is not what we expect. */
    static Path requireFixture(String name) {
        Path fixture = repositoryRoot().resolve("fixtures").resolve(name);
        Assumptions.assumeTrue(Files.isDirectory(fixture),
                "fixture " + name + " is not present in this checkout");
        return fixture;
    }

    /** @return the repository root, found by walking up from the working directory. */
    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    /** Skips the test when no Maven executable is available, instead of failing spuriously. */
    static void assumeBuildToolAvailable() {
        Assumptions.assumeTrue(findMaven() != null,
                "no Maven executable on PATH, MAVEN_HOME or M2_HOME: end-to-end verification cannot run");
    }

    static String findMaven() {
        return findExecutable("mvn");
    }

    /** Skips the test when no Python interpreter is available, instead of failing spuriously. */
    static void assumePythonAvailable() {
        Assumptions.assumeTrue(findPython() != null,
                "no Python interpreter on PATH or VIRTUAL_ENV: Python end-to-end verification cannot run");
    }

    /** @return the first Python interpreter that exists, preferring an active virtual environment. */
    static String findPython() {        String virtualEnv = System.getenv("VIRTUAL_ENV");
        if (virtualEnv != null && !virtualEnv.isBlank()) {
            for (String relative : List.of("bin/python", "Scripts/python.exe")) {
                Path candidate = Path.of(virtualEnv, relative);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        for (String name : List.of("python3", "python")) {
            String found = findExecutable(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Skips the test when no Cargo executable is available, instead of failing spuriously. */
    static void assumeCargoAvailable() {
        Assumptions.assumeTrue(findCargo() != null,
                "no cargo executable on PATH: Rust end-to-end verification cannot run");
    }

    /** @return the Cargo executable on {@code PATH}, or {@code null}. */
    static String findCargo() {
        return findExecutable("cargo");
    }

    /** Skips the test when no {@code go} executable is available, instead of failing spuriously. */
    static void assumeGoAvailable() {
        Assumptions.assumeTrue(findGo() != null,
                "no go executable on PATH: Go end-to-end verification cannot run");
    }

    /** @return the {@code go} executable on {@code PATH}, or {@code null}. */
    static String findGo() {
        return findExecutable("go");
    }

    /** Skips the test when no Node executable is available, instead of failing spuriously. */
    static void assumeNodeAvailable() {
        Assumptions.assumeTrue(findNode() != null,
                "no node executable on PATH: JavaScript and TypeScript end-to-end verification cannot run");
    }

    /** @return the Node executable on {@code PATH}, or {@code null}. */
    static String findNode() {
        return findExecutable("node");
    }

    /**
     * Skips the test when no CMake is available, instead of failing spuriously.
     *
     * <p>The C and C++ adapter drives the toolchain the project already uses, so the fixture needs CMake and
     * the CTest that ships with it. Both are looked for the way the adapter looks for them: on {@code PATH}.
     */
    static void assumeCmakeAvailable() {
        Assumptions.assumeTrue(findCmake() != null,
                "no cmake executable on PATH: C and C++ end-to-end verification cannot run");
        Assumptions.assumeTrue(findExecutable("ctest") != null,
                "no ctest executable on PATH: C and C++ end-to-end verification cannot run");
    }

    /** @return the {@code cmake} executable on {@code PATH}, or {@code null}. */
    static String findCmake() {
        return findExecutable("cmake");
    }

    /**
     * Skips the test when no PHP interpreter is available, instead of failing spuriously.
     *
     * <p>PHP is the one adapter whose test tool is not part of the language toolchain: PHPUnit is a
     * Composer dependency, so the fixture's build has to resolve it from Packagist before a test can run.
     * Only the interpreter is checked here; a Composer that cannot reach the network makes the build fail
     * loudly, and the run reports that as {@code BLOCKED} rather than as a code defect.
     */
    static void assumePhpAvailable() {
        Assumptions.assumeTrue(findPhp() != null,
                "no php executable on PATH: PHP end-to-end verification cannot run");
    }

    /** @return the {@code php} executable on {@code PATH}, or {@code null}. */
    static String findPhp() {
        return findExecutable(isWindows() ? "php.exe" : "php");
    }

    /**
     * Skips the test when no Ruby interpreter is available, instead of failing spuriously.
     *
     * <p>Ruby is the other adapter whose test tool is not part of the language toolchain: RSpec is a
     * bundle dependency, so the fixture's build has to resolve it from RubyGems before a test can run.
     * Only the interpreter is checked here; a Bundler that cannot reach the network makes the build fail
     * loudly, and the run reports that as {@code BLOCKED} rather than as a code defect.
     */
    static void assumeRubyAvailable() {
        Assumptions.assumeTrue(findRuby() != null,
                "no ruby executable on PATH: Ruby end-to-end verification cannot run");
    }

    /** @return the {@code ruby} executable on {@code PATH}, or {@code null}. */
    static String findRuby() {
        return findExecutable(isWindows() ? "ruby.exe" : "ruby");
    }

    /** Skips the test when no .NET SDK is available, instead of failing spuriously. */
    static void assumeDotnetAvailable() {        Assumptions.assumeTrue(findDotnet() != null,
                "no dotnet executable on PATH, in DOTNET_ROOT or in a conventional install location: "
                        + "C# end-to-end verification cannot run");
    }

    /**
     * @return the .NET SDK driver, or {@code null} when none can be found.
     *
     * <p>The search mirrors the adapter's: {@code DOTNET_ROOT} first, then the locations the SDK's own
     * installer and the package managers use, then the per-user install, and {@code PATH} last. A test
     * that only consulted {@code PATH} would skip on a machine that has an SDK the adapter would have
     * found and used.
     */
    static String findDotnet() {
        String executable = isWindows() ? "dotnet.exe" : "dotnet";
        String root = System.getenv("DOTNET_ROOT");
        if (root != null && !root.isBlank() && Files.isExecutable(Path.of(root, executable))) {
            return Path.of(root, executable).toString();
        }
        for (String directory : List.of("/usr/local/share/dotnet", "/usr/share/dotnet",
                "/opt/homebrew/opt/dotnet/libexec")) {
            Path candidate = Path.of(directory, executable);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        String home = System.getProperty("user.home");
        if (home != null && Files.isExecutable(Path.of(home, ".dotnet", executable))) {
            return Path.of(home, ".dotnet", executable).toString();
        }
        return findExecutable(executable);
    }

    /**
     * Skips the test when no Swift toolchain is available, instead of failing spuriously.
     *
     * <p>Swift is the one adapter whose test tool ships with the language toolchain: XCTest is part of
     * the Swift distribution, so a package with no external dependencies needs no network to build or
     * test. Only the driver is checked here.
     */
    static void assumeSwiftAvailable() {
        Assumptions.assumeTrue(findSwift() != null,
                "no swift executable on PATH or in SWIFT_EXECUTABLE: Swift end-to-end verification cannot run");
    }

    /**
     * @return the {@code swift} driver, or {@code null} when none can be found.
     *
     * <p>The search mirrors the adapter's: {@code SWIFT_EXECUTABLE} first, then the conventional Xcode
     * and open-source toolchain locations, and {@code PATH} last. A test that only consulted {@code PATH}
     * would skip on a macOS machine whose Xcode toolchain is not on the test JVM's PATH but which the
     * adapter would still have found.
     */
    static String findSwift() {
        String executable = isWindows() ? "swift.exe" : "swift";
        String configured = System.getenv("SWIFT_EXECUTABLE");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        for (String directory : List.of(
                "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin",
                "/Library/Developer/Toolchains/swift-latest.xctoolchain/usr/bin",
                "/usr/local/swift/usr/bin",
                "/usr/share/swift/usr/bin")) {
            Path candidate = Path.of(directory, executable);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return findExecutable(executable);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** @return an executable found on {@code PATH}, or {@code null}. */
    static String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (element.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(element, name);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /** @return run directories, most recent first. */
    static List<Path> runDirectories(Path output) {
        Path runs = output.resolve("runs");
        if (!Files.isDirectory(runs)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(runs)) {
            List<Path> directories = new ArrayList<>(stream.filter(Files::isDirectory).toList());
            directories.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            return directories;
        } catch (Exception e) {
            return List.of();
        }
    }

    static JsonNode requirement(JsonNode report, String requirementId) {
        for (JsonNode node : report.path("requirements")) {
            if (requirementId.equals(node.path("id").asText())) {
                return node;
            }
        }
        throw new AssertionError("no verdict for " + requirementId + " in " + report.path("runId").asText());
    }

    static JsonNode counterexampleFor(JsonNode report, String requirementId) {
        for (JsonNode node : report.path("counterexamples")) {
            if (requirementId.equals(node.path("requirementId").asText())) {
                return node;
            }
        }
        return null;
    }

    static void assertReportLooksComplete(JsonNode report) {
        assertNotNull(report.get("runId"), "the report must identify the run");
        assertNotNull(report.get("environment"), "the report must record the environment");
        assertTrue(report.path("environment").path("requirementsHash").asText().length() == 64,
                "the requirements hash must be recorded for reproducibility");
    }
}

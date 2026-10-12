package dev.codewitness.swift;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection of Swift packages.
 *
 * <p>The adapter drives Swift Package Manager and nothing else, so the tests pin the two halves of that
 * claim: a package with a {@code Package.swift} is claimed and described, and a tree with only Swift
 * sources is left to the detector that reports it as unverifiable.
 */
class SwiftProjectDetectorTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("a package is recognised by its manifest, not by having Swift files")
    void recognisesPackages() throws IOException {
        write("Package.swift", """
                // swift-tools-version:5.9
                import PackageDescription
                let package = Package(name: "Ledger", targets: [.target(name: "Ledger")])
                """);

        assertTrue(SwiftProjectDetector.looksLikeSwiftProject(root));
    }

    @Test
    @DisplayName("a tree with Swift sources but no manifest is not a package this adapter can drive")
    void doesNotClaimAManifestlessTree() throws IOException {
        write("Sources/Ledger/Ledger.swift", "public struct Ledger {}\n");

        assertFalse(SwiftProjectDetector.looksLikeSwiftProject(root));
    }

    @Test
    @DisplayName("the manifest names the package, and the layout gives the module and the test target")
    void describesThePackage() throws IOException {
        write("Package.swift", """
                // swift-tools-version:5.9
                import PackageDescription
                let package = Package(
                    name: "Ledger",
                    targets: [
                        .target(name: "Ledger"),
                        .testTarget(name: "LedgerTests", dependencies: ["Ledger"]),
                    ]
                )
                """);
        write("Sources/Ledger/Ledger.swift", "public struct Ledger {}\n");
        write("Tests/LedgerTests/LedgerTests.swift", "import XCTest\nfinal class LedgerTests: XCTestCase {}\n");

        ProjectInfo info = SwiftProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.SWIFT, info.language());
        assertEquals(BuildSystemId.SWIFTPM, info.buildSystem());
        assertTrue(info.sourceExtensions().contains("swift"), info.sourceExtensions().toString());
        assertEquals("Ledger", info.modules().getFirst().name());
        assertTrue(info.hasTests(), "the test target must be reported");
        assertEquals("LedgerTests", info.attributes().get(SwiftToolchain.ATTR_TEST_MODULE));
        assertEquals("Ledger", info.attributes().get(SwiftToolchain.ATTR_HOST_MODULE),
                "the library target a generated test imports is a different fact from the test module "
                        + "XCTest reports a case under");
        assertEquals("xctest", info.attributes().get(SwiftProjectDetector.ATTR_TEST_FRAMEWORK));
    }

    @Test
    @DisplayName("build output and dependency checkouts are never walked as project source")
    void ignoresBuildOutput() throws IOException {
        write("Package.swift", "// swift-tools-version:5.9\n");
        write("Sources/Ledger/Ledger.swift", "public struct Ledger {}\n");
        write(".build/checkouts/some-package/Sources/Other/Other.swift", "public struct Other {}\n");

        List<Path> files = SwiftProjectDetector.swiftFiles(root);

        assertEquals(1, files.size(), files.toString());
        assertTrue(files.getFirst().toString().endsWith("Ledger.swift"), files.toString());
    }

    @Test
    @DisplayName("Package.resolved is reported as a descriptor beside the manifest")
    void reportsResolvedDescriptor() throws IOException {
        write("Package.swift", "// swift-tools-version:5.9\n");
        write("Package.resolved", "{\"pins\":[]}\n");

        ProjectInfo info = SwiftProjectDetector.inspect(ProjectRef.of(root));

        String descriptors = info.attributes().get(SwiftProjectDetector.ATTR_DESCRIPTOR);
        assertTrue(descriptors.contains("Package.swift"), descriptors);
        assertTrue(descriptors.contains("Package.resolved"), descriptors);
    }

    @Test
    @DisplayName("swiftc's canonical diagnostic line is read, and its source snippet is not mistaken for one")
    void readsCompilerDiagnostics() {
        String output = """
                [3/5] Compiling Ledger Ledger.swift
                /work/Sources/Ledger/Broken.swift:2:18: error: cannot convert value of type 'String' to specified type 'Int'
                1 | public func broken() -> Int {
                2 |     let x: Int = "not an int"
                  |                  `- error: cannot convert value of type 'String' to specified type 'Int'
                3 |     return x
                error: fatal issue with the package manifest
                """;

        List<CompilerDiagnostic> diagnostics = SwiftBuildProvider.diagnostics(output, Path.of("/work"));

        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(0).severity());
        assertEquals("Sources/Ledger/Broken.swift", diagnostics.get(0).file());
        assertEquals(2, diagnostics.get(0).line());
        assertEquals(18, diagnostics.get(0).column());
        assertTrue(diagnostics.get(0).message().startsWith("cannot convert"), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.file() == null), diagnostics.toString());
    }
}

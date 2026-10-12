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
        assertTrue(adapters.ids().contains("kotlin"),
                "the Kotlin adapter must be on the classpath of the CLI: " + adapters.ids());
        assertTrue(adapters.ids().contains("cpp"),
                "the C and C++ adapter must be on the classpath of the CLI: " + adapters.ids());
        assertTrue(adapters.ids().contains("php"),
                "the PHP adapter must be on the classpath of the CLI: " + adapters.ids());
        assertTrue(adapters.ids().contains("swift"),
                "the Swift adapter must be on the classpath of the CLI: " + adapters.ids());

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

    @Test
    @DisplayName("a Kotlin project is verified rather than reported as present and unverifiable")
    void kotlinIsNotReportedTwice(@TempDir Path project) throws IOException {
        write(project, "src/main/kotlin/com/example/Ledger.kt", "package com.example\n\nclass Ledger\n");
        LanguageDetectorRegistry detectors = new LanguageDetectorRegistry().discover();

        List<String> covered = detectors.detect(project, Set.of(LanguageId.KOTLIN)).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(covered.isEmpty(),
                "the Kotlin adapter claimed this tree, so nothing about it is unverifiable: " + covered);

        // A tree the adapter does not claim - here, one with no Kotlin in it at all - is still the
        // detector's to speak for, which is the point of keeping the detector registered rather than
        // deleting it. Perl is the example because nothing verifies it: the languages with adapters are
        // suppressed by the covered set, and this call passes none, so only a language no adapter claims
        // can appear here.
        Path other = project.resolve("perl");
        write(other, "ledger.pl", "sub total {\n  return 0;\n}\n");
        List<String> uncovered = detectors.detect(other, Set.of()).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(uncovered.contains("Perl"),
                "a language no adapter claimed is still named: " + uncovered);
    }

    @Test
    @DisplayName("a CMake C++ project is verified, and a Make-built one is still reported as unverifiable")
    void cppIsNotReportedTwice(@TempDir Path project) throws IOException {
        write(project, "CMakeLists.txt", """
                cmake_minimum_required(VERSION 3.20)
                project(ledger LANGUAGES CXX)
                """);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        LanguageDetectorRegistry detectors = new LanguageDetectorRegistry().discover();

        List<String> covered = detectors.detect(project, Set.of(LanguageId.CPP)).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(covered.isEmpty(),
                "the adapter claimed this tree, so nothing about it is unverifiable: " + covered);

        // The adapter drives CMake and nothing else, so a tree built by a hand-written Makefile is exactly
        // what is left for the detector to speak for. Reporting it is the point of keeping the detector
        // registered rather than deleting it once an adapter existed.
        Path makeBuilt = project.resolve("make-built");
        write(makeBuilt, "Makefile", "all:\n\tc++ -o ledger src/ledger.cpp\n");
        write(makeBuilt, "src/ledger.cpp", "int main() { return 0; }\n");
        List<String> uncovered = detectors.detect(makeBuilt, Set.of()).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(uncovered.contains("C or C++"),
                "a C++ project the adapter does not claim is still named: " + uncovered);
    }

    @Test
    @DisplayName("a PHP project is verified rather than reported as present and unverifiable")
    void phpIsNotReportedTwice(@TempDir Path project) throws IOException {
        write(project, "composer.json", "{\"name\": \"example/ledger\"}\n");
        write(project, "src/Ledger.php", "<?php\n\nfinal class Ledger\n{\n}\n");
        LanguageDetectorRegistry detectors = new LanguageDetectorRegistry().discover();

        List<String> covered = detectors.detect(project, Set.of(LanguageId.PHP)).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(covered.isEmpty(),
                "the adapter claimed this tree, so nothing about it is unverifiable: " + covered);

        // Unlike C and C++, PHP has no build-system variant the adapter declines: it claims a tree that
        // holds PHP source or a PHP descriptor, and this detector's evidence is a subset of that, so there
        // is no PHP tree left for it to speak for. What it still exists for is a classpath without the
        // adapter, which is why it is kept registered rather than deleted.
        Path other = project.resolve("elixir");
        write(other, "mix.exs", "defmodule Ledger.MixProject do\nend\n");
        List<String> uncovered = detectors.detect(other, Set.of()).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(uncovered.contains("Elixir"),
                "a language no adapter claimed is still named: " + uncovered);
    }

    @Test
    @DisplayName("a Swift package is verified, and a CocoaPods tree is still reported as unverifiable")
    void swiftIsNotReportedTwice(@TempDir Path project) throws IOException {
        write(project, "Package.swift", """
                // swift-tools-version:5.9
                import PackageDescription
                let package = Package(name: "Ledger", targets: [.target(name: "Ledger")])
                """);
        write(project, "Sources/Ledger/Ledger.swift", "public struct Ledger {}\n");
        LanguageDetectorRegistry detectors = new LanguageDetectorRegistry().discover();

        List<String> covered = detectors.detect(project, Set.of(LanguageId.SWIFT)).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(covered.isEmpty(),
                "the adapter claimed this tree, so nothing about it is unverifiable: " + covered);

        // The adapter drives Swift Package Manager and nothing else, so a CocoaPods tree - recognised by
        // its Podfile and its .swift sources, with no Package.swift - is exactly what is left for the
        // detector to speak for. Reporting it is the point of keeping the detector registered rather than
        // deleting it once an adapter existed.
        Path cocoaPods = project.resolve("pods");
        write(cocoaPods, "Podfile", "platform :ios, '17.0'\n");
        write(cocoaPods, "Ledger/Ledger.swift", "public struct Ledger {}\n");
        List<String> uncovered = detectors.detect(cocoaPods, Set.of()).stream()
                .map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(uncovered.contains("Swift"),
                "a Swift tree the adapter does not claim is still named: " + uncovered);
    }

    private static void write(Path base, String relative, String content) throws IOException {        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}

package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code C# or F#} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code C# or F#} code does not have every verdict computed as though that code
 * were absent.
 */
public final class DotNetLanguageDetector extends DescriptorLanguageDetector {

    public DotNetLanguageDetector() {
        super("csharp", "C# or F#", List.of("global.json", "Directory.Build.props"), List.of(".csproj", ".fsproj", ".vbproj", ".sln", ".cs", ".fs"));
    }
}

package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises {@code C# or F#} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code C# or F#} code does not have every verdict computed as though that code
 * were absent.
 *
 * <p>C# itself is no longer in that position: {@code codewitness-adapter-csharp} claims C# projects and
 * reports them as {@code csharp}. Declaring that here means a tree the adapter verified is not described
 * a second time as present and unverifiable, which would contradict the rest of the report. What remains
 * on this path is the rest of the .NET family - F# and Visual Basic - which the adapter does not claim
 * because it cannot build, test or analyse those languages.
 *
 * <p>The declaration is all-or-nothing, which is the one case it gets wrong: a tree holding both C# and F#
 * is claimed by the adapter, so this detector is suppressed for it and the F# part goes unmentioned. That
 * is how the detector mechanism is defined rather than an accident of this detector, and the C# adapter's
 * own detection is deliberately narrow - it requires C# evidence such as a {@code .csproj} or a {@code .cs}
 * file - so an F# only tree is never claimed.
 */
public final class DotNetLanguageDetector extends DescriptorLanguageDetector {

    public DotNetLanguageDetector() {
        super("csharp", "C# or F#", List.of("global.json", "Directory.Build.props"), List.of(".csproj", ".fsproj", ".vbproj", ".sln", ".cs", ".fs"));
    }

    /** @return {@code csharp}, because an adapter for that language decides the verdicts itself. */
    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("csharp");
    }
}

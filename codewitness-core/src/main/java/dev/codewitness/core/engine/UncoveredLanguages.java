package dev.codewitness.core.engine;

import dev.codewitness.core.model.LanguageId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Names the languages a project contains that nothing in this run could verify.
 *
 * <p>This is the second half of supporting a polyglot tree. Running every adapter that claims the project
 * fixes the case where CodeWitness <em>can</em> verify both sides; it does nothing for a tree that contains
 * a language no adapter covers at all. A Java service with a Ruby build script would still have every
 * verdict computed as though the tree were Java, and the report would say nothing about the Ruby. Detecting
 * that and saying so is the difference between a report that is incomplete and one that is misleading.
 *
 * <p>The table below is a heuristic over well-known project descriptors, and it is deliberately only a
 * naming concern: it never schedules a phase, never produces evidence, and never influences a verdict. Its
 * whole output is a sentence in the limitations list. A language it fails to recognise is no worse off than
 * before - the report simply does not name it - so the cost of the list being incomplete is low and the
 * benefit of the common cases being caught is high.
 *
 * <p>Descriptors for languages CodeWitness does support are in the table too. They matter when the adapter
 * for that language is not on the classpath: the project still declares itself, and the report should say
 * that its language is present but was not verified rather than staying quiet.
 */
public final class UncoveredLanguages {

    /** How deep to look for a project file whose name is only recognisable by its extension. */
    private static final int MAX_DEPTH = 3;

    /**
     * Directories that do not hold the project's own code: dependencies, build output, and test inputs.
     *
     * <p>{@code fixtures} and {@code testdata} are in the list because a repository's sample projects are
     * inputs to its tests rather than code the run set out to verify. Naming them would be noise in the
     * common case, and noise in a limitations list is how the list stops being read.
     */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "target", "build", "dist", "vendor", ".git", ".venv", "venv", "__pycache__",
            "fixtures", "testdata");

    /**
     * One recognisable language.
     *
     * @param name       how to name the language in the report
     * @param coveredBy  the language id an adapter would report for it, when one exists
     * @param files      descriptor file names looked for at the project root
     * @param extensions file name suffixes looked for below the root
     */
    private record Language(String name, LanguageId coveredBy, Set<String> files, Set<String> extensions) {
    }

    private static final List<Language> LANGUAGES = List.of(
            new Language("Java", LanguageId.JAVA,
                    Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
                    Set.of()),
            new Language("Python", LanguageId.PYTHON,
                    Set.of("pyproject.toml", "requirements.txt", "setup.py", "setup.cfg", "Pipfile"),
                    Set.of(".py")),
            new Language("Rust", LanguageId.RUST, Set.of("Cargo.toml"), Set.of(".rs")),
            new Language("Go", LanguageId.GO, Set.of("go.mod"), Set.of(".go")),
            // package.json says "a Node project" but not which of the two languages it is written in, so
            // both ids count as covering it and the report keeps the ambiguity in the name.
            new Language("JavaScript or TypeScript", LanguageId.JAVASCRIPT, Set.of("package.json"),
                    Set.of(".js", ".mjs", ".cjs", ".ts", ".tsx")),
            new Language("Ruby", null, Set.of("Gemfile", "Rakefile", "config.ru"), Set.of(".rb", ".gemspec")),
            new Language("PHP", null, Set.of("composer.json", "artisan"), Set.of(".php")),
            new Language("C# or F#", null, Set.of("global.json"), Set.of(".csproj", ".fsproj", ".sln")),
            new Language("Scala", null, Set.of("build.sbt"), Set.of(".scala")),
            new Language("Kotlin", null, Set.of(), Set.of(".kt", ".kts")),
            new Language("Elixir", null, Set.of("mix.exs"), Set.of(".ex", ".exs")),
            new Language("Dart", null, Set.of("pubspec.yaml"), Set.of(".dart")),
            new Language("Swift", null, Set.of("Package.swift"), Set.of(".swift")),
            new Language("C or C++", null, Set.of("CMakeLists.txt", "meson.build"), Set.of(".cc", ".cpp", ".hpp")),
            new Language("Haskell", null, Set.of("stack.yaml", "cabal.project"), Set.of(".cabal", ".hs")),
            new Language("Clojure", null, Set.of("project.clj", "deps.edn"), Set.of(".clj", ".cljs")),
            new Language("Erlang", null, Set.of("rebar.config"), Set.of(".erl")),
            new Language("Perl", null, Set.of("cpanfile", "Makefile.PL"), Set.of(".pl", ".pm")),
            new Language("Lua", null, Set.of(), Set.of(".rockspec", ".lua")));

    private UncoveredLanguages() {
    }

    /** One language found in a project that the run had no adapter for. */
    public record Uncovered(String language, String evidence) {
    }

    /**
     * @param root     the root of the tree that was actually verified
     * @param verified the language ids the run's adapters reported
     * @return every recognisable language present in the tree that none of them verified, in table order
     */
    public static List<Uncovered> detect(Path root, Set<LanguageId> verified) {
        List<Uncovered> uncovered = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return uncovered;
        }
        Set<LanguageId> covered = verified == null ? Set.of() : verified;
        for (Language language : LANGUAGES) {
            if (language.coveredBy() != null && covered.contains(language.coveredBy())) {
                continue;
            }
            // A Node project is covered when either Node adapter ran, because package.json names neither.
            if (language.coveredBy() == LanguageId.JAVASCRIPT && covered.contains(LanguageId.TYPESCRIPT)) {
                continue;
            }
            String evidence = evidenceOf(language, root);
            if (evidence != null) {
                uncovered.add(new Uncovered(language.name(), evidence));
            }
        }
        return List.copyOf(uncovered);
    }

    /** @return the descriptor that gives this language away, or {@code null} when nothing does. */
    private static String evidenceOf(Language language, Path root) {
        for (String file : language.files()) {
            if (Files.isRegularFile(root.resolve(file))) {
                return file;
            }
        }
        if (language.extensions().isEmpty()) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> !ignored(path, root))
                    .filter(path -> hasExtension(path, language.extensions()))
                    .map(path -> root.relativize(path).toString())
                    .findFirst()
                    .orElse(null);
        } catch (IOException _) {
            // An unreadable tree yields no evidence, which only means the report names one language fewer.
            return null;
        }
    }

    private static boolean hasExtension(Path path, Set<String> extensions) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(name::endsWith);
    }

    private static boolean ignored(Path path, Path root) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (IGNORED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param uncovered languages the run could not verify
     * @param verified  the adapters that did run, for the sentence that says what the report does cover
     * @return one sentence per uncovered language, ready for the report's limitations list
     */
    public static List<String> limitations(List<Uncovered> uncovered, List<String> verified) {
        List<String> limitations = new ArrayList<>();
        for (Uncovered language : new LinkedHashSet<>(uncovered)) {
            limitations.add("This project also contains " + language.language() + " code (" + language.evidence()
                    + "), and no language adapter in this run understands it. Nothing written in "
                    + language.language() + " was built, tested or analysed, and the verdicts below cover "
                    + "only " + String.join(", ", verified) + ".");
        }
        return List.copyOf(limitations);
    }
}

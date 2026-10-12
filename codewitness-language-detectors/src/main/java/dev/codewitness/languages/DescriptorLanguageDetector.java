package dev.codewitness.languages;

import dev.codewitness.core.spi.LanguageDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Recognises a language from the files a project conventionally keeps for it.
 *
 * <p>A concrete detector says three things - the language's id, how to name it, and what gives it away -
 * and this class does the looking. That keeps each language to a few lines and makes the two ways a
 * language announces itself explicit: a descriptor at the project root ({@code Gemfile}, {@code
 * composer.json}, {@code build.sbt}) and a file name suffix anywhere below it ({@code .rb}, {@code .php}).
 *
 * <p>The scan is deliberately shallow and skips dependency, build-output and test-input directories. A
 * detector is a reporting aid: a language it fails to notice is no worse off than before, so the cost of
 * being conservative is low, while a false positive puts a sentence in every report about a repository that
 * does not contain the language at all. A language whose files conventionally live deeper than that - SQL
 * under a {@code src/main/resources} migration tree, for instance - can ask for a deeper scan through the
 * constructor that takes a depth, because the conservative default would miss the only place its files live.
 */
public abstract class DescriptorLanguageDetector implements LanguageDetector {

    /** How deep to look for a file that is only recognisable by its name. */
    private static final int MAX_DEPTH = 3;

    /**
     * Directories that do not hold the project's own code: dependencies, build output, and test inputs.
     *
     * <p>{@code fixtures} and {@code testdata} are in the list because a repository's sample projects are
     * inputs to its tests rather than code the run set out to verify. Naming them would be noise in the
     * common case, and noise in a limitations list is how the list stops being read.
     */
    static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "target", "build", "dist", "vendor", ".git", ".venv", "venv", "__pycache__",
            "fixtures", "testdata", "bower_components", "Pods");

    private final String id;
    private final String language;
    private final Set<String> descriptors;
    private final Set<String> suffixes;
    private final int maxDepth;

    /**
     * @param id          the language's identifier, used as its {@code LanguageId}
     * @param language    how the language is named in a report
     * @param descriptors file names looked for at the project root
     * @param suffixes    file name suffixes looked for below the root, each including the dot
     */
    protected DescriptorLanguageDetector(
            String id, String language, List<String> descriptors, List<String> suffixes) {
        this(id, language, descriptors, suffixes, MAX_DEPTH);
    }

    /**
     * @param maxDepth how deep below the root to look for a file recognised only by its suffix. Ignored
     *     directories are still skipped at every level, so a deeper scan stays bounded by the project's own
     *     tree rather than by whatever happens to be vendored inside it.
     */
    protected DescriptorLanguageDetector(
            String id, String language, List<String> descriptors, List<String> suffixes, int maxDepth) {
        this.id = id;
        this.language = language;
        this.descriptors = new LinkedHashSet<>(descriptors);
        this.suffixes = new LinkedHashSet<>(suffixes);
        this.maxDepth = maxDepth;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String language() {
        return language;
    }

    @Override
    public final Optional<String> evidence(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        for (String descriptor : descriptors) {
            if (Files.isRegularFile(root.resolve(descriptor))) {
                return Optional.of(descriptor);
            }
        }
        if (suffixes.isEmpty()) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> !ignored(root, path))
                    .filter(this::hasSuffix)
                    .map(path -> root.relativize(path).toString())
                    .findFirst();
        } catch (IOException _) {
            // An unreadable tree yields no evidence, which only means the report names one language fewer.
            return Optional.empty();
        }
    }

    private boolean hasSuffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return suffixes.stream().anyMatch(name::endsWith);
    }

    private static boolean ignored(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        for (Path element : relative) {
            if (IGNORED_DIRECTORIES.contains(element.toString())) {
                return true;
            }
        }
        return false;
    }
}

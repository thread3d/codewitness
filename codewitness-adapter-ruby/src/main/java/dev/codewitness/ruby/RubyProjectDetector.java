package dev.codewitness.ruby;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects Ruby projects and describes their layout.
 *
 * <p>Only files are read here; nothing is executed and no interpreter is needed. Detection is
 * deliberately conservative: a descriptor that cannot be read is reported through
 * {@link ProjectInfo#attributes()} rather than replaced by an invented module name or Ruby version, and
 * a tree without a descriptor is still claimed - it can be syntax-checked, tested and analysed, it
 * simply has nothing to install.
 *
 * <p>Ruby descriptors are not machine readable formats this adapter parses with a real parser. A
 * {@code Gemfile} is Ruby source and a {@code *.gemspec} is Ruby source, and evaluating either to learn
 * a name would execute the project before the run has decided to. Both are read as text with the few
 * line patterns below, which yields the facts a report needs or nothing at all - never a guess.
 *
 * <p>The one thing this detector cannot know is whether the project's tests can run: RSpec is a gem
 * that exists only after the build has run {@code bundle install}. The test framework recorded here is
 * what the project <em>declares</em>, and the test phase resolves the runner itself.
 */
public final class RubyProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";
    public static final String ATTR_RUBY_VERSION = "rubyVersion";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";

    /**
     * Descriptor files that sit before {@code *.gemspec} in the order descriptors are preferred.
     *
     * <p>{@code Gemfile} is first because it is the only one that also names the package manager, the
     * module and the interpreter the project asks for; the rest merely say how the project is laid out.
     */
    private static final List<String> DESCRIPTORS_BEFORE_GEMSPEC = List.of("Gemfile", "Gemfile.lock");

    /** Descriptor files that sit after {@code *.gemspec} in the order descriptors are preferred. */
    private static final List<String> DESCRIPTORS_AFTER_GEMSPEC =
            List.of(".ruby-version", "Rakefile", "config.ru");

    /** File extensions, without the dot, this adapter treats as Ruby source. */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of("rb", "rake", "gemspec");

    /** The same extensions as a single attribute value, in the order a report should read them. */
    private static final String SOURCE_EXTENSIONS_VALUE = "rb,rake,gemspec";

    /**
     * Directories never treated as source, relative to the walked root.
     *
     * <p>The check is relative to the root rather than absolute, exactly as the PHP and Python
     * detectors' is: a run's isolated workspace lives under the project's {@code .codewitness}
     * directory, so an absolute check would reject every file in the workspace and silently find no
     * source at all.
     *
     * <p>{@code coverage}, {@code tmp}, {@code log}, {@code .bundle} and {@code .yardoc} are on the list
     * because they are where Ruby tooling writes: a coverage result set or a Bundler config is not
     * project source, and analysing one would attribute a tool's output to the project.
     */
    private static final Set<String> EXCLUDED = Set.of(
            "vendor", "node_modules", "build", "dist", ".git", ".codewitness",
            ".bundle", "coverage", "tmp", "log", ".rspec_status", ".yardoc");

    /** Candidates for the directory the project's own sources live in, in preference order. */
    private static final List<String> SOURCE_ROOTS = List.of("lib", "app", "src");

    /** Candidates for a test directory, in preference order. */
    private static final List<String> TEST_ROOTS = List.of("spec", "test");

    /**
     * The first version a requirement names, with every component it carries.
     *
     * <p>Ruby releases are three-component by convention ({@code 3.3.6}), so a pattern that stopped at the
     * second would report a pinned {@code 3.3.6} as {@code 3.3} and make two different interpreters the
     * same fact. Nothing longer than a version is captured because a requirement's own operators and
     * separators ({@code ~>}, {@code >=}, {@code ,}) are not digits.
     */
    private static final Pattern VERSION_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)*)");

    /** {@code ruby '3.3.0'} in a Gemfile; the same line may carry a constraint such as {@code '~> 3.3'}. */
    private static final Pattern GEMFILE_RUBY =
            Pattern.compile("^\\s*ruby\\s+[\"']([^\"']+)[\"']", Pattern.MULTILINE);

    /** {@code spec.name = "ledger"} or {@code s.name = 'ledger'} in a gemspec. */
    private static final Pattern GEMSPEC_NAME =
            Pattern.compile("(?m)^\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\.)?name\\s*=\\s*[\"']([^\"']+)[\"']");

    /** {@code spec.required_ruby_version = ">= 3.1"} in a gemspec. */
    private static final Pattern GEMSPEC_REQUIRED_RUBY = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*\\.required_ruby_version\\s*=\\s*[\"']([^\"']+)[\"']");

    private RubyProjectDetector() {
    }

    /** @return {@code true} when the directory looks like a Ruby project this adapter can handle. */
    public static boolean looksLikeRubyProject(Path root) {
        if (!descriptorFiles(root).isEmpty()) {
            return true;
        }
        return containsRubySource(root, 3);
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.RUBY)
                .sourceExtensions(SOURCE_EXTENSIONS)
                .attribute(ATTR_SOURCE_EXTENSIONS, SOURCE_EXTENSIONS_VALUE);

        List<Path> descriptors = descriptorFiles(root);
        for (Path descriptor : descriptors) {
            builder.addDetectedDescriptor(descriptor);
        }
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none")
                    .attribute(ATTR_DETECTION_WARNING,
                            "no Ruby build descriptor (Gemfile, Gemfile.lock, *.gemspec, .ruby-version, "
                                    + "Rakefile, config.ru) found; the project can be syntax-checked, tested "
                                    + "and analysed but its dependencies cannot be installed");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors.stream()
                    .map(descriptor -> descriptor.getFileName().toString())
                    .toList()));
        }
        boolean hasGemfile = Files.isRegularFile(root.resolve("Gemfile"));
        builder.buildSystem(hasGemfile ? BuildSystemId.BUNDLER : BuildSystemId.NONE);
        // Bundler is the only package manager Ruby projects use in practice, so a tree without a Gemfile
        // has no package manager at all rather than a default one invented for it.
        builder.attribute(ATTR_PACKAGE_MANAGER, hasGemfile ? "bundler" : "unknown");

        DescriptorText gemfile = readText(root.resolve("Gemfile"));
        recordWarning(builder, gemfile);
        DescriptorText rubyVersionFile = readText(root.resolve(".ruby-version"));
        recordWarning(builder, rubyVersionFile);
        Path gemspec = firstGemspec(root);
        DescriptorText gemspecText = gemspec == null ? new DescriptorText(null, null) : readText(gemspec);
        recordWarning(builder, gemspecText);

        // Three descriptors can state which interpreter the project wants, in decreasing order of how
        // much the build is bound by them: the Gemfile's `ruby` line is the request Bundler itself acts
        // on, the `.ruby-version` file is the interpreter a version manager would select, and a gemspec's
        // `required_ruby_version` is a constraint the gem merely tolerates. The first one present wins;
        // the attribute is a hint that the probed interpreter's own version replaces later anyway.
        String rubyVersion = gemfileRubyVersion(gemfile.text());
        if (rubyVersion == null) {
            rubyVersion = pinnedRubyVersion(rubyVersionFile.text());
        }
        if (rubyVersion == null) {
            rubyVersion = gemspecRubyVersion(gemspecText.text());
        }
        if (rubyVersion != null) {
            builder.attribute(ATTR_RUBY_VERSION, rubyVersion);
        }

        String declaredName = gemspecName(gemspecText.text());
        String name = declaredName == null ? project.name() : moduleName(declaredName);
        List<Path> testDirs = detectTestDirs(root);
        builder.addModule(new ModuleInfo(name, root, "ruby", detectSourceDirs(root), testDirs, null));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testDirs.isEmpty()));

        String framework = detectTestFramework(root, gemfile.text(), gemspecText.text());
        if (!"none".equals(framework)) {
            builder.addTestFramework(framework);
        }
        builder.attribute(ATTR_TEST_FRAMEWORK, framework);
        return builder.build();
    }

    /**
     * @return the gem name without a namespace prefix, because that is what a human and a report mean by
     *     "the project". A gem name is normally already bare, so this is the identity for it; stripping
     *     the separator anyway keeps a name read from an unusual descriptor from carrying a directory
     *     into a module name.
     */
    static String moduleName(String gemName) {
        int slash = gemName.indexOf('/');
        return slash < 0 ? gemName : gemName.substring(slash + 1);
    }

    /** @return {@code lib}, {@code app} or {@code src} when it holds Ruby sources, otherwise the root. */
    private static List<Path> detectSourceDirs(Path root) {
        for (String candidate : SOURCE_ROOTS) {
            Path directory = root.resolve(candidate);
            if (Files.isDirectory(directory) && containsRubySource(directory, 8)) {
                return List.of(directory);
            }
        }
        // A gem that predates the conventional layout keeps its sources at the root, next to the
        // gemspec, exactly where a developer's `require` finds them.
        return List.of(root);
    }

    /** @return the test roots {@code spec} or {@code test} when they hold Ruby source. */
    private static List<Path> detectTestDirs(Path root) {
        List<Path> result = new ArrayList<>();
        for (String candidate : TEST_ROOTS) {
            Path directory = root.resolve(candidate);
            if (Files.isDirectory(directory) && containsRubySource(directory, 8)) {
                result.add(directory);
            }
        }
        return List.copyOf(result);
    }

    /**
     * @return {@code rspec} or {@code minitest} when the project declares one, otherwise {@code none}.
     *
     * <p>RSpec is preferred when both are mentioned: it is the framework the brief's report shape and the
     * coverage result set are built around, and a project that keeps a {@code spec/} directory beside a
     * {@code test/} one is asked for its RSpec report. Only minitest is a fallback, and a project with
     * neither declaration simply has no tests this adapter can honestly claim to be able to run.
     */
    private static String detectTestFramework(Path root, String gemfile, String gemspec) {
        if (Files.isRegularFile(root.resolve(".rspec"))
                || holdsRubySource(root.resolve("spec"))
                || mentions(gemfile, "rspec")
                || mentions(gemspec, "rspec")) {
            return "rspec";
        }
        if (holdsRubySource(root.resolve("test"))
                || Files.isRegularFile(root.resolve("Rakefile"))
                || mentions(gemfile, "minitest")) {
            return "minitest";
        }
        return "none";
    }

    /** @return {@code true} when the text mentions a gem; a descriptor that could not be read says nothing. */
    private static boolean mentions(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * @return the Ruby files below a root, sorted, skipping the bundle tree and the tool output
     *     directories.
     *
     * <p>Dependency code is somebody else's source: analysing it would attribute another project's
     * decisions to this one, and checking its syntax would report a gem's incompatibility as this
     * project's build failure.
     */
    public static List<Path> rubyFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(RubyProjectDetector::isRubySourceName)
                    .filter(path -> !isExcludedSource(root, path))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /** @return {@code true} when an element below {@code root} is the bundle tree, tool output or VCS data. */
    public static boolean isExcludedSource(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        for (Path element : relative) {
            if (EXCLUDED.contains(element.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the descriptor files of the project, in preference order.
     *
     * <p>Any file ending in {@code .gemspec} at the root counts, so the list is built from a directory
     * listing rather than from a fixed name: a gem is as likely to be called {@code ledger.gemspec} as
     * {@code my_gem.gemspec}, and a hard-coded name would miss both.
     */
    static List<Path> descriptorFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null) {
            return files;
        }
        for (String name : DESCRIPTORS_BEFORE_GEMSPEC) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        files.addAll(gemspecs(root));
        for (String name : DESCRIPTORS_AFTER_GEMSPEC) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        return files;
    }

    /** @return every {@code *.gemspec} at the root, sorted by name so one tree always reads the same. */
    static List<Path> gemspecs(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gemspec"))
                    .sorted()
                    .toList();
        } catch (IOException _) {
            return List.of();
        }
    }

    /** @return the first gemspec in name order, which is the one whose name will name the module. */
    private static Path firstGemspec(Path root) {
        List<Path> gemspecs = gemspecs(root);
        return gemspecs.isEmpty() ? null : gemspecs.getFirst();
    }

    /** @return {@code true} when a shallow walk finds a Ruby source file below {@code root}. */
    private static boolean containsRubySource(Path root, int maxDepth) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            return walk.anyMatch(path -> Files.isRegularFile(path)
                    && isRubySourceName(path)
                    && !isExcludedSource(root, path));
        } catch (IOException _) {
            return false;
        }
    }

    /** @return {@code true} when the directory exists and holds Ruby source. */
    private static boolean holdsRubySource(Path directory) {
        return Files.isDirectory(directory) && containsRubySource(directory, 8);
    }

    private static boolean isRubySourceName(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".rb") || name.endsWith(".rake") || name.endsWith(".gemspec");
    }

    /** @return the version the Gemfile's {@code ruby} line asks for, or {@code null} when it has none. */
    private static String gemfileRubyVersion(String gemfile) {
        if (gemfile == null) {
            return null;
        }
        Matcher matcher = GEMFILE_RUBY.matcher(gemfile);
        return matcher.find() ? firstVersionNumber(matcher.group(1)) : null;
    }

    /** @return the version a gemspec's {@code required_ruby_version} asks for, or {@code null}. */
    private static String gemspecRubyVersion(String gemspec) {
        if (gemspec == null) {
            return null;
        }
        Matcher matcher = GEMSPEC_REQUIRED_RUBY.matcher(gemspec);
        return matcher.find() ? firstVersionNumber(matcher.group(1)) : null;
    }

    /**
     * @return the version a {@code .ruby-version} file pins, or {@code null}.
     *
     * <p>The file holds one version per line, optionally prefixed by the implementation - {@code 3.3.6} or
     * {@code ruby-3.3.6}. The first non-blank line is the one a version manager would select.
     */
    private static String pinnedRubyVersion(String rubyVersionFile) {
        if (rubyVersionFile == null) {
            return null;
        }
        for (String line : rubyVersionFile.split("\\R")) {
            if (!line.isBlank()) {
                return firstVersionNumber(line.trim());
            }
        }
        return null;
    }

    /** @return the gem name a gemspec declares, or {@code null} when no pattern matches it. */
    static String gemspecName(String gemspec) {
        if (gemspec == null) {
            return null;
        }
        Matcher matcher = GEMSPEC_NAME.matcher(gemspec);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstVersionNumber(String requirement) {
        Matcher matcher = VERSION_NUMBER.matcher(requirement);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** @return the file contents, or the empty string when the file does not exist or cannot be read. */
    public static String readString(Path file) {
        try {
            return file != null && Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }

    /**
     * Reads a descriptor and keeps the difference between "there is nothing" and "it could not be read".
     *
     * <p>{@link #readString} collapses both to the empty string, which is the right answer for a caller
     * that only wants text. The detector needs the warning, because a descriptor that exists and cannot
     * be read is a fact about the run - a permissions problem, a broken checkout - that a report must
     * carry rather than silently describe the project as if the file were absent.
     */
    private static DescriptorText readText(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new DescriptorText(null, null);
        }
        try {
            return new DescriptorText(Files.readString(file, StandardCharsets.UTF_8), null);
        } catch (IOException e) {
            return new DescriptorText(null, file.getFileName() + " exists but could not be read ("
                    + e.getClass().getSimpleName() + "); the project was described from its file tree instead");
        }
    }

    private static void recordWarning(ProjectInfo.Builder builder, DescriptorText descriptor) {
        if (descriptor.warning() != null) {
            builder.attribute(ATTR_DETECTION_WARNING, descriptor.warning());
        }
    }

    /**
     * What a descriptor said, or why it could not be read.
     *
     * @param text    the file contents, or {@code null} when it does not exist or could not be read
     * @param warning why it could not be read, or {@code null}
     */
    private record DescriptorText(String text, String warning) {
    }
}

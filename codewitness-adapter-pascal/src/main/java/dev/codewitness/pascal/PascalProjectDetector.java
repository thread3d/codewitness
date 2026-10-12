package dev.codewitness.pascal;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Recognises a Pascal project and describes it without compiling anything.
 *
 * <p>Pascal has no single manifest every project shares, so detection is driven by the sources
 * themselves: any file with a Pascal suffix makes the tree a Pascal project, and the files that declare
 * a {@code program} or {@code library} are the executables the compiler can link. Everything else is a
 * unit, which the adapter can compile as a first-failure check but not run.
 *
 * <p>Test discovery is the same shape: a source is a test when it uses the fpcunit framework or when its
 * name or directory says so. A tree of test units with no runner is still testable, because the adapter
 * can synthesize a runner over them, so their presence is recorded rather than their being reachable.
 */
public final class PascalProjectDetector {

    /** Project attributes written by this class. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";
    public static final String ATTR_PROGRAMS = "programs";
    public static final String ATTR_MODULES = "modules";
    public static final String ATTR_TESTS = "tests";
    public static final String ATTR_HAS_TESTS = "hasTests";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_MODULE_COUNT = "moduleCount";
    public static final String ATTR_TOP_PROGRAM = "topProgram";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";

    /** The framework the adapter drives, which ships with Free Pascal itself. */
    public static final String TEST_FRAMEWORK = "fpcunit";

    /** File extensions treated as Pascal source, without the leading dot. */
    public static final Set<String> SOURCE_EXTENSIONS = Set.of("pas", "pp", "lpr", "dpr", "p");

    private PascalProjectDetector() {
    }

    /** @return {@code true} when the tree holds at least one Pascal source. */
    public static boolean looksLikePascalProject(Path root) {
        return !sourceFiles(root).isEmpty();
    }

    /**
     * Describes the project.
     *
     * @param project the project to inspect; its root is read, never executed
     * @return the detected description, with one module covering the tree
     */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        List<Path> sources = sourceFiles(root);
        List<PascalModule> modules = modules(sources);
        List<PascalModule> programs = modules.stream().filter(PascalModule::program).toList();
        List<PascalModule> tests = modules.stream().filter(PascalModule::test).toList();

        Set<String> unitNames = new TreeSet<>();
        for (PascalModule module : modules) {
            unitNames.add(module.name());
        }
        Set<String> programNames = new TreeSet<>();
        for (PascalModule program : programs) {
            programNames.add(program.name());
        }
        Set<String> testNames = new TreeSet<>();
        for (PascalModule test : tests) {
            testNames.add(test.name());
        }

        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.PASCAL)
                .buildSystem(BuildSystemId.FPC)
                .sourceExtensions(SOURCE_EXTENSIONS)
                .attribute(ATTR_DESCRIPTOR, "pascal")
                .attribute(ATTR_SOURCE_EXTENSIONS, String.join(",", new TreeSet<>(SOURCE_EXTENSIONS)))
                .attribute(ATTR_MODULES, String.join(",", unitNames))
                .attribute(ATTR_PROGRAMS, String.join(",", programNames))
                .attribute(ATTR_TESTS, String.join(",", testNames))
                .attribute(ATTR_MODULE_COUNT, String.valueOf(modules.size()));

        if (!tests.isEmpty()) {
            builder.addTestFramework(TEST_FRAMEWORK);
            builder.attribute(ATTR_HAS_TESTS, "true");
            builder.attribute(ATTR_TEST_FRAMEWORK, TEST_FRAMEWORK);
        } else {
            builder.attribute(ATTR_HAS_TESTS, "false");
        }
        if (!programs.isEmpty()) {
            builder.attribute(ATTR_TOP_PROGRAM, programs.getFirst().name());
        } else if (modules.isEmpty()) {
            builder.attribute(ATTR_DETECTION_WARNING,
                    "no Pascal compilation unit was found; only files whose suffix looks like Pascal are present");
        }

        for (Path descriptor : sourceFiles(root)) {
            builder.addDetectedDescriptor(descriptor);
        }

        List<Path> sourceDirs = distinctParents(sources);
        List<Path> testDirs = distinctParents(tests.stream().map(PascalModule::file).toList());
        if (sourceDirs.isEmpty()) {
            sourceDirs = List.of(root);
        }
        String moduleName = programs.isEmpty() ? project.name() : programs.getFirst().name();
        builder.addModule(new ModuleInfo(moduleName, root, programs.isEmpty() ? "unit" : "program",
                sourceDirs, testDirs, null));
        return builder.build();
    }

    /** @return every Pascal source file under {@code root}, sorted by path. */
    public static List<Path> sourceFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(PascalProjectDetector::isSourceFile)
                    .filter(path -> !isExcluded(root, path))
                    .forEach(files::add);
        } catch (IOException _) {
            // An unreadable subtree yields the files that were readable, which only narrows the claim.
        }
        files.sort((a, b) -> a.toString().compareTo(b.toString()));
        return List.copyOf(files);
    }

    /** @return the parsed compilation units of the project, in path order. */
    public static List<PascalModule> modules(Path root) {
        return modules(sourceFiles(root));
    }

    private static List<PascalModule> modules(List<Path> files) {
        List<PascalModule> modules = new ArrayList<>();
        for (Path file : files) {
            modules.add(PascalSourceParser.parse(file));
        }
        return List.copyOf(modules);
    }

    /** @return the sources that declare a {@code program} or {@code library}. */
    public static List<PascalModule> programs(Path root) {
        return modules(root).stream().filter(PascalModule::program).toList();
    }

    /** @return the sources that belong to the test suite. */
    public static List<PascalModule> tests(Path root) {
        return modules(root).stream().filter(PascalModule::test).toList();
    }

    /** @return the sources that are units rather than programs or tests. */
    public static List<PascalModule> units(Path root) {
        return modules(root).stream().filter(module -> !module.program() && !module.test()).toList();
    }

    /** @return the first program, which is what a compile without an explicit target picks. */
    public static PascalModule topProgram(Path root) {
        List<PascalModule> programs = programs(root);
        return programs.isEmpty() ? null : programs.getFirst();
    }

    /** @return the directories the compiler must search for units, in a stable order. */
    public static List<Path> sourceDirectories(Path root) {
        LinkedHashSet<Path> directories = new LinkedHashSet<>();
        for (Path source : sourceFiles(root)) {
            Path parent = source.getParent();
            if (parent != null) {
                directories.add(parent);
            }
        }
        directories.add(root);
        return List.copyOf(directories);
    }

    /** @return {@code true} when the file name carries one of {@link #SOURCE_EXTENSIONS}. */
    public static boolean isSourceFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SOURCE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * @return {@code true} when the path lies in build output, a dependency tree or the run's own
     *     workspace, and so is not the project's own source
     */
    static boolean isExcluded(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        for (Path element : relative) {
            if (EXCLUDED_DIRECTORIES.contains(element.toString())) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".codewitness", "node_modules", "target", "build", "out", "dist", ".idea", ".vscode",
            "venv", ".venv");

    private static List<Path> distinctParents(List<Path> files) {
        LinkedHashSet<Path> parents = new LinkedHashSet<>();
        for (Path file : files) {
            Path parent = file.getParent();
            if (parent != null) {
                parents.add(parent);
            }
        }
        return List.copyOf(parents);
    }

    /** @return the file's text, or an empty string when it cannot be read. */
    public static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }
}

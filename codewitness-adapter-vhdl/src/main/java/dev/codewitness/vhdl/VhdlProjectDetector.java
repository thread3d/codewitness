package dev.codewitness.vhdl;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Detects VHDL projects and describes their design and testbench layout.
 *
 * <p>Only files are read here; nothing is analysed, elaborated or executed. VHDL has no manifest and
 * no package manager, so detection is the source tree itself: every {@code .vhd} or {@code .vhdl} file
 * is a source, and an entity declared without a port clause is a testbench. The one build descriptor
 * the language itself defines - the design unit the tool is pointed at - is read implicitly by
 * following entity declarations; no separate file list is required, and a directory of sources is an
 * analyzable design.
 *
 * <p>The tree is split into the design under test and its testbenches. The design is the first entity
 * with ports that is not a testbench; its name is the unit {@code ghdl -e} elaborates. The testbenches
 * are the entities the test phase simulates, one at a time. A tree with sources but no testbench is
 * still claimed, analysed and analysed, and its requirements stay UNVERIFIED with the reason that
 * nothing was executed, which is the honest report rather than a silent pass.
 */
public final class VhdlProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";
    public static final String ATTR_TOP_ENTITY = "topEntity";
    public static final String ATTR_ENTITIES = "entities";
    public static final String ATTR_TESTBENCHES = "testbenches";
    public static final String ATTR_HAS_TESTS = "hasTestbenches";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_ENTITY_COUNT = "entityCount";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";

    /** Source extensions, without the dot, that this adapter treats as VHDL sources. */
    public static final Set<String> SOURCE_EXTENSIONS = Set.of("vhd", "vhdl");

    private VhdlProjectDetector() {
    }

    /** @return {@code true} when the directory holds at least one VHDL source file. */
    public static boolean looksLikeVhdlProject(Path root) {
        return !sourceFiles(root).isEmpty();
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.VHDL)
                .buildSystem(BuildSystemId.GHDL)
                .sourceExtensions(SOURCE_EXTENSIONS)
                .attribute(ATTR_SOURCE_EXTENSIONS, String.join(",", new java.util.TreeSet<>(SOURCE_EXTENSIONS)));
        builder.addTestFramework("vhdl-testbench");
        builder.attribute(ATTR_TEST_FRAMEWORK, "vhdl-testbench");

        List<Path> sources = sourceFiles(root);
        List<VhdlDesignUnit> units = entities(sources);
        List<VhdlDesignUnit> testbenches = units.stream().filter(VhdlDesignUnit::testbench).toList();
        List<VhdlDesignUnit> design = units.stream()
                .filter(unit -> !unit.testbench())
                .filter(VhdlDesignUnit::hasPorts)
                .toList();

        // VHDL's own build descriptor is the design unit the tool is pointed at; nothing on disk names
        // it, so the report says so rather than pointing at a manifest that does not exist.
        builder.attribute(ATTR_DESCRIPTOR, "vhdl design units");
        builder.attribute(ATTR_ENTITY_COUNT, String.valueOf(units.size()));

        if (units.isEmpty()) {
            builder.attribute(ATTR_DETECTION_WARNING,
                    "no entity declaration was found; the tree holds VHDL files that do not declare an "
                            + "entity, so nothing can be analysed or simulated");
        }
        if (!design.isEmpty()) {
            builder.attribute(ATTR_TOP_ENTITY, design.getFirst().name());
        }
        builder.attribute(ATTR_ENTITIES,
                String.join(",", units.stream().map(VhdlDesignUnit::name).distinct().toList()));
        builder.attribute(ATTR_TESTBENCHES,
                String.join(",", testbenches.stream().map(VhdlDesignUnit::name).distinct().toList()));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testbenches.isEmpty()));

        List<Path> sourceDirs = distinctParents(sources);
        List<Path> testDirs = distinctParents(testbenches.stream().map(VhdlDesignUnit::file).toList());
        if (sourceDirs.isEmpty()) {
            sourceDirs = List.of(root);
        }
        if (testDirs.isEmpty()) {
            testDirs = List.of();
        }
        String name = design.isEmpty() ? project.name() : design.getFirst().name();
        builder.addModule(new ModuleInfo(name, root, "design", sourceDirs, testDirs, null));
        return builder.build();
    }

    /** @return the entity declarations found under a root, in file then declaration order. */
    public static List<VhdlDesignUnit> entities(Path root) {
        return entities(sourceFiles(root));
    }

    private static List<VhdlDesignUnit> entities(List<Path> files) {
        List<VhdlDesignUnit> units = new ArrayList<>();
        for (Path file : files) {
            units.addAll(VhdlSourceParser.parse(file));
        }
        return List.copyOf(units);
    }

    /** @return the testbench entities under a root, in file then declaration order. */
    public static List<VhdlDesignUnit> testbenches(Path root) {
        return entities(root).stream().filter(VhdlDesignUnit::testbench).toList();
    }

    /**
     * @return the design-under-test entity: the first entity with ports that is not a testbench, or
     *     {@code null} when the tree declares none.
     */
    public static VhdlDesignUnit topEntity(Path root) {
        for (VhdlDesignUnit unit : entities(root)) {
            if (!unit.testbench() && unit.hasPorts()) {
                return unit;
            }
        }
        return null;
    }

    /** @return every VHDL source file below a root, sorted, skipping build output and VCS metadata. */
    public static List<Path> sourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(VhdlProjectDetector::isSourceFile)
                    .filter(path -> !isExcluded(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return List.copyOf(files);
    }

    /** @return {@code true} when the file name carries one of the VHDL source extensions. */
    public static boolean isSourceFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SOURCE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static List<Path> distinctParents(List<Path> files) {
        Set<Path> dirs = new LinkedHashSet<>();
        for (Path file : files) {
            Path parent = file.getParent();
            if (parent != null) {
                dirs.add(parent);
            }
        }
        return List.copyOf(dirs);
    }

    /**
     * @return {@code true} when a path element below the walked root is build output, a cache, a
     *     dependency tree or a VCS directory.
     *
     * <p>The check is relative to the root rather than absolute: a run's isolated workspace lives
     * under the project's {@code .codewitness} directory, and an absolute check would reject every
     * file in the workspace and find no source at all.
     */
    static boolean isExcluded(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        Set<String> excluded = Set.of(
                ".git", ".codewitness", "obj_dir", "node_modules", "target", "build", "out", "dist",
                ".idea", ".vscode", "venv", ".venv", "work", "ghdl");
        for (Path element : relative) {
            if (excluded.contains(element.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String readString(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException _) {
            return "";
        }
    }
}

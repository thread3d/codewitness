package dev.codewitness.verilog;

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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Detects Verilog and SystemVerilog projects and describes their design and testbench layout.
 *
 * <p>Only files are read here; nothing is compiled, elaborated or executed. Verilog has no manifest
 * and no package manager, so detection is the source tree itself: every {@code .v}, {@code .sv},
 * {@code .vh} and {@code .svh} file is a source, and a module declared without ports whose body
 * contains a simulation control task - or a file that follows a testbench naming convention - is a
 * testbench. The one build descriptor the toolchain itself defines, the file list ({@code -f}), is
 * recognised and reported, but it is not required: a directory of sources is a buildable design.
 *
 * <p>The tree is split into the design under test and its testbenches. The design is the first module
 * with ports that is not a testbench; its name is the root {@code iverilog -s} selects. The
 * testbenches are the roots the test phase simulates, one at a time. A tree with sources but no
 * testbench is still claimed, built and analysed, and its requirements stay UNVERIFIED with the
 * reason that nothing was executed, which is the honest report rather than a silent pass.
 */
public final class VerilogProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";
    public static final String ATTR_TOP_MODULE = "topModule";
    public static final String ATTR_MODULES = "modules";
    public static final String ATTR_TESTBENCHES = "testbenches";
    public static final String ATTR_HAS_TESTS = "hasTestbenches";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_MODULE_COUNT = "moduleCount";
    public static final String ATTR_FILE_LISTS = "fileLists";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";

    /** Source extensions, without the dot, that this adapter treats as Verilog sources. */
    public static final Set<String> SOURCE_EXTENSIONS = Set.of("v", "sv", "vh", "svh");

    /** The file list extension the Icarus and Verilator command lines both understand. */
    private static final String FILE_LIST_EXTENSION = ".f";

    private VerilogProjectDetector() {
    }

    /** @return {@code true} when the directory holds at least one Verilog source file. */
    public static boolean looksLikeVerilogProject(Path root) {
        return !sourceFiles(root).isEmpty();
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.VERILOG)
                .buildSystem(BuildSystemId.ICARUS)
                .sourceExtensions(SOURCE_EXTENSIONS)
                .attribute(ATTR_SOURCE_EXTENSIONS, String.join(",", new java.util.TreeSet<>(SOURCE_EXTENSIONS)));
        builder.addTestFramework("verilog-testbench");
        builder.attribute(ATTR_TEST_FRAMEWORK, "verilog-testbench");

        List<Path> sources = sourceFiles(root);
        List<VerilogModule> modules = modules(sources);
        List<VerilogModule> testbenches = modules.stream().filter(VerilogModule::testbench).toList();
        List<VerilogModule> design = modules.stream()
                .filter(module -> !module.testbench())
                .filter(VerilogModule::hasPorts)
                .toList();

        List<String> descriptors = new ArrayList<>();
        for (Path fileList : fileLists(root)) {
            descriptors.add(root.relativize(fileList).toString());
            builder.addDetectedDescriptor(fileList);
        }
        builder.attribute(ATTR_DESCRIPTOR, descriptors.isEmpty() ? "verilog sources" : String.join(",", descriptors));
        builder.attribute(ATTR_FILE_LISTS, String.join(",", descriptors));
        builder.attribute(ATTR_MODULE_COUNT, String.valueOf(modules.size()));

        if (modules.isEmpty()) {
            builder.attribute(ATTR_DETECTION_WARNING,
                    "no module declaration was found; the tree holds Verilog files that do not declare a "
                            + "module, so nothing can be compiled or simulated");
        }
        if (!design.isEmpty()) {
            VerilogModule top = design.getFirst();
            builder.attribute(ATTR_TOP_MODULE, top.name());
            builder.attribute(ATTR_MODULES,
                    String.join(",", modules.stream().map(VerilogModule::name).distinct().toList()));
        } else if (!modules.isEmpty()) {
            builder.attribute(ATTR_MODULES,
                    String.join(",", modules.stream().map(VerilogModule::name).distinct().toList()));
        }
        builder.attribute(ATTR_TESTBENCHES,
                String.join(",", testbenches.stream().map(VerilogModule::name).distinct().toList()));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testbenches.isEmpty()));

        List<Path> sourceDirs = distinctParents(sources);
        List<Path> testDirs = distinctParents(testbenches.stream().map(VerilogModule::file).toList());
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

    /** @return the module declarations found under a root, in file then declaration order. */
    public static List<VerilogModule> modules(Path root) {
        return modules(sourceFiles(root));
    }

    private static List<VerilogModule> modules(List<Path> files) {
        List<VerilogModule> modules = new ArrayList<>();
        for (Path file : files) {
            modules.addAll(VerilogModuleParser.parse(file));
        }
        return List.copyOf(modules);
    }

    /** @return the testbench modules under a root, in file then declaration order. */
    public static List<VerilogModule> testbenches(Path root) {
        return modules(root).stream().filter(VerilogModule::testbench).toList();
    }

    /**
     * @return the design-under-test module: the first module with ports that is not a testbench, or
     *     {@code null} when the tree declares none.
     */
    public static VerilogModule topModule(Path root) {
        for (VerilogModule module : modules(root)) {
            if (!module.testbench() && module.hasPorts()) {
                return module;
            }
        }
        return null;
    }

    /** @return every Verilog source file below a root, sorted, skipping build output and VCS metadata. */
    public static List<Path> sourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(VerilogProjectDetector::isSourceFile)
                    .filter(path -> !isExcluded(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return List.copyOf(files);
    }

    /** @return the file list ({@code .f}) descriptors below a root, sorted. */
    public static List<Path> fileLists(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(FILE_LIST_EXTENSION))
                    .filter(path -> !isExcluded(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException _) {
            // no descriptors is a fact the detector reports, not an error
        }
        return List.copyOf(files);
    }

    /** @return {@code true} when the file name carries one of the Verilog source extensions. */
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
                ".idea", ".vscode", "venv", ".venv");
        for (Path element : relative) {
            if (excluded.contains(element.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String readString(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }
}

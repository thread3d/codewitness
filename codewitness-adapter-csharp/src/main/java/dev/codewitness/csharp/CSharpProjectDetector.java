package dev.codewitness.csharp;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects C# projects and describes how the .NET SDK will build them.
 *
 * <p>Only files are read here; nothing is executed. A .NET tree announces itself in one of three
 * ways, and all three are recognised: a solution file, one or more projects, or - when a tree holds
 * C# sources but no project file at all - nothing but {@code .cs} files. The third case is reported
 * with a warning, exactly as Go's module-less case is: the sources can be analysed but there is no
 * project for the SDK to build, so nothing can be compiled, tested or mutated.
 *
 * <p>The unit of build and of testing is the project, not the tree. That matters twice: the build
 * target list is the projects (or the solution that lists them), and a test project's own directory
 * is the test source root, so the requirement mapper reads tests only where tests live rather than
 * treating a production project's directory as a test tree.
 *
 * <p>Which projects are test projects is decided from the project file: a project is a test project
 * when it references {@code Microsoft.NET.Test.Sdk} (or the newer {@code Microsoft.Testing.Platform})
 * or declares {@code IsTestProject}. A project that merely references a test framework without a test
 * host is a library, and running the test host over it would report "no tests" for a project that was
 * never meant to have any.
 */
public final class CSharpProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_SOLUTION = "solution";
    public static final String ATTR_PROJECTS = "projectCount";
    public static final String ATTR_TEST_PROJECTS = "testProjectCount";
    public static final String ATTR_TARGET_FRAMEWORK = "targetFramework";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_SOURCE_EXTENSIONS = "sourceExtensions";
    public static final String ATTR_PACKAGE_MANAGER = "packageManager";
    public static final String ATTR_NAMESPACES = "namespaces";

    /** Descriptor files that identify a .NET project, in the order they are preferred. */
    private static final List<String> ROOT_DESCRIPTORS = List.of(
            "global.json", "Directory.Build.props", "Directory.Packages.props", "Directory.Build.targets",
            "NuGet.config", "nuget.config");

    /** How deep to look for a project file. Nested solutions use {@code src/Product/Product.csproj}. */
    private static final int MAX_PROJECT_DEPTH = 6;

    /** How deep to look for C# sources when no project file was found. */
    private static final int MAX_SOURCE_DEPTH = 6;

    private static final Pattern PROJECT_REFERENCE =
            Pattern.compile("(?i)<PackageReference\\s+[^>]*Include\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TARGET_FRAMEWORK =
            Pattern.compile("(?i)<TargetFrameworks?\\s*>([^<]+)</TargetFrameworks?>");
    private static final Pattern IS_TEST_PROJECT =
            Pattern.compile("(?i)<IsTestProject\\s*>\\s*true\\s*</IsTestProject>");
    /** A namespace declaration: file-scoped ({@code namespace A.B;}) or block ({@code namespace A.B {}). */
    private static final Pattern NAMESPACE =
            Pattern.compile("(?m)^\\s*namespace\\s+([A-Za-z_][A-Za-z0-9_.]*)");

    private CSharpProjectDetector() {
    }

    /** @return {@code true} when the directory looks like a C# project this adapter can verify. */
    public static boolean looksLikeCSharpProject(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        if (!projectFiles(root).isEmpty()) {
            return true;
        }
        if (solutionFiles(root).stream().anyMatch(CSharpProjectDetector::solutionListsCSharpProject)) {
            return true;
        }
        return !sourceFiles(root).isEmpty();
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.CSHARP)
                .sourceExtensions(Set.of("cs"))
                .attribute(ATTR_SOURCE_EXTENSIONS, "cs")
                .attribute(ATTR_PACKAGE_MANAGER, "nuget");

        List<String> descriptors = new ArrayList<>();
        for (String descriptor : ROOT_DESCRIPTORS) {
            Path file = root.resolve(descriptor);
            if (Files.isRegularFile(file)) {
                descriptors.add(descriptor);
                builder.addDetectedDescriptor(file);
            }
        }

        List<Path> solutions = solutionFiles(root);
        List<Path> projects = projectFiles(root);
        for (Path solution : solutions) {
            descriptors.add(solution.getFileName().toString());
            builder.addDetectedDescriptor(solution);
        }
        for (Path csproj : projects) {
            builder.addDetectedDescriptor(csproj);
        }

        boolean buildable = !projects.isEmpty() || !solutions.isEmpty();
        builder.buildSystem(buildable ? BuildSystemId.DOTNET : BuildSystemId.NONE);
        if (descriptors.isEmpty()) {
            builder.attribute(ATTR_DESCRIPTOR, "none");
        } else {
            builder.attribute(ATTR_DESCRIPTOR, String.join(",", descriptors));
        }
        if (!solutions.isEmpty()) {
            builder.attribute(ATTR_SOLUTION, solutions.getFirst().getFileName().toString());
        }
        if (!buildable) {
            builder.attribute(ATTR_DETECTION_WARNING,
                    "no .csproj or .sln was found; the C# sources can be analysed but there is no project "
                            + "for the .NET SDK to build, test or mutate");
        } else if (projects.isEmpty()) {
            // A solution with no project beside it is unusual and worth saying so: the solution may
            // reference projects that are absent, and the build will then fail for a reason the report
            // can name in advance.
            builder.attribute(ATTR_DETECTION_WARNING,
                    "only a solution file was found; the projects it references could not be located beside it");
        }

        List<Path> testProjects = testProjects(root);
        List<Path> sourceProjects = new ArrayList<>(projects);
        sourceProjects.removeAll(testProjects);

        Set<String> testFrameworks = new LinkedHashSet<>();
        for (Path testProject : testProjects) {
            testFrameworks.add(testFramework(testProject));
        }
        testFrameworks.forEach(builder::addTestFramework);

        String targetFramework = projects.stream().map(CSharpProjectDetector::targetFramework)
                .filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(null);
        if (targetFramework != null) {
            builder.attribute(ATTR_TARGET_FRAMEWORK, targetFramework);
        }
        if (!testFrameworks.isEmpty()) {
            builder.attribute(ATTR_TEST_FRAMEWORK, String.join(",", testFrameworks));
        }

        for (Path csproj : sourceProjects) {
            builder.addModule(new ModuleInfo(stem(csproj), csproj.getParent(), "csproj",
                    List.of(csproj.getParent()), List.of(), null));
        }
        for (Path csproj : testProjects) {
            builder.addModule(new ModuleInfo(stem(csproj), csproj.getParent(), "csproj",
                    List.of(), List.of(csproj.getParent()), null));
        }
        if (projects.isEmpty() && solutions.isEmpty()) {
            // Nothing declares a module, but there is C# source to analyse. It is reported as one
            // source-only module so the static-analysis phase has somewhere to look, and the detector's
            // warning says why no build follows.
            builder.addModule(new ModuleInfo(project.name(), root, "source-only",
                    List.of(root), List.of(), null));
        }

        List<String> namespaces = namespaces(root);
        if (!namespaces.isEmpty()) {
            builder.attribute(ATTR_NAMESPACES, String.join(",", namespaces));
        }
        builder.attribute(ATTR_PROJECTS, String.valueOf(projects.size()));
        builder.attribute(ATTR_TEST_PROJECTS, String.valueOf(testProjects.size()));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!testProjects.isEmpty()));
        return builder.build();
    }

    /**
     * @return every {@code .csproj} below {@code root}, sorted, skipping build output, VCS metadata and
     *     previous runs
     */
    public static List<Path> projectFiles(Path root) {
        return filesWithSuffix(root, ".csproj", MAX_PROJECT_DEPTH);
    }

    /** @return every {@code .sln} or {@code .slnx} below {@code root}, sorted. */
    public static List<Path> solutionFiles(Path root) {
        List<Path> solutions = new ArrayList<>(filesWithSuffix(root, ".sln", MAX_PROJECT_DEPTH));
        solutions.addAll(filesWithSuffix(root, ".slnx", MAX_PROJECT_DEPTH));
        solutions.sort(java.util.Comparator.comparing(Path::toString));
        return solutions;
    }

    /** @return every {@code .cs} file below {@code root}, sorted, skipping build output and vendors. */
    public static List<Path> sourceFiles(Path root) {
        return filesWithSuffix(root, ".cs", Integer.MAX_VALUE);
    }

    /**
     * @return the projects the build phase should compile: the solution when the tree has exactly one,
     *     otherwise every project.
     *
     * <p>A solution is the project's own statement of which projects belong together, and building it
     *     is one command instead of several. When there is no solution - or more than one, when a tree
     *     holds several unrelated solutions - every project is built in turn instead, so that no project
     *     is silently left uncompiled.
     */
    public static List<Path> buildTargets(Path root) {
        List<Path> solutions = solutionFiles(root);
        if (solutions.size() == 1) {
            return List.of(solutions.getFirst());
        }
        return projectFiles(root);
    }

    /** @return the projects the test runner should run: those that reference a .NET test host. */
    public static List<Path> testProjects(Path root) {
        List<Path> tests = new ArrayList<>();
        for (Path project : projectFiles(root)) {
            if (isTestProject(project)) {
                tests.add(project);
            }
        }
        return List.copyOf(tests);
    }

    /**
     * @return {@code true} when the project file declares a .NET test host.
     *
     * <p>The test host is what makes {@code dotnet test} able to run a project at all. A project that
     * references only a test framework is a library that happens to use xUnit's attributes, and running
     * the test host over it would report "no tests" for a project that was never meant to have any.
     */
    public static boolean isTestProject(Path projectFile) {
        String content = read(projectFile);
        if (content.isEmpty()) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        if (IS_TEST_PROJECT.matcher(content).find()) {
            return true;
        }
        return lower.contains("microsoft.net.test.sdk")
                || lower.contains("microsoft.testing.platform")
                || lower.contains("microsoft.testplatform.testhost");
    }

    /** @return the test framework a project uses, as the report names it. */
    public static String testFramework(Path projectFile) {
        String content = read(projectFile).toLowerCase(Locale.ROOT);
        if (content.contains("xunit")) {
            return "xunit";
        }
        if (content.contains("nunit")) {
            return "nunit";
        }
        if (content.contains("mstest")) {
            return "mstest";
        }
        return "vstest";
    }

    /** @return the target framework a project declares, or {@code null} when it declares none. */
    public static String targetFramework(Path projectFile) {
        Matcher matcher = TARGET_FRAMEWORK.matcher(read(projectFile));
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).strip();
        // A multi-targeting project lists several frameworks separated by ';'; the report names the whole
        // set rather than pretending only the first one is built.
        return value.isEmpty() ? null : value;
    }

    /** @return the package ids a project references, in the order the project file lists them. */
    public static List<String> packageReferences(Path projectFile) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = PROJECT_REFERENCE.matcher(read(projectFile));
        while (matcher.find()) {
            String id = matcher.group(1).strip();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * @return every namespace declared in the C# sources below {@code root}, in first-seen order.
     *
     * <p>Used by the generated-test writer, which has to import the project's namespaces for a
     * scenario fragment to resolve the types it names. Collecting the declared namespaces is what makes
     * {@code new WithdrawalLedger()} compile without the scenario having to spell out its namespace.
     */
    public static List<String> namespaces(Path root) {
        Set<String> namespaces = new LinkedHashSet<>();
        for (Path file : sourceFiles(root)) {
            namespaces.addAll(namespacesOf(read(file)));
        }
        return List.copyOf(namespaces);
    }

    /** @return the namespaces declared in one C# source file, in first-seen order. */
    public static List<String> namespacesOf(String content) {
        List<String> namespaces = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return namespaces;
        }
        Matcher matcher = NAMESPACE.matcher(content);
        while (matcher.find()) {
            String namespace = matcher.group(1).strip();
            if (!namespace.isEmpty() && !namespaces.contains(namespace)) {
                namespaces.add(namespace);
            }
        }
        return namespaces;
    }

    /**
     * @param root   the directory to walk
     * @param suffix the file name suffix to collect, including the dot
     * @param depth  maximum depth, or {@link Integer#MAX_VALUE} for the whole tree
     * @return the matching files, sorted
     */
    static List<Path> filesWithSuffix(Path root, String suffix, int depth) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root, depth)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .filter(path -> !isExcluded(root, path))
                    // A project file inside a NuGet packages directory is a dependency's own project, not
                    // this project's; the directory is excluded by name below, and this guard covers the
                    // copy that lives directly under a project as well.
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return files;
    }

    /** @return {@code true} when the solution text references a C# project. */
    static boolean solutionListsCSharpProject(Path solution) {
        String content = read(solution);
        return content.toLowerCase(Locale.ROOT).contains(".csproj");
    }

    /**
     * @return {@code true} when a path element below the walked root is build output, a package cache or
     *     VCS metadata.
     *
     * <p>The check is relative to the walked root rather than absolute on purpose: a run's isolated
     * workspace lives under the project's {@code .codewitness} directory, so an absolute check would
     * reject every file in the workspace.
     */
    static boolean isExcluded(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        for (Path element : relative) {
            if (EXCLUDED_DIRECTORIES.contains(element.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "bin", "obj", ".git", ".codewitness", ".vs", ".idea", ".vscode", "node_modules", "packages",
            "testresults");

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }
}

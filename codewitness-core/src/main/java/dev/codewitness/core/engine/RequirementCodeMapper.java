package dev.codewitness.core.engine;

import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.RequirementSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Maps requirements to the source files and test classes that mention them.
 *
 * <p>The mapping rule is intentionally simple and inspectable: a file belongs to a requirement when
 * the requirement's identifier appears in it, or when the requirement declares
 * {@code paths:} / {@code tests:} explicitly. That makes the mapping a fact about the source tree
 * rather than a guess, and a reader can always check it by opening the file.
 *
 * <p>Anything not mapped stays unmapped, and the report says so. CodeWitness never silently
 * attributes a passing test to a requirement it does not mention.
 */
public final class RequirementCodeMapper {

    private final Map<String, Set<String>> testClassesByRequirement = new LinkedHashMap<>();
    private final Map<String, Set<String>> requirementIdsByAbsolutePath = new LinkedHashMap<>();
    private final Map<String, String> requirementIdByTestClass = new LinkedHashMap<>();
    private final List<String> unmappedTestFiles = new ArrayList<>();

    private RequirementCodeMapper() {
    }

    /** Scans the project's source and test trees and builds the mapping. */
    public static RequirementCodeMapper build(ProjectInfo project, RequirementSet requirements) {
        RequirementCodeMapper mapper = new RequirementCodeMapper();
        mapper.applyExplicitDeclarations(requirements);
        List<Path> testRoots = project.testSourceDirs();
        List<Path> mainRoots = project.sourceDirs();
        Path projectRoot = project.project().root();
        for (Path root : mainRoots) {
            mapper.scan(root, requirements, false, testRoots, projectRoot, project);
        }
        for (Path root : testRoots) {
            mapper.scan(root, requirements, true, testRoots, projectRoot, project);
        }
        return mapper;
    }

    private void applyExplicitDeclarations(RequirementSet requirements) {
        for (var requirement : requirements.requirements()) {
            String tests = requirement.attributes().get("tests");
            if (tests != null && !tests.isBlank()) {
                for (String test : tests.split("\\s*,\\s*")) {
                    if (!test.isBlank()) {
                        testClassesByRequirement
                                .computeIfAbsent(requirement.id(), k -> new LinkedHashSet<>())
                                .add(test.trim());
                        requirementIdByTestClass.put(test.trim(), requirement.id());
                    }
                }
            }
            String paths = requirement.attributes().get("paths");
            if (paths != null && !paths.isBlank()) {
                for (String path : paths.split("\\s*,\\s*")) {
                    if (!path.isBlank()) {
                        requirementIdsByAbsolutePath
                                .computeIfAbsent(path.trim(), k -> new LinkedHashSet<>())
                                .add(requirement.id());
                    }
                }
            }
        }
    }

    private void scan(
            Path root,
            RequirementSet requirements,
            boolean testTree,
            List<Path> testRoots,
            Path projectRoot,
            ProjectInfo project) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(project::isSourceFile)
                    .filter(file -> !testTree || isTestCandidate(file))
                    .toList();
            for (Path file : files) {
                String content;
                try {
                    content = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                String upper = content.toUpperCase(Locale.ROOT);
                boolean matched = false;
                for (var requirement : requirements.requirements()) {
                    if (upper.contains(requirement.id().toUpperCase(Locale.ROOT))) {
                        matched = true;
                        String relative = root.relativize(file).toString();
                        requirementIdsByAbsolutePath
                                .computeIfAbsent(relative, k -> new LinkedHashSet<>())
                                .add(requirement.id());
                        if (testTree) {
                            String className = toClassName(file, testRoots, projectRoot);
                            if (className != null) {
                                testClassesByRequirement
                                        .computeIfAbsent(requirement.id(), k -> new LinkedHashSet<>())
                                        .add(className);
                                requirementIdByTestClass.put(className, requirement.id());
                            }
                        }
                    }
                }
                if (testTree && !matched) {
                    unmappedTestFiles.add(file.getFileName().toString());
                }
            }
        } catch (IOException _) {
            // an unreadable tree simply produces an empty mapping, which the report discloses
        }
    }

    /**
     * Converts a test source file into the identifier the test runner reports it under.
     *
     * <p>Java reports a fully qualified class name, so the file is relativized against the test
     * source root and the extension dropped. Python reports a dotted module path, so the file is
     * relativized against the project root instead: {@code tests/test_ledger.py} becomes
     * {@code tests.test_ledger}, which is exactly what pytest and unittest put in their reports.
     * Other languages report a test target name, which is the file name without its extension.
     */
    private static String toClassName(Path file, List<Path> roots, Path projectRoot) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("py") || extension.equals("pyi")) {
            Path base = projectRoot != null && file.startsWith(projectRoot) ? projectRoot : file.getParent();
            if (base == null) {
                return stripExtension(fileName);
            }
            String relative = base.relativize(file).toString().replace(java.io.File.separatorChar, '.')
                    .replace('/', '.');
            return stripExtension(relative);
        }
        if (extension.equals("go")) {
            // Go reports a test case under its package's module-qualified import path, which is what
            // the Go test report parser records as the suite, so the mapping derives the same value
            // from go.mod and the file's directory.
            return goPackagePath(file, projectRoot, stripExtension(fileName));
        }
        if (extension.equals("js") || extension.equals("mjs") || extension.equals("cjs")
                || extension.equals("ts") || extension.equals("tsx") || extension.equals("mts") || extension.equals("cts")) {
            // Node's test runner reports a test file by path and its cases by display name, so the
            // stable identifier is the file's own name: the extension and the test/spec marker are
            // dropped, which is what the Node test report parser produces for the same file.
            return stripTestMarker(stripExtension(fileName));
        }
        for (Path root : roots) {
            if (root != null && file.startsWith(root)) {
                String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '.')
                        .replace('/', '.');
                // Java keeps the package path (com.example.FooTest); a test target name such as a Rust
                // integration test binary is just the file stem. stripExtension covers both, because a
                // Java class is its own file stem and a package separator is not a file extension.
                return stripExtension(relative);
            }
        }
        // Only source files reach this method, so dropping the extension of a bare file name is the
        // same rule as above applied without a root to relativize against.
        return stripExtension(fileName);
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(0, dot);
    }

    /**
     * @return {@code true} when a file in a test tree is really a test file.
     *
     * <p>Most languages have a separate test root, so everything below it is a test. Go places tests
     * beside the code as {@code *_test.go}, so a Go test tree is the package directory and only the
     * {@code _test.go} files in it count; without this rule every source file would also be read as a
     * test and its mention of a requirement would be attributed to the tests.
     */
    static boolean isTestCandidate(Path file) {
        String name = file == null || file.getFileName() == null
                ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.endsWith(".go") || name.endsWith("_test.go");
    }

    /**
     * @return the module-qualified import path of the package a Go file belongs to, derived from
     *     {@code go.mod}, or {@code fallback} when the project is not a module.
     */
    static String goPackagePath(Path file, Path projectRoot, String fallback) {
        if (file == null || projectRoot == null) {
            return fallback;
        }
        String module = readGoModule(projectRoot.resolve("go.mod"));
        Path parent = file.getParent();
        if (module == null || parent == null || !parent.startsWith(projectRoot)) {
            return module == null ? fallback : module;
        }
        String relative = projectRoot.relativize(parent).toString().replace(java.io.File.separatorChar, '/');
        return relative.isEmpty() ? module : module + "/" + relative;
    }

    private static String readGoModule(Path manifest) {
        if (manifest == null || !Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("module ")) {
                    String value = trimmed.substring("module ".length()).strip().replace("\"", "").replace("'", "");
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (IOException _) {
            // an unreadable manifest yields the fallback, which the caller reports rather than guesses about
        }
        return null;
    }

    /** Drops a trailing {@code .test} or {@code .spec} marker from a JavaScript test file stem. */
    private static String stripTestMarker(String value) {
        if (value.endsWith(".test")) {
            return value.substring(0, value.length() - ".test".length());
        }
        if (value.endsWith(".spec")) {
            return value.substring(0, value.length() - ".spec".length());
        }
        return value;
    }

    /** @return test classes that mention the requirement. */
    public List<String> testClassesFor(String requirementId) {
        return List.copyOf(testClassesByRequirement.getOrDefault(requirementId, Set.of()));
    }

    /** @return the requirement a test class was mapped to, if any. */
    public Optional<String> requirementForTestClass(String className) {
        if (className == null) {
            return Optional.empty();
        }
        String direct = requirementIdByTestClass.get(className);
        if (direct != null) {
            return Optional.of(direct);
        }
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        for (Map.Entry<String, String> entry : requirementIdByTestClass.entrySet()) {
            String key = entry.getKey();
            String keySimple = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
            if (keySimple.equals(simple)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** @return requirements whose mapped files cover the given path, matched by suffix. */
    public Set<String> requirementsForFile(String relativeOrAbsolutePath) {
        Set<String> result = new LinkedHashSet<>();
        if (relativeOrAbsolutePath == null) {
            return result;
        }
        String normalized = relativeOrAbsolutePath.replace('\\', '/');
        for (Map.Entry<String, Set<String>> entry : requirementIdsByAbsolutePath.entrySet()) {
            String key = entry.getKey().replace('\\', '/');
            if (normalized.endsWith(key) || key.endsWith(normalized)) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    public List<String> unmappedTestFiles() {
        return List.copyOf(unmappedTestFiles);
    }

    public boolean hasAnyMapping() {
        return !requirementIdsByAbsolutePath.isEmpty() || !testClassesByRequirement.isEmpty();
    }
}

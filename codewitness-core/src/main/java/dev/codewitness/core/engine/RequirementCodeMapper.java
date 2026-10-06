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
        for (Path root : mainRoots) {
            mapper.scan(root, requirements, false, testRoots);
        }
        for (Path root : testRoots) {
            mapper.scan(root, requirements, true, testRoots);
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

    private void scan(Path root, RequirementSet requirements, boolean testTree, List<Path> testRoots) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).toList();
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
                            String className = toClassName(file, testRoots);
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
        } catch (IOException ignored) {
            // an unreadable tree simply produces an empty mapping, which the report discloses
        }
    }

    /** Converts a test source file into its fully qualified class name when it sits under a source root. */
    private static String toClassName(Path file, List<Path> roots) {
        for (Path root : roots) {
            if (root != null && file.startsWith(root)) {
                String relative = root.relativize(file).toString();
                return relative.replace(java.io.File.separatorChar, '.').replace('/', '.')
                        .replaceAll("\\.java$", "");
            }
        }
        String fileName = file.getFileName().toString();
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : null;
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

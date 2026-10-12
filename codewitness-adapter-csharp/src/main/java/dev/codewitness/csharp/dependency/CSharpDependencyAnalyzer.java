package dev.codewitness.csharp.dependency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.csharp.CSharpProjectDetector;
import dev.codewitness.support.dependency.DependencyPathHints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventories a .NET project's NuGet dependencies from its manifests.
 *
 * <p>This is an inventory, not a vulnerability scan. It reports which packages the project needs and at
 * which version, and says so in the report's own words; whether any of them has a known advisory is a
 * separate question, answered by the advisory scan, which is the only phase that consults a database.
 *
 * <p>Three sources are read, in decreasing order of authority:
 *
 * <ol>
 *   <li>a {@code packages.lock.json}, which is NuGet's own resolution record: it names every package the
 *       restore selected, transitives included, with the exact version. Asking an advisory service about
 *       anything else would be asking about a version the build may not use;</li>
 *   <li>the {@code Version} attribute of a {@code PackageReference} in the project file;</li>
 *   <li>a central {@code PackageVersion} in {@code Directory.Packages.props}, which is where a project
 *       using central package management declares the version instead.</li>
 * </ol>
 *
 * <p>A reference whose version comes from an MSBuild property such as {@code $(XunitVersion)} is reported
 * without a version rather than with a guess. The model treats that as meaningful - the package exists,
 * the resolved version is not known - and the advisory scan reports that it could not be asked about it
 * instead of quietly omitting it.
 *
 * <p>No command is executed: the manifests are read directly. That keeps the phase available even when
 * the SDK cannot restore, and it keeps the inventory independent of whether the machine has a warm
 * package cache.
 */
public final class CSharpDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code <PackageReference Include="X" Version="1.2.3" />}, with the attributes in any order. */
    private static final Pattern PACKAGE_REFERENCE =
            Pattern.compile("(?is)<PackageReference\\b([^>]*?)/?>");
    /** {@code <PackageVersion Include="X" Version="1.2.3" />} in a central package management file. */
    private static final Pattern PACKAGE_VERSION =
            Pattern.compile("(?is)<PackageVersion\\b([^>]*?)/?>");
    private static final Pattern INCLUDE = Pattern.compile("(?i)\\bInclude\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("(?i)\\bVersion\\s*=\\s*\"([^\"]*)\"");

    @Override
    public String id() {
        return "nuget-packages";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CSHARP);
    }

    /**
     * {@inheritDoc}
     *
     * <p>NuGet package ids are what an advisory's NuGet record names, and the ecosystem is spelled with
     * the casing the advisory service uses.
     */
    @Override
    public Set<String> ecosystems() {
        return Set.of(PackageCoordinate.NUGET);
    }

    /** {@inheritDoc} */
    @Override
    public String dependencyPathHint(PackageCoordinate coordinate) {
        return DependencyPathHints.nuget(coordinate);
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path root = context.projectRoot();
        List<Path> projects = CSharpProjectDetector.projectFiles(root);
        String manifest = read(root.resolve("Directory.Packages.props"));
        if (manifest.isEmpty()) {
            // The file is conventionally named with a capital D; a lower-case copy is accepted because
            // MSBuild accepts it on case-sensitive filesystems too.
            manifest = read(root.resolve("directory.packages.props"));
        }
        Map<String, String> centralVersions = centralVersions(manifest);
        Map<String, String> rootLock = lockVersions(root.resolve("packages.lock.json"));

        if (projects.isEmpty()) {
            // A tree of C# sources with no project file has no manifest to read. Reporting an empty
            // inventory would be indistinguishable from a project with no packages.
            return DependencyReport.notAnalysed("nuget-packages",
                    "no .csproj was found, so no package reference could be read");
        }

        List<PackageCoordinate> coordinates = new ArrayList<>();
        Map<String, List<String>> byModule = new LinkedHashMap<>();
        Map<String, String> versionsById = new LinkedHashMap<>();
        List<String> resolvedFromLock = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (Path project : projects) {
            Map<String, String> lock = rootLock.isEmpty()
                    ? lockVersions(project.getParent().resolve("packages.lock.json")) : rootLock;
            if (!lock.isEmpty() && !resolvedFromLock.contains(project.getFileName().toString())) {
                resolvedFromLock.add(project.getFileName().toString());
                // Every package the lock file resolved, transitives included: that is the graph the build
                // actually uses, not just the direct references the project file lists.
                for (Map.Entry<String, String> entry : lock.entrySet()) {
                    versionsById.putIfAbsent(entry.getKey(), entry.getValue());
                    coordinates.add(PackageCoordinate.nuget(entry.getKey(), entry.getValue()));
                }
            }
            List<PackageCoordinate> declared = declaredPackages(project, centralVersions, lock);
            for (PackageCoordinate coordinate : declared) {
                versionsById.putIfAbsent(coordinate.name(), coordinate.version());
                coordinates.add(coordinate);
                if (!coordinate.resolved() && !unresolved.contains(coordinate.name())) {
                    unresolved.add(coordinate.name());
                }
            }
            byModule.put(stem(project), declared.stream().map(PackageCoordinate::display).toList());
        }

        List<PackageCoordinate> distinct = PackageCoordinate.distinct(coordinates);
        String note = inventoryNote(projects.size(), resolvedFromLock.size(), unresolved);
        return DependencyReport.inventory("nuget-packages", note, distinct, byModule);
    }

    /** @return the sentence the report uses to describe what this inventory covers. */
    static String inventoryNote(int projects, int lockFiles, List<String> unresolved) {
        StringBuilder sb = new StringBuilder();
        sb.append("inventory only: the NuGet packages ").append(projects)
                .append(projects == 1 ? " project declares" : " projects declare");
        if (lockFiles > 0) {
            sb.append(", read from packages.lock.json for ").append(lockFiles)
                    .append(lockFiles == 1 ? " project" : " projects")
                    .append(" and so including the transitive graph with the versions the restore selected");
        } else {
            sb.append(", read from the project files and Directory.Packages.props. Without a "
                    + "packages.lock.json this is the declared version, which is the version NuGet selects "
                    + "unless a lock file or a higher transitive requirement says otherwise");
        }
        if (!unresolved.isEmpty()) {
            sb.append(". ").append(unresolved.size())
                    .append(unresolved.size() == 1 ? " package has" : " packages have")
                    .append(" no readable version and cannot be asked about: ")
                    .append(String.join(", ", unresolved));
        }
        sb.append(". Whether any of these has a known advisory is recorded by the advisory scan, a "
                + "separate question from which packages the program needs");
        return sb.toString();
    }

    /** @return one coordinate per declared {@code PackageReference}, in the order the project lists them. */
    static List<PackageCoordinate> declaredPackages(
            Path project, Map<String, String> centralVersions, Map<String, String> lock) {
        List<PackageCoordinate> coordinates = new ArrayList<>();
        Matcher matcher = PACKAGE_REFERENCE.matcher(read(project));
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String id = attribute(INCLUDE, attributes);
            if (id == null) {
                continue;
            }
            String version = attribute(VERSION, attributes);
            if (version == null && lock != null) {
                version = lock.get(id);
            }
            if (version == null) {
                version = centralVersions.get(id);
            }
            coordinates.add(PackageCoordinate.nuget(id, version == null ? "" : version));
        }
        return List.copyOf(coordinates);
    }

    /** @return the central package versions declared in a {@code Directory.Packages.props}. */
    static Map<String, String> centralVersions(String manifest) {
        Map<String, String> versions = new LinkedHashMap<>();
        if (manifest == null || manifest.isEmpty()) {
            return versions;
        }
        Matcher matcher = PACKAGE_VERSION.matcher(manifest);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String id = attribute(INCLUDE, attributes);
            String version = attribute(VERSION, attributes);
            if (id != null && version != null) {
                versions.put(id, version);
            }
        }
        return versions;
    }

    /**
     * @return every package a {@code packages.lock.json} resolved, transitives included, keyed by package
     *     id, or an empty map when the file is absent or unreadable.
     *
     * <p>The lock file groups its entries by target framework. A package resolved to different versions for
     *     different frameworks appears once per framework; the first one is reported and the report's note
     *     says the inventory comes from the lock file, so a reader knows the version is the resolved one
     *     rather than a range.
     */
    static Map<String, String> lockVersions(Path lockFile) {
        Map<String, String> versions = new LinkedHashMap<>();
        if (lockFile == null || !Files.isRegularFile(lockFile)) {
            return versions;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(lockFile, StandardCharsets.UTF_8));
            JsonNode dependencies = root.path("dependencies");
            if (!dependencies.isObject()) {
                return versions;
            }
            dependencies.fields().forEachRemaining(framework -> {
                if (!framework.getValue().isObject()) {
                    return;
                }
                framework.getValue().fields().forEachRemaining(packageEntry -> {
                    JsonNode resolved = packageEntry.getValue().path("resolved");
                    if (resolved.isTextual() && !resolved.asText().isBlank()) {
                        versions.putIfAbsent(packageEntry.getKey(), resolved.asText());
                    }
                });
            });
        } catch (IOException _) {
            // A lock file that cannot be parsed is not an empty package graph: the caller falls back to the
            // declared versions and the note says a lock file was not read.
            return Map.of();
        }
        return versions;
    }

    private static String attribute(Pattern pattern, String attributes) {
        Matcher matcher = pattern.matcher(attributes);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).strip();
        if (value.isEmpty() || value.contains("$(")) {
            // An MSBuild property is not a version this adapter can evaluate, and inventing one would put a
            // wrong version in front of an advisory service.
            return null;
        }
        return value;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}

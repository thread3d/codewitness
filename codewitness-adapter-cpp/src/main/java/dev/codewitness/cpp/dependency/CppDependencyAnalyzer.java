package dev.codewitness.cpp.dependency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.cpp.CppProjectDetector;
import dev.codewitness.support.dependency.DependencyPathHints;

import java.io.IOException;
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
 * Inventories a C or C++ project's packages from its manifests and its CMake files.
 *
 * <p>This is an inventory, not a vulnerability scan. It reports which packages the program needs and at
 * which version, and says so in the report's own words; whether any of them has a known advisory is a
 * separate question, answered by the advisory scan, which is the only phase that consults a database.
 *
 * <p>Three sources are read, in decreasing order of authority:
 *
 * <ol>
 *   <li>a {@code conan.lock}, which is Conan's own resolution record: it names every package the install
 *       selected, transitives included, with the exact version. Asking an advisory service about anything
 *       else would be asking about a version the build may not use;</li>
 *   <li>a {@code vcpkg.json} manifest, whose dependencies declare packages at most at a floor - a
 *       {@code version>=} or nothing at all - and a floor is not the version vcpkg installed;</li>
 *   <li>the {@code CMakeLists.txt} files, whose {@code FetchContent_Declare} and {@code find_package}
 *       calls name packages no package manager resolved: a git tag is not a published release, and a
 *       system package is provided by the platform, so both are reported with no version rather than
 *       with a guess.</li>
 * </ol>
 *
 * <p>A coordinate with no version is meaningful rather than missing data: it says the package exists and
 * that the resolved version is not known, and the advisory scan reports that it could not be asked about
 * it instead of quietly omitting it. The report's note counts those packages and names them.
 *
 * <p>Every coordinate here carries the {@code ConanCenter} ecosystem, the name the advisory service knows
 * the C and C++ ecosystem by; {@code Conan} alone is rejected by that service rather than answered with
 * no advisories, which is the difference between "nothing is known" and "nothing is wrong". A package
 * that only CMake declares is not given an ecosystem of its own either, because inventing one would
 * claim a registry that never resolved it; it stays in the same ecosystem with the version left empty,
 * so nothing can be asked about it.
 *
 * <p>No command is executed: the manifests and the CMake files are read directly. That keeps the phase
 * available even when Conan or vcpkg cannot reach a registry, and it keeps the inventory independent of
 * whether the machine has a warm package cache.
 */
public final class CppDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Conan's resolution record. A {@code conanfile.lock} without the dot is not a thing. */
    private static final String CONAN_LOCK = "conan.lock";
    /** The vcpkg manifest, which declares floors rather than resolved versions. */
    private static final String VCPKG_MANIFEST = "vcpkg.json";
    /** The field by which a vcpkg manifest pins the port revisions its floors are relative to. */
    private static final String BUILTIN_BASELINE = "builtin-baseline";

    /** Why a {@code FetchContent} dependency's version is not reported when its source is a git tag. */
    private static final String GIT_TAG_REASON = "the version is a git tag rather than a published release";
    /** Why a {@code FetchContent} dependency's version is not reported when its source is an archive. */
    private static final String FETCHED_SOURCE_REASON =
            "the version is a downloaded archive rather than a published release";
    /** Why a {@code find_package} dependency's version is not reported. */
    private static final String SYSTEM_PACKAGE_REASON = "a system package the platform provides";
    /** Why a vcpkg declaration's version is not reported. */
    private static final String VCPKG_FLOOR_REASON =
            "a vcpkg manifest states a floor rather than the installed version";

    /** {@code FetchContent_Declare(<name> ...)}, read up to its closing parenthesis. */
    private static final Pattern FETCH_CONTENT =
            Pattern.compile("(?is)FetchContent_Declare\\s*\\(\\s*([^\\s()]+)(.*?)\\)");
    /** The {@code GIT_TAG} argument of a {@code FetchContent_Declare}, quoted or bare. */
    private static final Pattern GIT_TAG = Pattern.compile("(?is)\\bGIT_TAG\\s+\"?([^\\s\")]+)\"?");
    /** The {@code URL} argument of a {@code FetchContent_Declare}, quoted or bare. */
    private static final Pattern FETCH_URL = Pattern.compile("(?is)\\bURL\\s+\"?([^\\s\")]+)\"?");
    /** {@code find_package(<Name> [<version>] ...)}. */
    private static final Pattern FIND_PACKAGE =
            Pattern.compile("(?is)find_package\\s*\\(\\s*([^\\s()]+)([^)]*)\\)");
    @Override
    public String id() {
        return "cpp-packages";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CPP);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every coordinate this inventory produces, resolved or not, is spelled as a Conan Center package:
     * {@code conan.lock} is the only source here that resolves a version, while vcpkg floors,
     * {@code FetchContent} tags and {@code find_package} names are recorded under the same ecosystem so
     * that the report has one place to say whether each was queryable. Whether the advisory source
     * publishes records for that ecosystem is a separate question, and the scan's own note answers it.
     */
    @Override
    public Set<String> ecosystems() {
        return Set.of(PackageCoordinate.CONAN_CENTER);
    }

    /** {@inheritDoc} */
    @Override
    public String dependencyPathHint(PackageCoordinate coordinate) {
        return DependencyPathHints.conan(coordinate);
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path root = context.projectRoot();
        List<Path> cmakeFiles = CppProjectDetector.cmakeListsFiles(root);
        Path buildRoot = CppProjectDetector.buildRoot(root);
        Path conanLock = manifest(root, buildRoot, CONAN_LOCK);
        Path vcpkgManifest = manifest(root, buildRoot, VCPKG_MANIFEST);

        if (cmakeFiles.isEmpty() && conanLock == null && vcpkgManifest == null) {
            // Nothing to read at all. Reporting an empty inventory here would be indistinguishable from a
            // project with no dependencies, which is a different fact about the tree.
            return DependencyReport.notAnalysed("cpp-packages",
                    "no CMakeLists.txt and no vcpkg or Conan manifest was found, so no dependency could "
                            + "be read");
        }

        String lockText = CppProjectDetector.read(conanLock);
        boolean lockUnreadable = conanLock != null && !isConanLock(lockText);
        if (lockUnreadable && vcpkgManifest == null && cmakeFiles.isEmpty()) {
            // A resolution record that exists but cannot be parsed is not an empty package graph: the
            // versions the install selected are unknown, and "no dependencies" is a claim this phase
            // cannot support.
            return DependencyReport.notAnalysed("cpp-packages",
                    "conan.lock exists but could not be read as a Conan resolution record, so the "
                            + "resolved package graph is unknown rather than empty");
        }

        String vcpkgText = CppProjectDetector.read(vcpkgManifest);
        List<String> baseline = vcpkgBaseline(vcpkgText);
        Map<String, String> baselineAttributes = new LinkedHashMap<>();
        if (baseline != null && !baseline.isEmpty()) {
            baselineAttributes.put(BUILTIN_BASELINE, baseline.getFirst());
        }

        String topLevel = topLevelProject(cmakeFiles, buildRoot, root);
        List<PackageCoordinate> coordinates = new ArrayList<>();
        Map<String, String> unresolved = new LinkedHashMap<>();
        List<String> topLevelDisplays = new ArrayList<>();

        for (Map.Entry<String, String> resolved : conanLockVersions(lockText).entrySet()) {
            // Conan's own resolution record: every package the install selected, transitives included, at
            // the version it selected.
            PackageCoordinate coordinate = PackageCoordinate.conan(resolved.getKey(), resolved.getValue());
            coordinates.add(coordinate);
            topLevelDisplays.add(coordinate.display());
        }
        for (PackageCoordinate coordinate : vcpkgDependencies(vcpkgText, baselineAttributes)) {
            coordinates.add(coordinate);
            topLevelDisplays.add(coordinate.display());
            unresolved.putIfAbsent(coordinate.name(), VCPKG_FLOOR_REASON);
        }

        Map<String, List<String>> byModule = new LinkedHashMap<>();
        for (Path cmakeFile : cmakeFiles) {
            String cmakeText = CppProjectDetector.read(cmakeFile);
            String project = projectName(cmakeFile);
            List<String> declared = new ArrayList<>();
            for (FetchContentDependency fetched : fetchContentDeclarations(cmakeText)) {
                coordinates.add(fetched.coordinate());
                declared.add(fetched.display());
                unresolved.putIfAbsent(fetched.name(),
                        fetched.gitTag() ? GIT_TAG_REASON : FETCHED_SOURCE_REASON);
            }
            for (PackageCoordinate coordinate : findPackageDependencies(cmakeText)) {
                coordinates.add(coordinate);
                declared.add(coordinate.display());
                unresolved.putIfAbsent(coordinate.name(), SYSTEM_PACKAGE_REASON);
            }
            List<String> module = byModule.get(project);
            if (module == null) {
                module = new ArrayList<>(project.equals(topLevel) ? topLevelDisplays : List.of());
                byModule.put(project, module);
            }
            module.addAll(declared);
        }
        if (!byModule.containsKey(topLevel)) {
            // Manifests without a CMake project still belong to the module the detector would report for
            // the tree, so an inventory of a vcpkg-only or Conan-only tree is not left without one.
            byModule.put(topLevel, new ArrayList<>(topLevelDisplays));
        }

        int sources = (conanLock == null ? 0 : 1) + (vcpkgManifest == null ? 0 : 1) + cmakeFiles.size();
        String note = inventoryNote(sources, unresolvedEntries(unresolved));
        if (lockUnreadable) {
            note = note + ". conan.lock exists but could not be read as a Conan resolution record, so the "
                    + "resolved graph is missing from this inventory and only the declared packages are "
                    + "listed";
        }
        return DependencyReport.inventory("cpp-packages", note,
                PackageCoordinate.distinct(coordinates), byModule);
    }

    @Override
    public List<String> dependencyCommand(VerificationContext context) {
        return List.of();
    }

    /**
     * @return the sentence the report uses to describe what this inventory covers.
     *
     * @param sources    how many files the inventory was read from: the manifests that exist plus every
     *                   {@code CMakeLists.txt} below the project root
     * @param unresolved the packages with no readable version, each already carrying the reason no
     *                   version is known, in the order the sources named them
     */
    public static String inventoryNote(int sources, List<String> unresolved) {
        StringBuilder note = new StringBuilder();
        note.append("inventory only, not a vulnerability scan: the C and C++ packages the program needs, ")
                .append("read from ").append(sources).append(sources == 1 ? " file" : " files")
                .append(". conan.lock is Conan's own resolution record, so it gives the resolved graph "
                        + "with the versions the install selected; vcpkg.json states a declared floor "
                        + "rather than the installed version; and a CMakeLists.txt names the packages "
                        + "FetchContent downloads and the ones find_package asks the platform for, with "
                        + "no version at all");
        if (unresolved != null && !unresolved.isEmpty()) {
            note.append(". ").append(unresolved.size())
                    .append(unresolved.size() == 1 ? " package has" : " packages have")
                    .append(" no readable version and cannot be asked about: ")
                    .append(String.join(", ", unresolved));
        }
        note.append(". Whether any of these has a known advisory is recorded by the advisory scan, a "
                + "separate question from which packages the program needs");
        return note.toString();
    }

    /**
     * @return every package a {@code conan.lock} resolved, keyed by package name, in the order the file
     *     lists them, or an empty map when the text is absent, blank or not parseable.
     *
     * <p>An entry is {@code name/version#recipe-revision%timestamp}. The recipe revision and the
     *     timestamp identify the revision the install used rather than the package an advisory service
     *     knows, so only the part before the {@code #} is read, and the version is everything after the
     *     first {@code /} in it.
     */
    public static Map<String, String> conanLockVersions(String json) {
        Map<String, String> versions = new LinkedHashMap<>();
        JsonNode root = parse(json);
        if (root == null || !root.isObject()) {
            return versions;
        }
        for (String field : List.of("requires", "build_requires", "python_requires")) {
            JsonNode entries = root.path(field);
            if (!entries.isArray()) {
                continue;
            }
            for (JsonNode entry : entries) {
                if (!entry.isTextual()) {
                    continue;
                }
                String reference = entry.asText();
                int revision = reference.indexOf('#');
                String coordinate = (revision < 0 ? reference : reference.substring(0, revision)).strip();
                int slash = coordinate.indexOf('/');
                if (slash <= 0 || slash == coordinate.length() - 1) {
                    // A reference that does not carry both a name and a version is not a package this
                    // inventory can report, and guessing which half is missing would put a wrong name or
                    // a wrong version in front of an advisory service.
                    continue;
                }
                String name = coordinate.substring(0, slash).strip();
                String version = coordinate.substring(slash + 1).strip();
                if (!name.isEmpty() && !version.isEmpty()) {
                    versions.putIfAbsent(name, version);
                }
            }
        }
        return versions;
    }

    /**
     * @return the {@code builtin-baseline} the manifest names, as a single-element list, or {@code null}
     *     when the text is absent, unparseable or declares no baseline.
     *
     * <p>A baseline is a git commit hash rather than a version, and it is never reported as one: it says
     *     which vcpkg revision the manifest's floors are relative to, not which version of any package is
     *     installed. {@link #vcpkgDependencies} keeps it as an attribute of the display, so the report
     *     records the fact without mistaking it for a version.
     */
    public static List<String> vcpkgBaseline(String json) {
        JsonNode root = parse(json);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode baseline = root.path(BUILTIN_BASELINE);
        if (!baseline.isTextual() || baseline.asText().isBlank()) {
            return null;
        }
        return List.of(baseline.asText().strip());
    }

    /**
     * @return one coordinate per dependency a {@code vcpkg.json} declares, in the order the file lists
     *     them, each without a version.
     *
     * <p>A vcpkg manifest declares a floor - {@code version>=} - or nothing at all for a bare string, and
     *     neither is the version vcpkg selected, so no version is reported. The declared floor and every
     *     attribute in {@code baseline} are kept in the display instead: a reader sees what the manifest
     *     said, while an advisory service is never asked about a version nobody resolved.
     *
     * @param json     the manifest text
     * @param baseline attributes to record in the display, such as the {@code builtin-baseline}; may be
     *                 {@code null} or empty
     */
    public static List<PackageCoordinate> vcpkgDependencies(String json, Map<String, String> baseline) {
        List<PackageCoordinate> coordinates = new ArrayList<>();
        JsonNode root = parse(json);
        if (root == null || !root.isObject()) {
            return coordinates;
        }
        JsonNode dependencies = root.path("dependencies");
        if (!dependencies.isArray()) {
            return coordinates;
        }
        for (JsonNode dependency : dependencies) {
            String name = null;
            String floor = "";
            if (dependency.isTextual()) {
                name = dependency.asText().strip();
            } else if (dependency.isObject()) {
                name = dependency.path("name").asText("").strip();
                floor = dependency.path("version>=").asText("").strip();
            }
            if (name == null || name.isEmpty()) {
                // An entry that names no package - a bare object, a number, a platform-only marker - is
                // skipped rather than reported under an invented name.
                continue;
            }
            coordinates.add(new PackageCoordinate(PackageCoordinate.CONAN_CENTER, name, "",
                    vcpkgDisplay(name, floor, baseline)));
        }
        return List.copyOf(coordinates);
    }

    /**
     * @return one coordinate per {@code FetchContent_Declare} in the CMake text, in the order they appear.
     *
     * <p>Every one of them is reported without a version. A {@code GIT_TAG} such as {@code v1.14.0} looks
     *     like a semantic version and is not one: it names a commit in somebody else's repository, and
     *     asking an advisory service about the release of that number would report on a package that need
     *     not be the one this build fetched. A {@code URL} names an archive whose version cannot be read
     *     from the CMake file at all. The display names the tag or the URL, so the report keeps what the
     *     build actually fetches.
     */
    public static List<PackageCoordinate> fetchContentDependencies(String cmakeText) {
        List<PackageCoordinate> coordinates = new ArrayList<>();
        for (FetchContentDependency declaration : fetchContentDeclarations(cmakeText)) {
            coordinates.add(declaration.coordinate());
        }
        return List.copyOf(coordinates);
    }

    /**
     * @return one coordinate per {@code find_package} in the CMake text, in the order they appear, each
     *     without a version.
     *
     * <p>A {@code find_package} names something the platform provides - a system library, a toolchain, a
     *     framework - so its version is a property of the machine rather than of this project's
     *     dependency graph: a version written in the CMake file is what the build requires, not what it
     *     resolved. The display keeps the name and that requirement, because it is what the build asked
     *     for; the version is left empty, because the answer is not in the tree.
     */
    public static List<PackageCoordinate> findPackageDependencies(String cmakeText) {
        List<PackageCoordinate> coordinates = new ArrayList<>();
        String text = CppProjectDetector.stripComments(cmakeText);
        if (text.isEmpty()) {
            return coordinates;
        }
        Matcher matcher = FIND_PACKAGE.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1).strip();
            if (name.isEmpty()) {
                continue;
            }
            String version = requiredVersion(matcher.group(2));
            coordinates.add(new PackageCoordinate(PackageCoordinate.CONAN_CENTER, name, "",
                    version == null ? "find_package(" + name + ")"
                            : "find_package(" + name + " " + version + ")"));
        }
        return List.copyOf(coordinates);
    }

    /**
     * @return every {@code FetchContent_Declare} in the CMake text with the argument that names its
     *     source, so the caller can say why none of them carries a version.
     */
    private static List<FetchContentDependency> fetchContentDeclarations(String cmakeText) {
        List<FetchContentDependency> declarations = new ArrayList<>();
        String text = CppProjectDetector.stripComments(cmakeText);
        if (text.isEmpty()) {
            return declarations;
        }
        Matcher matcher = FETCH_CONTENT.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1).strip();
            if (name.isEmpty()) {
                continue;
            }
            String arguments = matcher.group(2);
            Matcher tag = GIT_TAG.matcher(arguments);
            if (tag.find()) {
                String value = tag.group(1).strip();
                declarations.add(new FetchContentDependency(name, name + " (GIT_TAG " + value + ")", true));
                continue;
            }
            Matcher url = FETCH_URL.matcher(arguments);
            String value = url.find() ? url.group(1).strip() : "";
            declarations.add(new FetchContentDependency(name,
                    value.isEmpty() ? name : name + " (URL " + value + ")", false));
        }
        return List.copyOf(declarations);
    }

    /**
     * @param arguments the {@code find_package} arguments after the package name
     * @return the version the call requires, or {@code null} when it names none.
     *
     * <p>Only a token that starts with a digit is read as a version: the rest of the argument list is
     *     CMake's own keywords ({@code REQUIRED}, {@code COMPONENTS}, {@code CONFIG}), component names and
     *     paths, none of which is a version this adapter may report.
     */
    private static String requiredVersion(String arguments) {
        for (String token : arguments.strip().split("\\s+")) {
            String candidate = token.strip();
            if (!candidate.isEmpty() && Character.isDigit(candidate.charAt(0))) {
                return candidate;
            }
        }
        return null;
    }

    /** @return the display of a vcpkg dependency: its name, the floor it declares and the baseline. */
    private static String vcpkgDisplay(String name, String floor, Map<String, String> baseline) {
        StringBuilder display = new StringBuilder(name);
        if (floor != null && !floor.isEmpty()) {
            display.append(" >=").append(floor);
        }
        if (baseline != null) {
            baseline.forEach((attribute, value) -> {
                if (attribute != null && !attribute.isBlank() && value != null && !value.isBlank()) {
                    display.append(" (").append(attribute.strip()).append(' ').append(value.strip())
                            .append(')');
                }
            });
        }
        return display.toString();
    }

    /** @return the unresolved packages as the note names them: the package, and why no version is known. */
    private static List<String> unresolvedEntries(Map<String, String> unresolved) {
        List<String> entries = new ArrayList<>();
        unresolved.forEach((name, reason) -> entries.add(name + " (" + reason + ")"));
        return List.copyOf(entries);
    }

    /**
     * @return the name of the CMake project the whole build belongs to: the one at the build root when
     *     there is one, otherwise the directory the manifests were found in.
     */
    private static String topLevelProject(List<Path> cmakeFiles, Path buildRoot, Path root) {
        if (buildRoot != null) {
            Path normalized = buildRoot.toAbsolutePath().normalize();
            for (Path cmakeFile : cmakeFiles) {
                Path directory = cmakeFile.getParent();
                if (directory != null && directory.toAbsolutePath().normalize().equals(normalized)) {
                    return projectName(cmakeFile);
                }
            }
        }
        return cmakeFiles.isEmpty() ? directoryName(root) : projectName(cmakeFiles.getFirst());
    }

    /**
     * @return the name a CMake file's own {@code project()} declares, or its directory name when it
     *     declares none: the project's own statement of what it is called, the way the detector reads it,
     *     so a report names a project the way its build does.
     */
    private static String projectName(Path cmakeFile) {
        return CppProjectDetector.projectName(CppProjectDetector.read(cmakeFile),
                cmakeFile == null ? null : cmakeFile.getParent());
    }

    /** @return the directory's own name, or {@code cpp-project} when it has none to give. */
    private static String directoryName(Path directory) {
        Path name = directory == null ? null : directory.getFileName();
        return name == null ? "cpp-project" : name.toString();
    }

    /**
     * @return the manifest to read: the one at the project root, or the one beside the top-level
     *     {@code CMakeLists.txt} when the project keeps its build files in a subdirectory, or {@code null}
     *     when there is none.
     */
    private static Path manifest(Path root, Path buildRoot, String name) {
        if (root == null) {
            return null;
        }
        Path atRoot = root.resolve(name);
        if (Files.isRegularFile(atRoot)) {
            return atRoot;
        }
        Path besideCmake = buildRoot == null ? null : buildRoot.resolve(name);
        return besideCmake != null && Files.isRegularFile(besideCmake) ? besideCmake : null;
    }

    /**
     * @return {@code true} when the text parses as a Conan resolution record, so that a
     *     {@code conan.lock} that exists but cannot be read is reported as unreadable rather than being
     *     mistaken for a project with no packages.
     */
    private static boolean isConanLock(String text) {
        JsonNode root = parse(text);
        return root != null && root.isObject()
                && (root.has("requires") || root.has("build_requires") || root.has("python_requires")
                        || root.has("version"));
    }

    /** @return the parsed JSON, or {@code null} when the text is blank or is not JSON at all. */
    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException _) {
            // A manifest that cannot be parsed is reported as unreadable by the caller; a verification
            // phase never throws for a file it was asked to read.
            return null;
        }
    }

    /** One {@code FetchContent_Declare}: the package it downloads and where its source comes from. */
    private record FetchContentDependency(String name, String display, boolean gitTag) {

        /** @return the coordinate this inventory reports: the package exists, its version is a tag. */
        private PackageCoordinate coordinate() {
            return new PackageCoordinate(PackageCoordinate.CONAN_CENTER, name, "", display);
        }
    }
}

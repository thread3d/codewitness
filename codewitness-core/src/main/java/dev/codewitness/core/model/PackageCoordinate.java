package dev.codewitness.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A dependency as an advisory service understands it: an ecosystem, a package name inside that
 * ecosystem, and the resolved version.
 *
 * <p>This type exists because a dependency inventory that is only a list of strings is only queryable
 * for whichever ecosystem happens to be encoded in those strings. Maven coordinates are
 * self-describing ({@code groupId:artifactId:type:version:scope}), so an inventory of them could be
 * parsed back apart; an npm package name or a Cargo crate name is not, and a bare name cannot be
 * queried at all because an advisory is a statement about a <em>version</em>. Carrying the ecosystem and
 * the version explicitly is what lets the same advisory scan cover every language CodeWitness
 * supports instead of only Maven.
 *
 * <p>An empty {@link #version()} is meaningful rather than missing data: it says the inventory knows
 * the package exists but not which version is resolved, so no advisory service can be asked about it.
 * {@link #resolved()} states that, and the report uses it to avoid presenting an unqueryable
 * inventory as a checked one.
 *
 * @param ecosystem advisory ecosystem exactly as the service names it, for example {@code Maven},
 *                  {@code npm}, {@code PyPI}, {@code crates.io} or {@code Go}
 * @param name      package name within that ecosystem, for example {@code org.slf4j:slf4j-api},
 *                  {@code express} or {@code github.com/pkg/errors}
 * @param version   resolved version, or the empty string when the inventory does not carry one
 * @param display   how the inventory reported this dependency, kept for the report; when absent it is
 *                  derived as {@code name@version}
 */
public record PackageCoordinate(String ecosystem, String name, String version, String display) {

    /** The Maven ecosystem id, as the advisory service spells it. */
    public static final String MAVEN = "Maven";
    /** The npm ecosystem id, which covers both JavaScript and TypeScript projects. */
    public static final String NPM = "npm";
    /** The PyPI ecosystem id. */
    public static final String PYPI = "PyPI";
    /** The crates.io ecosystem id. */
    public static final String CRATES_IO = "crates.io";
    /** The Go module ecosystem id. */
    public static final String GO = "Go";
    /** The NuGet ecosystem id, which covers .NET packages including C# and F# ones. */
    public static final String NUGET = "NuGet";

    public PackageCoordinate {
        Objects.requireNonNull(ecosystem, "ecosystem");
        Objects.requireNonNull(name, "name");
        ecosystem = ecosystem.strip();
        name = name.strip();
        version = version == null ? "" : version.strip();
        if (ecosystem.isEmpty()) {
            throw new IllegalArgumentException("ecosystem must not be empty");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (display == null || display.isBlank()) {
            display = version.isEmpty() ? name : name + "@" + version;
        } else {
            display = display.strip();
        }
    }

    /** @return {@code true} when this coordinate carries a version an advisory service can match on. */
    public boolean resolved() {
        return !version.isEmpty();
    }

    /** @return the coordinate as {@code ecosystem:name@version}, for messages and metadata. */
    public String describe() {
        return ecosystem + ":" + name + (version.isEmpty() ? "" : "@" + version);
    }

    /** @return an npm coordinate for a JavaScript or TypeScript package. */
    public static PackageCoordinate npm(String name, String version) {
        return new PackageCoordinate(NPM, name, version, null);
    }

    /** @return a PyPI coordinate for a Python distribution. */
    public static PackageCoordinate python(String name, String version) {
        return new PackageCoordinate(PYPI, name, version, null);
    }

    /** @return a crates.io coordinate for a Rust crate. */
    public static PackageCoordinate crate(String name, String version) {
        return new PackageCoordinate(CRATES_IO, name, version, null);
    }

    /** @return a Go coordinate for a module path at a resolved version. */
    public static PackageCoordinate go(String module, String version) {
        return new PackageCoordinate(GO, module, version, null);
    }

    /** @return a NuGet coordinate for a .NET package id at a resolved version. */
    public static PackageCoordinate nuget(String packageId, String version) {
        return new PackageCoordinate(NUGET, packageId, version, null);
    }

    /** @return a coordinate in an arbitrary ecosystem, for sources this class has no shortcut for. */
    public static PackageCoordinate of(String ecosystem, String name, String version) {
        return new PackageCoordinate(ecosystem, name, version, null);
    }

    /**
     * @return one coordinate per distinct {@code ecosystem:name:version}, in the order first seen.
     *
     * <p>A lock file normally lists each package once, but a workspace can resolve the same crate
     * twice, and a Maven reactor prints a dependency once per module that uses it. Querying the same
     * coordinate twice would inflate the reported coverage and waste the scan's request budget, so
     * duplicates are collapsed here rather than at the point of use.
     */
    public static List<PackageCoordinate> distinct(List<PackageCoordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return List.of();
        }
        Map<String, PackageCoordinate> unique = new LinkedHashMap<>();
        for (PackageCoordinate coordinate : coordinates) {
            if (coordinate != null) {
                unique.putIfAbsent(coordinate.ecosystem() + ":" + coordinate.name() + ":" + coordinate.version(),
                        coordinate);
            }
        }
        return List.copyOf(unique.values());
    }

    /**
     * Parses resolved Maven coordinates as {@code dependency:list} prints them.
     *
     * <p>The shape is {@code groupId:artifactId:type:version:scope}, with an extra classifier element
     * before the version when the dependency declares one, so the version is always the second to last
     * element. Anything that does not have at least that many elements is not a resolved coordinate and
     * is skipped rather than guessed at.
     *
     * @param coordinates dependency strings from a dependency inventory
     * @return one coordinate per parseable entry, in the order given
     */
    public static List<PackageCoordinate> parseMaven(List<String> coordinates) {
        List<PackageCoordinate> parsed = new ArrayList<>();
        if (coordinates == null) {
            return parsed;
        }
        for (String coordinate : coordinates) {
            if (coordinate == null) {
                continue;
            }
            String value = coordinate.strip();
            String[] parts = value.split(":");
            if (parts.length < 5) {
                continue;
            }
            String groupId = parts[0].strip();
            String artifactId = parts[1].strip();
            String version = parts[parts.length - 2].strip();
            if (groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()) {
                continue;
            }
            parsed.add(new PackageCoordinate(MAVEN, groupId + ":" + artifactId, version, value));
        }
        return List.copyOf(parsed);
    }

    private static String[] parts(String coordinate) {
        if (coordinate == null) {
            return new String[0];
        }
        String[] parts = coordinate.split(":");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].strip();
        }
        return parts;
    }

    /** @return the distinct ecosystems present, in first-seen order. */
    public static List<String> ecosystems(List<PackageCoordinate> coordinates) {
        LinkedHashSet<String> ecosystems = new LinkedHashSet<>();
        for (PackageCoordinate coordinate : coordinates == null ? List.<PackageCoordinate>of() : coordinates) {
            if (coordinate != null) {
                ecosystems.add(coordinate.ecosystem());
            }
        }
        return List.copyOf(ecosystems);
    }
}

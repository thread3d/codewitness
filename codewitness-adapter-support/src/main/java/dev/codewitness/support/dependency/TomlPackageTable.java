package dev.codewitness.support.dependency;

import dev.codewitness.core.model.PackageCoordinate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the resolved packages out of a {@code [[package]]} lock file.
 *
 * <p>{@code Cargo.lock} and {@code poetry.lock} are different formats by different tools but share the
 * one thing that matters here: both are machine-generated TOML whose resolved dependency list is a
 * sequence of {@code [[package]]} tables, each carrying a {@code name} and a {@code version}. A manifest
 * declares a version <em>range</em>, so this is the only place the installed version can be read from.
 *
 * <p>Only those two keys are read, and a table missing either is skipped rather than guessed at. That
 * matters more than it looks: {@code Cargo.lock} also holds {@code [[patch.unused]]} entries, and a
 * {@code [[package]]} block's own {@code dependencies} array lists names with no versions, so a reader
 * that scraped every {@code name =} line in the file would invent dependencies that do not exist.
 */
public final class TomlPackageTable {

    /** A table header, at the start of a line, with no indentation allowed inside a table. */
    private static final Pattern TABLE = Pattern.compile("^\\s*\\[\\[([^\\]]+)\\]\\]\\s*$");
    /** A key/value pair, quoted or bare, where the value is a quoted string. */
    private static final Pattern STRING_KEY = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\"([^\"]*)\"");

    private TomlPackageTable() {
    }

    /**
     * @param toml      contents of a lock file such as {@code Cargo.lock} or {@code poetry.lock}
     * @param ecosystem the advisory ecosystem the packages belong to
     * @return one coordinate per {@code [[package]]} table that declares both a name and a version
     */
    public static List<PackageCoordinate> parse(String toml, String ecosystem) {
        return parse(toml, ecosystem, false);
    }

    /**
     * @param toml          contents of a lock file such as {@code Cargo.lock}
     * @param ecosystem     the advisory ecosystem the packages belong to
     * @param publishedOnly {@code true} to keep only entries that name the registry they came from, which
     *                      is what separates a published crate from the one being built. A {@code Cargo.lock}
     *                      lists the workspace's own crate in its {@code [[package]]} tables alongside its
     *                      dependencies; poetry.lock lists only dependencies, so it has nothing to filter.
     * @return one coordinate per qualifying {@code [[package]]} table
     */
    public static List<PackageCoordinate> parse(String toml, String ecosystem, boolean publishedOnly) {
        List<PackageCoordinate> coordinates = new ArrayList<>();
        if (toml == null || toml.isBlank()) {
            return coordinates;
        }
        boolean inPackage = false;
        String name = null;
        String version = null;
        boolean published = false;
        for (String line : toml.split("\\R", -1)) {
            Matcher table = TABLE.matcher(line);
            if (table.matches()) {
                addIfComplete(coordinates, ecosystem, inPackage, name, version, published, publishedOnly);
                inPackage = "package".equals(table.group(1).strip());
                name = null;
                version = null;
                published = false;
                continue;
            }
            if (!inPackage) {
                continue;
            }
            Matcher key = STRING_KEY.matcher(line);
            if (key.find()) {
                switch (key.group(1)) {
                    case "name" -> name = key.group(2).strip();
                    case "version" -> version = key.group(2).strip();
                    // "source" names the registry or repository a crate came from. A crate built from this
                    // very tree has none.
                    case "source" -> published = !key.group(2).isBlank();
                    default -> {
                        // Every other key - checksum, dependencies, description - is not part of the
                        // coordinate. Ignoring them is what keeps a nested dependency name out of the
                        // inventory.
                    }
                }
            }
        }
        addIfComplete(coordinates, ecosystem, inPackage, name, version, published, publishedOnly);
        // A lock file can resolve the same crate at two versions for two dependents. Both are real, and
        // both are worth asking about, but each is asked about exactly once.
        Set<String> seen = new LinkedHashSet<>();
        List<PackageCoordinate> distinct = new ArrayList<>();
        for (PackageCoordinate coordinate : coordinates) {
            if (seen.add(coordinate.name() + "@" + coordinate.version())) {
                distinct.add(coordinate);
            }
        }
        return List.copyOf(distinct);
    }

    private static void addIfComplete(
            List<PackageCoordinate> coordinates, String ecosystem, boolean inPackage, String name, String version,
            boolean published, boolean publishedOnly) {
        if (!inPackage || name == null || name.isEmpty() || version == null || version.isEmpty()) {
            return;
        }
        if (publishedOnly && !published) {
            return;
        }
        coordinates.add(PackageCoordinate.of(ecosystem, name, version));
    }
}

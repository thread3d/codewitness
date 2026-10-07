package dev.codewitness.support.dependency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codewitness.core.model.PackageCoordinate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the resolved npm dependency graph out of a {@code package-lock.json}.
 *
 * <p>A manifest declares a version <em>range</em>; only the lock file says which version was actually
 * installed, and an advisory is a statement about a version. That is the whole reason this class
 * exists: without it an npm project's inventory can name its dependencies but cannot be asked whether
 * any of them is affected by anything.
 *
 * <p>Three lock file layouts are in the wild and all three are read:
 * <ul>
 *   <li>lockfile version 3 - a flat {@code packages} map keyed by install path;</li>
 *   <li>lockfile version 2 - the same {@code packages} map, plus a legacy {@code dependencies} tree;</li>
 *   <li>lockfile version 1 - only the {@code dependencies} tree, which nests a conflicting
 *       dependency under the package that pulls it in.</li>
 * </ul>
 *
 * <p>This is parsed with a real JSON parser rather than a regular expression. A lock file nests
 * objects that contain their own {@code version} fields, and misreading one of those as a package
 * version would produce an advisory finding against the wrong artifact - the exact failure this tool
 * must not have.
 */
public final class NodeLockFile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NodeLockFile() {
    }

    /**
     * @param json contents of a {@code package-lock.json}
     * @return the resolved npm coordinates it records, in the order the lock file lists them; empty when
     *     the file cannot be read as a lock file at all
     */
    public static List<PackageCoordinate> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception _) {
            // A lock file that cannot be parsed is not an empty dependency set. The caller decides what
            // to say about it; returning nothing here is the honest answer to "what did you read".
            return List.of();
        }
        Map<String, PackageCoordinate> resolved = new LinkedHashMap<>();
        JsonNode packages = root.path("packages");
        if (packages.isObject()) {
            // v2/v3: one flat entry per installed path, including nested copies of a conflicting version.
            packages.properties().forEach(entry -> {
                PackageCoordinate coordinate = packageEntry(entry.getKey(), entry.getValue());
                if (coordinate != null && coordinate.resolved()) {
                    resolved.putIfAbsent(key(coordinate), coordinate);
                }
            });
        }
        JsonNode dependencies = root.path("dependencies");
        if (dependencies.isObject()) {
            // v1 (and the legacy half of v2): a tree whose nested objects are conflict resolutions.
            collectTree(dependencies, resolved);
        }
        return List.copyOf(resolved.values());
    }

    /** @return the coordinate for one {@code packages} entry, or {@code null} when it is not a package. */
    private static PackageCoordinate packageEntry(String path, JsonNode entry) {
        if (path == null || path.isEmpty()) {
            // The empty key is the project itself, not one of its dependencies.
            return null;
        }
        String name = nameOfPath(path);
        if (name == null || entry.path("link").asBoolean(false)) {
            // A workspace link is a path into this repository, not a published package.
            return null;
        }
        return PackageCoordinate.npm(name, entry.path("version").asText(""));
    }

    /** @return the package name encoded in an install path such as {@code node_modules/a/node_modules/b}. */
    private static String nameOfPath(String path) {
        String marker = "node_modules/";
        int index = path.lastIndexOf(marker);
        if (index < 0) {
            return null;
        }
        String name = path.substring(index + marker.length());
        return name.isBlank() ? null : name;
    }

    /** Walks a lock file v1 {@code dependencies} tree, including each entry's own nested conflicts. */
    private static void collectTree(JsonNode dependencies, Map<String, PackageCoordinate> resolved) {
        dependencies.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            PackageCoordinate coordinate = PackageCoordinate.npm(name, value.path("version").asText(""));
            if (coordinate.resolved()) {
                resolved.putIfAbsent(key(coordinate), coordinate);
            }
            JsonNode nested = value.path("dependencies");
            if (nested.isObject()) {
                collectTree(nested, resolved);
            }
        });
    }

    private static String key(PackageCoordinate coordinate) {
        return coordinate.ecosystem() + ":" + coordinate.name() + ":" + coordinate.version();
    }
}

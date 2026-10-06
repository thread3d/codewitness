package dev.codewitness.core.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A reference to a project under verification.
 *
 * @param root       absolute path to the project root
 * @param name       human readable project name (from the build file when available)
 * @param identifier stable project identifier used for storage and reporting
 */
public record ProjectRef(Path root, String name, String identifier) {

    public ProjectRef {
        Objects.requireNonNull(root, "root");
        root = root.toAbsolutePath().normalize();
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(identifier, "identifier");
    }

    public static ProjectRef of(Path root) {
        Path absolute = root.toAbsolutePath().normalize();
        String name = absolute.getFileName() == null ? absolute.toString() : absolute.getFileName().toString();
        return new ProjectRef(absolute, name, deriveIdentifier(absolute, name));
    }

    /**
     * Derives a stable identifier for a project from its name and absolute location. Two runs against
     * the same checkout directory produce the same identifier, which is what makes verification
     * history comparable.
     */
    public static String deriveIdentifier(Path root, String name) {
        String slug = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        long hash = Integer.toUnsignedLong(root.toString().hashCode());
        return slug + "-" + Long.toString(hash, 36);
    }

    public Path resolve(String relative) {
        return root.resolve(relative);
    }

    @Override
    public String toString() {
        return name + " (" + root + ")";
    }
}

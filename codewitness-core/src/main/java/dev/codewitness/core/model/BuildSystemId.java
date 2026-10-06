package dev.codewitness.core.model;

import java.util.Locale;
import java.util.Objects;

/** Identifies the build system used by a project, for example Maven or Gradle. */
public record BuildSystemId(String id) {

    public static final BuildSystemId MAVEN = new BuildSystemId("maven");
    public static final BuildSystemId GRADLE = new BuildSystemId("gradle");
    public static final BuildSystemId NONE = new BuildSystemId("none");

    public BuildSystemId {
        Objects.requireNonNull(id, "id");
        id = id.toLowerCase(Locale.ROOT).trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("build system id must not be empty");
        }
    }

    public static BuildSystemId of(String id) {
        return new BuildSystemId(id);
    }

    @Override
    public String toString() {
        return id;
    }
}

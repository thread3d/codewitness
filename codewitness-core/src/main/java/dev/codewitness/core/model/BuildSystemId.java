package dev.codewitness.core.model;

import java.util.Locale;
import java.util.Objects;

/** Identifies the build system used by a project, for example Maven or Gradle. */
public record BuildSystemId(String id) {

    public static final BuildSystemId MAVEN = new BuildSystemId("maven");
    public static final BuildSystemId GRADLE = new BuildSystemId("gradle");
    /** Python packaging driven by {@code pyproject.toml}, {@code setup.py} or {@code requirements.txt}. */
    public static final BuildSystemId PYTHON = new BuildSystemId("python");
    /** Rust and Cargo, driven by {@code Cargo.toml} and {@code Cargo.lock}. */
    public static final BuildSystemId CARGO = new BuildSystemId("cargo");
    /** Go modules, driven by {@code go.mod} and {@code go.sum}. */
    public static final BuildSystemId GO = new BuildSystemId("go");
    /**
     * The Node package ecosystem, driven by {@code package.json}; the concrete package manager
     * (npm, pnpm, yarn or bun) is recorded in the project attributes rather than encoded here.
     */
    public static final BuildSystemId NODE = new BuildSystemId("node");
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

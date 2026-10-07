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
    /**
     * The .NET SDK, driven by {@code dotnet build} and {@code dotnet test} over a solution or one or
     * more {@code .csproj} projects; packages come from NuGet.
     */
    public static final BuildSystemId DOTNET = new BuildSystemId("dotnet");
    /**
     * CMake, driven by {@code cmake} over a {@code CMakeLists.txt}, with CTest for the tests and gcov
     * for coverage.
     *
     * <p>A C or C++ tree whose only build descriptor is a hand-written {@code Makefile} or a
     * {@code meson.build} is recognised as C or C++ and is not claimed by the adapter: driving every
     * build system would multiply the ways a build can be described without adding a way to verify a
     * requirement. CMake is the one the adapter drives, and it is recorded here rather than folded into
     * the language id because it is the build system, not the language.
     */
    public static final BuildSystemId CMAKE = new BuildSystemId("cmake");
    public static final BuildSystemId NONE = new BuildSystemId("none");
    /** More than one build system, because more than one language adapter claimed part of the project. */
    public static final BuildSystemId POLYGLOT = new BuildSystemId("polyglot");

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

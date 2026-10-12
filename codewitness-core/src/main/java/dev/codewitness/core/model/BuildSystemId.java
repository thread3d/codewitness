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
    /**
     * Composer, driven by {@code composer.json} and {@code composer.lock}, resolving PHP packages from
     * Packagist into {@code vendor}.
     *
     * <p>PHP has one package manager in practice, so this build system is what every PHP tree the
     * adapter claims is reported with. The Composer executable is recorded in the project attributes
     * rather than encoded here, because a project may pin its own copy or ship only a
     * {@code composer.phar}.
     */
    public static final BuildSystemId COMPOSER = new BuildSystemId("composer");
    /**
     * Bundler, driven by {@code Gemfile} and {@code Gemfile.lock}, resolving Ruby gems from RubyGems
     * into the bundle {@code bundle install} creates.
     *
     * <p>Ruby projects have one package manager in practice, so this build system is what every Ruby
     * tree the adapter claims is reported with. The Bundler executable is recorded in the project
     * attributes rather than encoded here, because a project may pin its own copy through
     * {@code .bundle/config} or ship only a binstub under {@code bin/}.
     */
    public static final BuildSystemId BUNDLER = new BuildSystemId("bundler");
    /**
     * Swift Package Manager, driven by {@code Package.swift} and {@code Package.resolved}, resolving
     * Swift packages from their git or registry locations into the {@code .build} scratch tree
     * {@code swift build} creates.
     *
     * <p>SwiftPM is the build system Apple ships with the compiler and the only one a Swift package can
     * declare, so it is what every Swift tree the adapter claims is reported with. The {@code swift}
     * executable is recorded in the project attributes rather than encoded here, because a machine may
     * have several toolchains selected through {@code swiftly}, Xcode or {@code PATH}.
     */
    public static final BuildSystemId SWIFTPM = new BuildSystemId("swiftpm");
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

package dev.codewitness.core.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Identifies the programming language of a project.
 *
 * <p>The verification core never switches on this value. It is used only for registry lookup and
 * reporting; behaviour lives in {@link dev.codewitness.core.spi.LanguageAdapter} implementations.
 */
public record LanguageId(String id) {

    public static final LanguageId JAVA = new LanguageId("java");
    public static final LanguageId PYTHON = new LanguageId("python");
    public static final LanguageId RUST = new LanguageId("rust");
    /** Go, built and tested with the {@code go} tool itself. */
    public static final LanguageId GO = new LanguageId("go");
    /** JavaScript, executed with Node; no compiler and no type check. */
    public static final LanguageId JAVASCRIPT = new LanguageId("javascript");
    /** TypeScript, executed with Node and optionally type checked with {@code tsc}. */
    public static final LanguageId TYPESCRIPT = new LanguageId("typescript");
    /**
     * C#, built and tested with the .NET SDK's {@code dotnet} driver.
     *
     * <p>The language is named for the language and not for the platform or the toolchain, exactly as
     * {@link #JAVA} is not called {@code maven}: {@code dotnet} is how this language is compiled, and
     * the build system is reported separately as {@link BuildSystemId#DOTNET}.
     */
    public static final LanguageId CSHARP = new LanguageId("csharp");
    /**
     * Kotlin on the JVM, compiled by {@code kotlinc} through the project's own Gradle or Maven build.
     *
     * <p>Kotlin is named for the language and not for the platform, exactly as C# is not called
     * {@code dotnet}: it shares a bytecode target, a build tool and a coverage agent with Java, and
     * none of that makes it Java. A tree holding both is polyglot, and each adapter verifies the
     * sources written in its own language.
     */
    public static final LanguageId KOTLIN = new LanguageId("kotlin");
    /**
     * C and C++, compiled and tested by the project's own CMake build and CTest.
     *
     * <p>One identifier covers both languages because one build system does: a {@code CMakeLists.txt}
     * declares C and C++ targets alike, they share a compiler driver, a test runner and a coverage
     * tool, and no project states which of the two it is in a way a build could act on. The adapter
     * reports the extensions it found so that a report says which of the two the tree actually holds
     * rather than implying the other. The language is named for the language and not for the toolchain,
     * exactly as {@link #CSHARP} is not called {@code dotnet}: the build system is reported separately as
     * {@link BuildSystemId#CMAKE}.
     */
    public static final LanguageId CPP = new LanguageId("cpp");
    /**
     * PHP, installed and tested with Composer and PHPUnit.
     *
     * <p>The language is named for the language and not for the package manager, exactly as
     * {@link #CSHARP} is not called {@code dotnet}: Composer resolves this language's dependencies and
     * is reported separately as {@link BuildSystemId#COMPOSER}. PHP has no separate compilation step,
     * but it does have a well defined first failure - a file the interpreter cannot parse - and that is
     * checked with the interpreter's own parser so a file that does not parse is BLOCKED rather than
     * reported as a code defect.
     */
    public static final LanguageId PHP = new LanguageId("php");
    /**
     * Ruby, installed and tested with Bundler and RSpec.
     *
     * <p>The language is named for the language and not for the package manager, exactly as
     * {@link #PHP} is not called {@code composer}: Bundler resolves this language's gems and is reported
     * separately as {@link BuildSystemId#BUNDLER}. Ruby has no separate compilation step, but it does
     * have a well defined first failure - a file the interpreter cannot parse - and that is checked with
     * the interpreter's own parser so a file that does not parse is BLOCKED rather than reported as a code
     * defect.
     */
    public static final LanguageId RUBY = new LanguageId("ruby");
    /**
     * Swift, built and tested with the Swift Package Manager the project already has.
     *
     * <p>The language is named for the language and not for the toolchain, exactly as {@link #CSHARP}
     * is not called {@code dotnet}: SwiftPM resolves this language's packages, compiles the modules and
     * runs the XCTest suite, and is reported separately as {@link BuildSystemId#SWIFTPM}. Swift has a
     * separate compilation step, so a file the compiler rejects is a failed build - which the engine
     * maps to BLOCKED - rather than a code defect.
     */
    public static final LanguageId SWIFT = new LanguageId("swift");
    /**
     * Verilog, and the SystemVerilog dialect of it, compiled by Icarus Verilog and simulated with
     * {@code vvp}.
     *
     * <p>One identifier covers Verilog and SystemVerilog because one toolchain does: {@code iverilog}
     * accepts both from the same command line ({@code -g2012} selects the SystemVerilog dialect), they
     * share a source-file vocabulary ({@code .v} and {@code .sv} are the same language to the
     * compiler), a testbench, a simulator and a simulator's output. The adapter reports the extensions
     * it found so that a report says which of the two a tree actually holds. The language is named for
     * the language and not for the tool, exactly as {@link #CPP} is not called {@code cmake}: the build
     * system is reported separately as {@link BuildSystemId#ICARUS}.
     *
     * <p>Verilog is not a software language with a runtime: its unit of execution is a testbench, a
     * top-level module with no ports that drives the design and calls {@code $finish}. The compile is
     * a real first failure - a file the compiler rejects is BLOCKED, not a code defect - and the
     * simulation is the test.
     */
    public static final LanguageId VERILOG = new LanguageId("verilog");
    /**
     * VHDL, analysed and simulated with GHDL.
     *
     * <p>The language is named for the language and not for the tool, exactly as {@link #VERILOG} is
     * not called {@code icarus}: the build system is reported separately as {@link BuildSystemId#GHDL}.
     * VHDL has a real separation between analysis and elaboration - a design unit is compiled into a
     * library, and only an elaborated entity can be simulated - so a design unit the analyzer rejects is
     * a failed build, which the engine maps to BLOCKED, rather than a code defect.
     *
     * <p>As with Verilog, VHDL is not a software language with a runtime: its unit of execution is a
     * testbench, an entity with no ports that instantiates the design and drives it. The adapter reports
     * one test case per testbench, because VHDL gives a command-line caller no case-level protocol.
     */
    public static final LanguageId VHDL = new LanguageId("vhdl");
    /**
     * Pascal, compiled with Free Pascal and tested with the fpcunit framework that ships with it.
     *
     * <p>The language is named for the language and not for the compiler, exactly as {@link #CSHARP}
     * is not called {@code dotnet}: the build system is reported separately as {@link BuildSystemId#FPC}.
     * Pascal has a separate compilation step, so a file the compiler rejects is a failed build - which
     * the engine maps to BLOCKED - rather than a code defect. A Pascal test suite is compiled into the
     * same executable as the code under test and run in-process, so the adapter compiles and runs each
     * test program the project declares.
     */
    public static final LanguageId PASCAL = new LanguageId("pascal");
    /**
     * SQL: schema, migrations, views and queries, loaded into an ephemeral database.
     *
     * <p>SQL has no compiler driver of its own, so the language's first failure is defined by the
     * adapter rather than by a tool: the declared schema and migrations are applied to a fresh database,
     * and a script the database rejects is a failed build - which the engine maps to BLOCKED - rather
     * than a defect. The language is named for the language and not for the engine that runs it, exactly
     * as {@link #CSHARP} is not called {@code dotnet}: the database engine is reported separately, by the
     * database fixture that prepared it.
     */
    public static final LanguageId SQL = new LanguageId("sql");
    /**
     * More than one language, verified together because more than one adapter claimed part of the
     * project. Reported instead of silently naming whichever language happened to be inspected first.
     */
    public static final LanguageId POLYGLOT = new LanguageId("polyglot");
    public static final LanguageId UNKNOWN = new LanguageId("unknown");

    public LanguageId {
        Objects.requireNonNull(id, "id");
        id = id.toLowerCase(Locale.ROOT).trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("language id must not be empty");
        }
    }

    public static LanguageId of(String id) {
        return new LanguageId(id);
    }

    @Override
    public String toString() {
        return id;
    }
}

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

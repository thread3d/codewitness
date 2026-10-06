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

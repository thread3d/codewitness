package dev.codewitness.core.model;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Identifier of a verification run.
 *
 * <p>Format: {@code CW-YYYY-MM-DD-XXXXX} where {@code XXXXX} is a short base32 suffix, for example
 * {@code CW-2026-10-06-7F31A}. Every piece of evidence produced by a run references its run id.
 */
public record RunId(String value) {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int SUFFIX_LENGTH = 5;

    public RunId {
        Objects.requireNonNull(value, "value");
        if (!value.startsWith("CW-") || value.length() < 12) {
            throw new IllegalArgumentException("invalid run id: " + value);
        }
    }

    /** Generates a run id for the given date using the supplied random source. */
    public static RunId generate(LocalDate date, RandomGenerator random) {
        StringBuilder sb = new StringBuilder("CW-").append(date).append('-');
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return new RunId(sb.toString().toUpperCase(Locale.ROOT));
    }

    public static RunId generate() {
        return generate(LocalDate.now(ZoneId.systemDefault()), new java.security.SecureRandom());
    }

    @Override
    public String toString() {
        return value;
    }
}

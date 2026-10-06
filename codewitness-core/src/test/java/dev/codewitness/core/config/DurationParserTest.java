package dev.codewitness.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Durations in configuration are written the way a human writes them. */
class DurationParserTest {

    private static final Duration FALLBACK = Duration.ofSeconds(7);

    private static Duration parse(String text) {
        return DurationParser.parse(text, FALLBACK);
    }

    @Test
    @DisplayName("15m is fifteen minutes")
    void parsesMinutes() {
        assertEquals(Duration.ofMinutes(15), parse("15m"));
    }

    @Test
    @DisplayName("90s is ninety seconds")
    void parsesSeconds() {
        assertEquals(Duration.ofSeconds(90), parse("90s"));
    }

    @Test
    @DisplayName("2h is two hours")
    void parsesHours() {
        assertEquals(Duration.ofHours(2), parse("2h"));
    }

    @Test
    @DisplayName("a bare number is seconds")
    void parsesBareNumberAsSeconds() {
        assertEquals(Duration.ofSeconds(45), parse("45"));
    }

    @Test
    @DisplayName("ISO-8601 durations such as PT15M are accepted")
    void parsesIsoDuration() {
        assertEquals(Duration.ofMinutes(15), parse("PT15M"));
    }

    @Test
    @DisplayName("compound durations such as '1h 30m' add up")
    void parsesCompoundDuration() {
        assertEquals(Duration.ofMinutes(90), parse("1h 30m"));
    }

    @Test
    @DisplayName("long unit names are accepted")
    void parsesLongUnitNames() {
        assertEquals(Duration.ofMinutes(5), parse("5 minutes"));
        assertEquals(Duration.ofDays(1), parse("1 day"));
    }

    @Test
    @DisplayName("surrounding whitespace and case do not matter")
    void parsingIsTrimmedAndCaseInsensitive() {
        assertEquals(Duration.ofMinutes(15), parse("  15M  "));
    }

    @Test
    @DisplayName("null, blank and invalid text fall back")
    void invalidTextFallsBack() {
        assertEquals(FALLBACK, parse(null));
        assertEquals(FALLBACK, parse(""));
        assertEquals(FALLBACK, parse("   "));
        assertEquals(FALLBACK, parse("soon"));
        assertEquals(FALLBACK, parse("5 fortnights"));
    }

    @Test
    @DisplayName("sub-second values truncate to zero rather than throwing")
    void millisecondsTruncate() {
        assertEquals(Duration.ZERO, parse("500ms"));
    }
}

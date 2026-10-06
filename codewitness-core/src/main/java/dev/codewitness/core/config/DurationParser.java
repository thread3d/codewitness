package dev.codewitness.core.config;

import java.time.Duration;
import java.util.Locale;

/** Parses human friendly durations such as {@code 15m}, {@code 90s}, {@code 2h} or {@code PT15M}. */
public final class DurationParser {

    private DurationParser() {
    }

    public static Duration parse(String text, Duration fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.startsWith("p")) {
                return Duration.parse(text.trim().toUpperCase(Locale.ROOT));
            }
            if (value.matches("\\d+")) {
                return Duration.ofSeconds(Long.parseLong(value));
            }
            long totalSeconds = 0;
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("(\\d+)\\s*([a-z]+)").matcher(value);
            boolean any = false;
            while (matcher.find()) {
                any = true;
                long amount = Long.parseLong(matcher.group(1));
                switch (matcher.group(2)) {
                    case "ms" -> totalSeconds += amount / 1000;
                    case "s", "sec", "secs", "second", "seconds" -> totalSeconds += amount;
                    case "m", "min", "mins", "minute", "minutes" -> totalSeconds += amount * 60;
                    case "h", "hr", "hrs", "hour", "hours" -> totalSeconds += amount * 3600;
                    case "d", "day", "days" -> totalSeconds += amount * 86400;
                    default -> {
                        return fallback;
                    }
                }
            }
            if (!any) {
                return fallback;
            }
            return Duration.ofSeconds(totalSeconds);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}

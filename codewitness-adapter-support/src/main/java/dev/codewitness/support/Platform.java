package dev.codewitness.support;

import java.util.Locale;

/**
 * Host platform facts shared by more than one adapter.
 *
 * <p>Every adapter that resolves a tool has to know whether to look for {@code cargo} or
 * {@code cargo.exe}, and the answer is the same everywhere. The check lived as an identical copy in
 * five adapters.
 */
public final class Platform {

    private Platform() {
    }

    /** @return {@code true} when running on Windows, where executables carry an extension. */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

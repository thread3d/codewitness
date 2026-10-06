package dev.codewitness.core.spi;

/** Receives progress events while a verification run proceeds. */
@FunctionalInterface
public interface ProgressListener {

    /**
     * @param phase   phase identifier, for example {@code build} or {@code generated-tests}
     * @param message human readable progress message
     */
    void onEvent(String phase, String message);

    /** A listener that discards everything, used in tests. */
    static ProgressListener silent() {
        return (phase, message) -> {
        };
    }
}

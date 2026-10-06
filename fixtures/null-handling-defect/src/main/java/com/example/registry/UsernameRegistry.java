package com.example.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A case-insensitive registry of usernames.
 *
 * <p>This is a CodeWitness fixture. It contains a deliberately introduced defect; it is not an
 * example of good code.
 *
 * <p>Deliberate defect:
 * <ol>
 *   <li>{@link #register(String)} dereferences {@code username} with {@code username.trim()}
 *       <em>before</em> checking it for null. A null username therefore throws
 *       {@link NullPointerException}, although {@link Outcome#REJECTED_NULL} exists precisely so that
 *       the caller can be told about the rejection explicitly. The existing tests never pass null,
 *       so the defect is invisible to the project's own suite.</li>
 * </ol>
 */
public final class UsernameRegistry {

    /** The outcome of an attempted registration. */
    public enum Outcome {
        /** The username was stored. */
        ACCEPTED,
        /** The username was null and was refused. */
        REJECTED_NULL,
        /** The username was empty or only whitespace and was refused. */
        REJECTED_BLANK
    }

    private final List<String> usernames = new ArrayList<>();

    /**
     * Attempts to register a username.
     *
     * @param username username to register; a null username must be rejected, not dereferenced
     * @return the outcome of the attempt
     */
    public Outcome register(String username) {
        // DEFECT: the null check is missing. This line dereferences `username` before anything
        // checks it, so a null username throws NullPointerException instead of returning
        // REJECTED_NULL. The compiler accepts it and the project's tests never pass null.
        String normalised = username.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            return Outcome.REJECTED_BLANK;
        }
        usernames.add(normalised);
        return Outcome.ACCEPTED;
    }

    /**
     * @param username username to look for
     * @return {@code true} when the username is registered
     */
    public boolean contains(String username) {
        return usernames.contains(username.trim().toLowerCase(Locale.ROOT));
    }

    /** @return the number of registered usernames. */
    public int size() {
        return usernames.size();
    }
}

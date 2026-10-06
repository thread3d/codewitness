package com.example.registry;

import com.example.registry.UsernameRegistry.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's own tests.
 *
 * <p>They cover the happy path and the blank-username rejection, but they never pass null. That is
 * the point of the fixture: the suite passes while the entry point throws
 * {@link NullPointerException} for the input the requirement is actually about.
 */
class UsernameRegistryTest {

    @Test
    void acceptsANonBlankUsername() {
        UsernameRegistry registry = new UsernameRegistry();
        assertEquals(Outcome.ACCEPTED, registry.register("Alice"));
        assertTrue(registry.contains("alice"));
    }

    @Test
    void rejectsABlankUsername() {
        UsernameRegistry registry = new UsernameRegistry();
        assertEquals(Outcome.REJECTED_BLANK, registry.register("   "));
    }
}

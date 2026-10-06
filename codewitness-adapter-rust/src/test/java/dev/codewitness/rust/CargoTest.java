package dev.codewitness.rust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Network policy is enforced through Cargo's own offline switch, not assumed. */
class CargoTest {

    @Test
    @DisplayName("a run without network access forces Cargo offline")
    void forcesOfflineWhenNetworkIsDisallowed() {
        Map<String, String> environment = Cargo.environment(false);

        assertTrue("true".equals(environment.get("CARGO_NET_OFFLINE")),
                "Cargo must be told to stay offline: " + environment);
        assertTrue("never".equals(environment.get("CARGO_TERM_COLOR")));
    }

    @Test
    @DisplayName("a run with network access does not force Cargo offline")
    void allowsNetworkWhenConfigured() {
        Map<String, String> environment = Cargo.environment(true);

        assertFalse(environment.containsKey("CARGO_NET_OFFLINE"), environment.toString());
    }
}

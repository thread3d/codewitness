package dev.codewitness.typescript;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime version handling and the environment a Node command runs with. */
class NodeRuntimeTest {

    @Test
    @DisplayName("only Node 22.18 and later can execute TypeScript by stripping types")
    void recognisesTypeStrippingVersions() {
        assertTrue(NodeRuntime.supportsTypeStripping("v24.21.0"));
        assertTrue(NodeRuntime.supportsTypeStripping("v22.18.0"));
        assertTrue(NodeRuntime.supportsTypeStripping("v23.0.0"));
        assertFalse(NodeRuntime.supportsTypeStripping("v22.17.9"));
        assertFalse(NodeRuntime.supportsTypeStripping("v22.5.9"));
        assertFalse(NodeRuntime.supportsTypeStripping("v20.11.1"));
        assertFalse(NodeRuntime.supportsTypeStripping(null));
        assertFalse(NodeRuntime.supportsTypeStripping(""));
    }

    @Test
    @DisplayName("the minimum is the release that enables type stripping by default, not the flag's release")
    void minimumVersionIsTheDefaultEnablementRelease() {
        // --experimental-strip-types exists from 22.6.0 and module.stripTypeScriptTypes from 22.13.0,
        // but neither runs a .ts file without an explicit flag; 22.18.0 is the first that does.
        assertEquals("22.18.0", NodeRuntime.MINIMUM_TYPE_STRIPPING_VERSION);
        assertFalse(NodeRuntime.supportsTypeStripping("v22.13.0"));
        assertFalse(NodeRuntime.supportsTypeStripping("v22.17.9"));
        assertTrue(NodeRuntime.supportsTypeStripping("v22.18.0"));
    }

    @Test
    @DisplayName("the runtime warning names the missing capability and the version that provides it")
    void warningNamesTheCapabilityAndTheRequiredVersion() {
        String warning = NodeRuntime.typeStrippingWarning("v22.10.0");

        assertTrue(warning.contains("v22.10.0"), warning);
        assertTrue(warning.contains("stripping types"), warning);
        assertTrue(warning.contains(NodeRuntime.MINIMUM_TYPE_STRIPPING_VERSION), warning);
    }

    @Test
    @DisplayName("a run that may not reach the network tells the package manager to stay offline")
    void environmentHonoursNetworkPolicy() {
        Map<String, String> offline = NodeRuntime.environment(false);
        assertTrue(offline.containsKey("npm_config_offline"));
        assertTrue(offline.containsKey("PNPM_OFFLINE"));
        assertTrue(offline.containsKey("YARN_ENABLE_NETWORK"));
        assertEquals("1", offline.get("NO_COLOR"));

        Map<String, String> online = NodeRuntime.environment(true);
        assertFalse(online.containsKey("npm_config_offline"));
        assertTrue(online.containsKey("NO_COLOR"));
    }

    @Test
    @DisplayName("a Node executable is always resolved, even when it is only on PATH")
    void resolvesNode() {
        assertNotNull(NodeRuntime.resolve(null, Map.of()));
        assertNotNull(NodeRuntime.resolve(null, Map.of("PATH", "/usr/local/bin:/usr/bin")));
    }
}

package dev.codewitness.javascript;

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
    @DisplayName("Node 18.17 and later can write the JUnit report the test phase reads")
    void recognisesTestRunnerVersions() {
        assertEquals("18.17.0", NodeRuntime.MINIMUM_TEST_RUNNER_VERSION);
        assertTrue(NodeRuntime.supportsTestRunner("v18.17.0"));
        assertTrue(NodeRuntime.supportsTestRunner("v18.17"));
        assertTrue(NodeRuntime.supportsTestRunner("v20.11.1"));
        assertTrue(NodeRuntime.supportsTestRunner("v22.6.0"));
        assertTrue(NodeRuntime.supportsTestRunner("v24.21.0"));
        assertFalse(NodeRuntime.supportsTestRunner("v18.16.9"));
        assertFalse(NodeRuntime.supportsTestRunner("v16.20.2"));
        assertFalse(NodeRuntime.supportsTestRunner(null));
        assertFalse(NodeRuntime.supportsTestRunner(""));
    }

    @Test
    @DisplayName("a run that may not reach the network tells the package manager to stay offline")
    void environmentHonoursNetworkPolicy() {
        Map<String, String> offline = NodeRuntime.environment(false);
        assertTrue(offline.containsKey("npm_config_offline"));
        assertTrue(offline.containsKey("PNPM_OFFLINE"));
        assertTrue(offline.containsKey("YARN_ENABLE_NETWORK"));
        assertEquals("1", offline.get("NO_COLOR"));
        assertEquals("0", offline.get("FORCE_COLOR"));
        assertEquals("UTC", offline.get("TZ"));

        Map<String, String> online = NodeRuntime.environment(true);
        assertFalse(online.containsKey("npm_config_offline"));
        assertFalse(online.containsKey("PNPM_OFFLINE"));
        assertFalse(online.containsKey("YARN_ENABLE_NETWORK"));
        assertTrue(online.containsKey("NO_COLOR"));
    }

    @Test
    @DisplayName("a Node executable is always resolved, even when it is only on PATH")
    void resolvesNode() {
        assertNotNull(NodeRuntime.resolve(null, Map.of()));
        assertNotNull(NodeRuntime.resolve(null, Map.of("PATH", "/usr/local/bin:/usr/bin")));
    }

    @Test
    @DisplayName("a missing probe is a reported outcome, not a crash")
    void describesWhatNodeReported() {
        assertTrue(new NodeRuntime.Probe("v20.11.1", true, null).available());
        assertFalse(new NodeRuntime.Probe(null, false, "node was not found").available());
        assertEquals("node was not found", new NodeRuntime.Probe(null, false, "node was not found").failureReason());
    }

    @Test
    @DisplayName("only the last lines of a captured output are kept for a report")
    void keepsTheLastLines() {
        assertEquals("(no output)", NodeRuntime.lastLines(null, 3));
        assertEquals("(no output)", NodeRuntime.lastLines("   ", 3));
        assertEquals("b | c | ", NodeRuntime.lastLines("a\nb\nc", 2));
        assertEquals("only | ", NodeRuntime.lastLines("only", 5));
    }
}

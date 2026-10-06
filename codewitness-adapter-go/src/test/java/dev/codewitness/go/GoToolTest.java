package dev.codewitness.go;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Toolchain resolution and the sandboxed Go environment. */
class GoToolTest {

    @Test
    @DisplayName("Go caches live inside the run, not in the user's home")
    void cachesLiveUnderTheRunDirectory(@TempDir Path cache) {
        Map<String, String> environment = GoTool.environment(false, cache);

        assertTrue(environment.get("GOCACHE").startsWith(cache.toString()), environment.toString());
        assertTrue(environment.get("GOPATH").startsWith(cache.toString()), environment.toString());
        assertEquals("local", environment.get("GOTOOLCHAIN"));
    }

    @Test
    @DisplayName("a run that may not reach the network forces the module proxy off")
    void offlineRunDisablesTheProxy(@TempDir Path cache) {
        Map<String, String> offline = GoTool.environment(false, cache);
        Map<String, String> online = GoTool.environment(true, cache);

        assertEquals("off", offline.get("GOPROXY"));
        assertFalse(online.containsKey("GOPROXY"), "an online run must not force a proxy: " + online);
    }

    @Test
    @DisplayName("cgo is disabled so no host C compiler is needed or trusted")
    void disablesCgo(@TempDir Path cache) {
        assertEquals("0", GoTool.environment(true, cache).get("CGO_ENABLED"));
    }

    @Test
    @DisplayName("the go command resolves to something usable even without a filesystem hit")
    void resolvesAFallbackCommand() {
        assertNotNull(GoTool.resolve(Path.of("/nonexistent-project"), Map.of()));
        assertFalse(GoTool.resolve(Path.of("/nonexistent-project"), Map.of()).isBlank());
    }
}

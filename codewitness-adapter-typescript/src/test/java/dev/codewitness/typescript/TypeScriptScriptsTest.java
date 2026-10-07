package dev.codewitness.typescript;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emitted build helper is itself the artefact under test: it is a Node script the adapter writes
 * into the run's artefact store and then executes, so its text is asserted directly.
 *
 * <p>These assertions are deliberately string level. They pin the two behaviours that a reader of the
 * emitted script would have to rely on: the path separator normalisation and the diagnostic location.
 */
class TypeScriptScriptsTest {

    @Test
    @DisplayName("the helper normalises backslashes and never replaces pipe characters")
    void normalisesBackslashesNotPipes() {
        // JavaScript regex /\\/g replaces a literal backslash; /\|/g would replace a pipe. The twin in
        // codewitness-adapter-javascript is the reference implementation.
        assertTrue(TypeScriptScripts.BUILD_CHECK.contains("replace(/\\\\/g, \"/\")"),
                "the helper must replace backslashes, like the JavaScript twin");
        assertFalse(TypeScriptScripts.BUILD_CHECK.contains("replace(/\\|/g"),
                "the helper must not replace pipe characters");
        assertTrue(TypeScriptScripts.BUILD_CHECK.contains("replace(/\\s+/g, \" \")"),
                "whitespace collapsing is still expected");
    }

    @Test
    @DisplayName("the helper reads the reported file and line out of the stripper error")
    void reportsTheLocationTheStripperReported() {
        // The same file:line shape the JavaScript twin parses out of `node --check` stderr.
        assertTrue(TypeScriptScripts.BUILD_CHECK.contains("/^(.+?):(\\d+)\\s*$/m"),
                "the helper must look for the reported file:line");
        assertTrue(TypeScriptScripts.BUILD_CHECK.contains("found.file"),
                "the diagnostic must use the reported file when there is one");
        assertTrue(TypeScriptScripts.BUILD_CHECK.contains("found.line"),
                "the diagnostic must use the reported line when there is one");
        assertFalse(TypeScriptScripts.BUILD_CHECK.contains("emit(\"ERROR\", file, 0, 0"),
                "the diagnostic must not hard-code line 0, column 0");
    }
}

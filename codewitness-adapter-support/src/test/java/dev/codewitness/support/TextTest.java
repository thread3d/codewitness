package dev.codewitness.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One implementation of "the tail of a tool's output" for every adapter.
 *
 * <p>Five adapters had their own copy and three of them had diverged by stripping each line, so the same
 * compiler output was quoted with different whitespace in a Kotlin report than in a Java one. These tests pin
 * the shared behaviour, including the whitespace, because that drift is what this extraction exists to stop.
 */
class TextTest {

    @Test
    @DisplayName("nothing to show is said out loud, not left blank")
    void blankInputSaysSo() {
        assertEquals("(no output)", Text.lastLines(null, 3));
        assertEquals("(no output)", Text.lastLines("   \n\t\n", 3));
    }

    @Test
    @DisplayName("only the last N lines are kept, joined by a separator")
    void keepsTheTail() {
        assertEquals("b | c | ", Text.lastLines("a\nb\nc", 2));
        assertEquals("a | b | c | ", Text.lastLines("a\nb\nc", 10));
    }

    @Test
    @DisplayName("a kept line's own leading whitespace is preserved")
    void preservesIndentation() {
        // Kotlin's copies stripped each line, which is invisible until a compiler's caret-indented error text
        // is quoted back at the user and no longer lines up with the line it is about. The whole text is
        // stripped at its outer edges - that is what stops a note starting with a blank line - but the lines
        // inside it keep the indentation the tool gave them.
        assertEquals("  detail | ", Text.lastLines("error: bad\n  detail", 1).replace("\n", ""));
        assertEquals("b |   detail | ", Text.lastLines("a\nb\n  detail", 2));
    }

    @Test
    @DisplayName("handles the carriage returns a Windows toolchain emits")
    void handlesCarriageReturns() {
        String out = Text.lastLines("first\r\nsecond\r\n", 1);
        assertTrue(out.contains("second"), out);
        assertTrue(!out.contains("first"), "only the tail is kept: " + out);
    }
}

package dev.codewitness.verilog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads module declarations out of Verilog source text.
 *
 * <p>The adapter needs three facts from a tree: which modules exist, which of them are testbenches,
 * and what ports the design under test declares. Reading them is a lexical job rather than a parse:
 * the header of a module declaration is a small, regular shape, and the body only has to be scanned
 * for the markers that make a testbench a testbench. Nothing here evaluates or elaborates a design,
 * which is the same choice every other adapter makes - detection never runs project code.
 *
 * <p>Both header styles are read. In the ANSI style every port carries its own direction and type
 * ({@code module m(input clk, output reg [7:0] q);}); in the non-ANSI style the header names the ports
 * and the body declares them ({@code module m(clk, q); input clk; output [7:0] q;}). The direction of
 * a non-ANSI port is therefore {@code unknown} until the body is read, and the adapter treats such a
 * module as a design rather than a testbench, because the port list alone cannot say.
 */
public final class VerilogModuleParser {

    /** A block comment, a line comment or a double-quoted string, all of which may hide a keyword. */
    private static final Pattern HIDDEN =
            Pattern.compile("//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"", Pattern.DOTALL);

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final Pattern DIRECTION = Pattern.compile("^(input|output|inout)\\b");
    private static final Pattern NET_TYPE = Pattern.compile("^(wire|reg|logic|bit|tri|wand|wor)\\b");

    /** Markers that a testbench almost always carries and design logic almost never does. */
    private static final List<String> TESTBENCH_MARKERS =
            List.of("$finish", "$fatal", "$dumpfile", "$dumpvars", "$stop");

    private VerilogModuleParser() {
    }

    /** @return every module declared in a file, in declaration order. */
    static List<VerilogModule> parse(Path file) {
        String raw = read(file);
        if (raw.isBlank()) {
            return List.of();
        }
        String source = stripCommentsAndStrings(raw);
        boolean testbench = looksLikeTestbench(file, source);
        List<VerilogModule> modules = new ArrayList<>();
        int index = 0;
        while (true) {
            int at = findKeyword(source, "module", index);
            if (at < 0) {
                break;
            }
            Declaration declaration = readDeclaration(source, at);
            if (declaration == null) {
                index = at + "module".length();
                continue;
            }
            index = Math.max(declaration.end(), at + "module".length());
            String name = declaration.name();
            if (name.isEmpty()) {
                continue;
            }
            List<VerilogModule.Port> ports = parsePorts(declaration.portList());
            boolean moduleIsTestbench = testbench || isTestbenchName(name) || (ports.isEmpty()
                    && containsAny(source, TESTBENCH_MARKERS));
            modules.add(new VerilogModule(name, file, ports, moduleIsTestbench));
        }
        return modules;
    }

    /** @return {@code true} when a file's name or content says it holds a testbench. */
    static boolean looksLikeTestbench(Path file, String strippedSource) {
        if (file != null) {
            Path parent = file.getParent();
            if (parent != null && isTestbenchDirectoryName(parent.getFileName() == null
                    ? "" : parent.getFileName().toString())) {
                return true;
            }
            String stem = stem(file.getFileName() == null ? "" : file.getFileName().toString());
            if (isTestbenchName(stem)) {
                return true;
            }
        }
        // A file with no module at all cannot be a testbench; a marker alone is not enough, because a
        // design module may call $finish in a self-checking block. Requiring the marker and an initial
        // block keeps the false-positive rate low without reading the design.
        return strippedSource != null
                && containsAny(strippedSource, TESTBENCH_MARKERS)
                && strippedSource.contains("initial");
    }

    /**
     * @return {@code true} when a module or file name follows a testbench convention:
     *     {@code ledger_tb}, {@code tb_ledger}, {@code ledger_test}, {@code ledger_testbench}.
     */
    public static boolean isTestbenchName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("_tb") || lower.startsWith("tb_") || lower.equals("tb")
                || lower.endsWith("_test") || lower.endsWith("_testbench")
                || lower.startsWith("testbench") || lower.contains("_testbench");
    }

    private static boolean isTestbenchDirectoryName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.equals("tb") || lower.equals("testbench") || lower.equals("testbenches")
                || lower.equals("test") || lower.equals("tests") || lower.equals("sim")
                || lower.equals("simulation");
    }

    /** @return the source with every comment and string literal replaced by spaces, offsets preserved. */
    static String stripCommentsAndStrings(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        Matcher matcher = HIDDEN.matcher(source);
        StringBuilder sb = new StringBuilder(source.length());
        int last = 0;
        while (matcher.find()) {
            sb.append(source, last, matcher.start());
            for (int i = matcher.start(); i < matcher.end(); i++) {
                // Blank the comment or literal but keep its newlines, so an offset or line number
                // computed against this view still matches the original file.
                sb.append(source.charAt(i) == '\n' ? '\n' : ' ');
            }
            last = matcher.end();
        }
        sb.append(source, last, source.length());
        return sb.toString();
    }

    /** @return the offset of {@code module} as a whole word at or after {@code from}, or {@code -1}. */
    private static int findKeyword(String source, String keyword, int from) {
        int index = from;
        while (index >= 0) {
            index = source.indexOf(keyword, index);
            if (index < 0) {
                return -1;
            }
            boolean startsWord = index == 0 || !isIdentifierChar(source.charAt(index - 1));
            int after = index + keyword.length();
            boolean endsWord = after >= source.length() || !isIdentifierChar(source.charAt(after));
            if (startsWord && endsWord) {
                return index;
            }
            index = after;
        }
        return -1;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** @return the parsed header, or {@code null} when it is not a well formed module declaration. */
    private static Declaration readDeclaration(String source, int at) {
        int i = at + "module".length();
        i = skipWhitespace(source, i);
        Matcher name = IDENTIFIER.matcher(source);
        name.region(i, source.length());
        if (!name.lookingAt()) {
            return null;
        }
        String moduleName = name.group();
        i = name.end();
        i = skipWhitespace(source, i);
        if (i < source.length() && source.charAt(i) == '#') {
            i = skipWhitespace(source, i + 1);
            if (i < source.length() && source.charAt(i) == '(') {
                i = matching(source, i, '(', ')');
                if (i < 0) {
                    return null;
                }
            }
            i = skipWhitespace(source, i);
        }
        String portList = "";
        if (i < source.length() && source.charAt(i) == '(') {
            int close = matching(source, i, '(', ')');
            if (close < 0) {
                return null;
            }
            // matching() returns the offset just past the closing parenthesis.
            portList = source.substring(i + 1, close - 1);
            i = close;
        }
        i = skipWhitespace(source, i);
        if (i >= source.length() || source.charAt(i) != ';') {
            return null;
        }
        return new Declaration(moduleName, portList, i + 1);
    }

    /** Parses a module header's port list, carrying the ANSI direction and type across entries. */
    static List<VerilogModule.Port> parsePorts(String portList) {
        List<VerilogModule.Port> ports = new ArrayList<>();
        if (portList == null || portList.isBlank()) {
            return ports;
        }
        String direction = null;
        String netType = null;
        String range = null;
        boolean signed = false;
        boolean carry = false;
        for (String entry : splitTopLevel(portList, ',')) {
            String text = entry.strip();
            if (text.isEmpty()) {
                continue;
            }
            int equals = indexOfTopLevel(text, '=');
            if (equals >= 0) {
                text = text.substring(0, equals).strip();
            }
            Matcher directionMatcher = DIRECTION.matcher(text);
            if (directionMatcher.find()) {
                direction = directionMatcher.group(1);
                text = text.substring(directionMatcher.end()).strip();
                netType = null;
                range = null;
                signed = false;
                carry = true;
            } else if (!carry) {
                // A non-ANSI header entry names a port and nothing else.
                Matcher identifier = IDENTIFIER.matcher(text);
                if (identifier.find()) {
                    ports.add(new VerilogModule.Port(identifier.group(), "unknown", null, null, false));
                }
                continue;
            }
            Matcher net = NET_TYPE.matcher(text);
            if (net.find()) {
                netType = net.group(1);
                text = text.substring(net.end()).strip();
            }
            if (text.startsWith("signed")) {
                signed = true;
                text = text.substring("signed".length()).strip();
            } else if (text.startsWith("unsigned")) {
                signed = false;
                text = text.substring("unsigned".length()).strip();
            }
            if (text.startsWith("[")) {
                int close = matching(text, 0, '[', ']');
                if (close > 0) {
                    // matching() returns the offset just past the closing bracket.
                    range = text.substring(0, close);
                    text = text.substring(close).strip();
                }
            }
            Matcher identifier = IDENTIFIER.matcher(text);
            if (!identifier.find()) {
                continue;
            }
            ports.add(new VerilogModule.Port(
                    identifier.group(), direction, netType, range, signed));
        }
        return ports;
    }

    /** @return the top-level comma-separated pieces of {@code text}, ignoring nested brackets. */
    static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int indexOfTopLevel(String text, char target) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == target && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** @return the offset just past the balanced pair, or {@code -1} when it is unterminated. */
    public static int matching(String text, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** @return the file name without its extension. */
    static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }

    /** A parsed module header: its name, its raw port list and the offset just past its body opener. */
    private record Declaration(String name, String portList, int end) {
    }
}

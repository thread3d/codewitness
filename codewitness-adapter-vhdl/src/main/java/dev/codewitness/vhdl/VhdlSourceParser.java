package dev.codewitness.vhdl;

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
 * Reads entity declarations out of VHDL source text.
 *
 * <p>The adapter needs three facts from a tree: which entities exist, which of them are testbenches,
 * and what ports the design under test declares. Reading them is a lexical job rather than a full
 * parse: an entity declaration is a small, regular shape - the name, an optional generic clause, and an
 * optional port clause - and the body only has to be scanned for that port clause. Nothing here
 * analyses or elaborates a design, which is the same choice every other adapter makes: detection never
 * runs project code.
 *
 * <p>VHDL keywords are case insensitive, so every search here is made against a lower-cased copy of the
 * masked source while offsets are taken from the original. A port clause may declare several names
 * sharing one mode and subtype ({@code a, b : in std_logic}), and a subtype may carry a default value
 * ({@code := '0'}) that is not part of the type; both are read. An entity with no port clause is a
 * testbench, because a synthesizable design entity has ports and a testbench is the top level that
 * instantiates one.
 */
public final class VhdlSourceParser {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** VHDL port modes, in the order they are recognised. */
    private static final List<String> MODES = List.of("inout", "in", "out", "buffer", "linkage");

    private VhdlSourceParser() {
    }

    /** @return every entity declared in a file, in declaration order. */
    static List<VhdlDesignUnit> parse(Path file) {
        String raw = read(file);
        if (raw.isBlank()) {
            return List.of();
        }
        String source = maskCommentsAndStrings(raw);
        String lower = source.toLowerCase(Locale.ROOT);
        boolean fileTestbench = looksLikeTestbench(file, source);
        List<VhdlDesignUnit> units = new ArrayList<>();
        int index = 0;
        while (true) {
            int at = findKeyword(lower, "entity", index);
            if (at < 0) {
                break;
            }
            int afterKeyword = at + "entity".length();
            int nameStart = skipWhitespace(lower, afterKeyword);
            Matcher name = IDENTIFIER.matcher(source);
            name.region(nameStart, source.length());
            if (!name.lookingAt()) {
                index = afterKeyword;
                continue;
            }
            String entityName = name.group();
            int afterName = skipWhitespace(lower, name.end());
            if (!lower.startsWith("is", afterName) || !isWordBoundary(lower, afterName + 2)) {
                // `end entity;` and `end entity foo;` both mention the keyword without declaring one.
                index = afterKeyword;
                continue;
            }
            int bodyStart = afterName + 2;
            int bodyEnd = findKeyword(lower, "end", bodyStart);
            if (bodyEnd < 0) {
                bodyEnd = source.length();
            }
            String body = source.substring(bodyStart, bodyEnd);
            String portClause = portClause(body);
            List<VhdlDesignUnit.Port> ports = portClause == null ? List.of() : parsePorts(portClause);
            boolean testbench = fileTestbench || ports.isEmpty();
            units.add(new VhdlDesignUnit(entityName, file, ports, testbench));
            int statementEnd = lower.indexOf(';', bodyEnd);
            index = statementEnd < 0 ? bodyEnd + 3 : statementEnd + 1;
        }
        return units;
    }

    /** @return the raw text between the parentheses of an entity's port clause, or {@code null}. */
    private static String portClause(String entityBody) {
        String lower = entityBody.toLowerCase(Locale.ROOT);
        int index = 0;
        while (true) {
            int at = findKeyword(lower, "port", index);
            if (at < 0) {
                return null;
            }
            int open = skipWhitespace(lower, at + "port".length());
            if (open < lower.length() && lower.charAt(open) == '(') {
                int close = matching(entityBody, open, '(', ')');
                if (close > 0) {
                    return entityBody.substring(open + 1, close - 1);
                }
                return null;
            }
            index = at + "port".length();
        }
    }

    /**
     * Parses an entity's port clause into ports.
     *
     * <p>Each entry is {@code names : mode subtype [:= default]}; a name may be shared by several ports,
     * and the default value is dropped because it is not part of the type.
     */
    static List<VhdlDesignUnit.Port> parsePorts(String portClause) {
        List<VhdlDesignUnit.Port> ports = new ArrayList<>();
        if (portClause == null || portClause.isBlank()) {
            return ports;
        }
        for (String entry : splitTopLevel(portClause, ';')) {
            String text = stripDefault(entry).strip();
            if (text.isEmpty()) {
                continue;
            }
            int colon = indexOfTopLevel(text, ':');
            if (colon < 0) {
                continue;
            }
            String names = text.substring(0, colon);
            String declaration = text.substring(colon + 1).strip();
            String mode = "in";
            String subtype = declaration;
            for (String candidate : MODES) {
                if (declaration.equalsIgnoreCase(candidate)) {
                    mode = candidate;
                    subtype = "";
                    break;
                }
                if (declaration.length() > candidate.length()
                        && declaration.regionMatches(true, 0, candidate, 0, candidate.length())
                        && Character.isWhitespace(declaration.charAt(candidate.length()))) {
                    mode = candidate;
                    subtype = declaration.substring(candidate.length()).strip();
                    break;
                }
            }
            if (subtype.isEmpty()) {
                subtype = "std_logic";
            }
            for (String name : names.split(",")) {
                String identifier = name.strip();
                if (!identifier.isEmpty()) {
                    ports.add(new VhdlDesignUnit.Port(identifier, mode, subtype));
                }
            }
        }
        return ports;
    }

    /** @return the entry with a top-level {@code := default} removed. */
    private static String stripDefault(String entry) {
        int depth = 0;
        for (int i = 0; i + 1 < entry.length(); i++) {
            char c = entry.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == ':' && depth == 0 && entry.charAt(i + 1) == '=') {
                // `:=` is the default-value operator, which is not part of the subtype.
                return entry.substring(0, i);
            }
        }
        return entry;
    }

    /** @return {@code true} when a file's name or location says it holds a testbench. */
    static boolean looksLikeTestbench(Path file, String maskedSource) {
        if (file != null) {
            Path parent = file.getParent();
            if (parent != null && isTestbenchDirectoryName(parent.getFileName() == null
                    ? "" : parent.getFileName().toString())) {
                return true;
            }
            return isTestbenchName(stem(file.getFileName() == null ? "" : file.getFileName().toString()));
        }
        return false;
    }

    /**
     * @return {@code true} when an entity or file name follows a testbench convention:
     *     {@code ledger_tb}, {@code tb_ledger}, {@code ledger_test}, {@code ledger_testbench}.
     */
    public static boolean isTestbenchName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("_tb") || lower.startsWith("tb_") || lower.equals("tb")
                || lower.endsWith("_test") || lower.endsWith("_testbench") || lower.startsWith("testbench")
                || lower.contains("_testbench");
    }

    private static boolean isTestbenchDirectoryName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.equals("tb") || lower.equals("testbench") || lower.equals("testbenches")
                || lower.equals("test") || lower.equals("tests") || lower.equals("sim")
                || lower.equals("simulation");
    }

    /** @return the source with every comment and string literal replaced by spaces, offsets preserved. */
    public static String maskCommentsAndStrings(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        char[] out = source.toCharArray();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '-' && i + 1 < n && source.charAt(i + 1) == '-') {
                while (i < n && source.charAt(i) != '\n') {
                    out[i] = ' ';
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i] = ' ';
                    if (i + 1 < n) {
                        out[i + 1] = ' ';
                    }
                    i += 2;
                }
                continue;
            }
            if (c == '"') {
                out[i] = ' ';
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    if (d == '"' && i + 1 < n && source.charAt(i + 1) == '"') {
                        // A doubled quote is an escaped quote inside a VHDL string literal.
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i += 2;
                        continue;
                    }
                    if (d == '"') {
                        out[i] = ' ';
                        i++;
                        break;
                    }
                    if (d != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                continue;
            }
            if (c == '\'' && i + 2 < n && source.charAt(i + 2) == '\'') {
                // A character literal such as '-', which must not be read as the start of a comment.
                out[i] = ' ';
                out[i + 1] = ' ';
                out[i + 2] = ' ';
                i += 3;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    /** @return the offset of {@code keyword} as a whole word at or after {@code from}, or {@code -1}. */
    public static int findKeyword(String lowerSource, String keyword, int from) {
        int index = Math.max(0, from);
        while (index >= 0) {
            index = lowerSource.indexOf(keyword, index);
            if (index < 0) {
                return -1;
            }
            boolean startsWord = index == 0 || !isIdentifierChar(lowerSource.charAt(index - 1));
            int after = index + keyword.length();
            boolean endsWord = after >= lowerSource.length() || !isIdentifierChar(lowerSource.charAt(after));
            if (startsWord && endsWord) {
                return index;
            }
            index = after;
        }
        return -1;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isWordBoundary(String text, int index) {
        return index >= text.length() || !isIdentifierChar(text.charAt(index));
    }

    /** @return the top-level pieces of {@code text}, ignoring nested brackets. */
    static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** @return the offset of the first {@code target} at the top level, or {@code -1}. */
    private static int indexOfTopLevel(String text, char target) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
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

    /** @return the file name without its extension. */
    public static String stem(String fileName) {
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
}

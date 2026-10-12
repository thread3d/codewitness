package dev.codewitness.pascal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lexical reader for Pascal source: it names a compilation unit and lists its dependencies without
 * compiling or elaborating anything.
 *
 * <p>This is deliberately a scanner and not a parser. Detection has to work on a tree whose compiler is
 * not installed and whose source may not even compile, so it reads the two things Pascal states
 * unambiguously at the top of a file - the kind and name of the unit, and its {@code uses} clauses - and
 * ignores everything else. Comments, compiler directives and string literals are masked first, so a
 * {@code uses} inside a comment or a quote is never mistaken for a dependency.
 */
public final class PascalSourceParser {

    /** The keyword that names a program or a library. */
    private static final Pattern PROGRAM_KEYWORD =
            Pattern.compile("(?i)\\b(program|library)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern UNIT_KEYWORD =
            Pattern.compile("(?i)\\bunit\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern USES_CLAUSE = Pattern.compile("(?i)\\buses\\b(.*?);", Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Markers that say a source is a test. They are the declarations the fpcunit framework makes every
     * test use, plus the name of its console runner; a test that uses none of them is still recognised
     * by its file name.
     */
    private static final List<String> TEST_MARKERS =
            List.of("fpcunit", "tregistry", "testregistry", "tcase", "testcase", "consoletestrunner",
                    "registertest");

    private PascalSourceParser() {
    }

    /**
     * Reads one source file into a module description.
     *
     * @param file the source file
     * @return the module, with the file's stem as its name when the source declares none
     */
    public static PascalModule parse(Path file) {
        String source = read(file);
        String masked = stripCommentsAndStrings(source);
        String lower = masked.toLowerCase(Locale.ROOT);

        String name = stem(file);
        boolean program = isProgramSuffix(file);
        Matcher programMatcher = PROGRAM_KEYWORD.matcher(masked);
        if (programMatcher.find()) {
            name = programMatcher.group(2);
            program = true;
        } else {
            Matcher unitMatcher = UNIT_KEYWORD.matcher(masked);
            if (unitMatcher.find()) {
                name = unitMatcher.group(1);
            }
        }

        boolean test = isTestSource(file, masked, lower);
        return new PascalModule(name, file, dependencies(masked), program, test);
    }

    /** @return the {@code uses} identifiers of an already masked source, in order and without duplicates. */
    static List<String> dependencies(String maskedSource) {
        Set<String> dependencies = new LinkedHashSet<>();
        Matcher uses = USES_CLAUSE.matcher(maskedSource);
        while (uses.find()) {
            for (String entry : uses.group(1).split(",")) {
                // An entry may carry a file name: `ledger in 'ledger.pas'`. Only the identifier matters.
                Matcher identifier = IDENTIFIER.matcher(entry);
                if (identifier.find()) {
                    dependencies.add(identifier.group());
                }
            }
        }
        return List.copyOf(dependencies);
    }

    /**
     * @return {@code true} when the source belongs to the test suite: it uses the fpcunit framework, its
     *     file name reads as a test, or it sits in a conventional test directory.
     */
    static boolean isTestSource(Path file, String maskedSource, String lowerMaskedSource) {
        for (String marker : TEST_MARKERS) {
            if (lowerMaskedSource.contains(marker)) {
                return true;
            }
        }
        if (isTestName(stem(file))) {
            return true;
        }
        Path parent = file.getParent();
        return parent != null && isTestDirectory(parent.getFileName() == null ? "" : parent.getFileName().toString());
    }

    /** @return {@code true} when the file suffix is one the language reserves for an executable. */
    public static boolean isProgramSuffix(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".lpr") || name.endsWith(".dpr");
    }

    /** @return {@code true} when a file stem reads as a test, for example {@code ledger_test}. */
    public static boolean isTestName(String stem) {
        String lower = stem.toLowerCase(Locale.ROOT);
        return lower.equals("test") || lower.equals("tests") || lower.endsWith("_test") || lower.endsWith("_tests")
                || lower.startsWith("test_") || lower.startsWith("tests_") || lower.endsWith("test")
                || lower.endsWith("spec") || lower.endsWith("_spec");
    }

    /** @return {@code true} when a directory name is a conventional home for tests. */
    public static boolean isTestDirectory(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("test") || lower.equals("tests") || lower.equals("t")
                || lower.equals("spec") || lower.equals("specs");
    }

    /**
     * Blanks comments, compiler directives and string literals, preserving every newline so a line
     * number computed from the masked text still matches the raw text.
     *
     * <p>It handles all three Pascal comment forms: {@code (* ... *)}, brace {@code { ... }} (which is
     * also how a compiler directive such as {@code {$mode objfpc}} is written) and {@code // ...}. A
     * string literal is written with apostrophes and escapes one with a doubled apostrophe.
     *
     * @param source the raw source
     * @return the source with the hidden spans replaced by spaces
     */
    public static String stripCommentsAndStrings(String source) {
        StringBuilder masked = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '{') {
                i = blankUntil(masked, source, i, '}');
                continue;
            }
            if (c == '(' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = blankUntil(masked, source, i, ')');
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int start = i;
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                blank(masked, source, start, i);
                continue;
            }
            if (c == '\'') {
                int start = i;
                i++;
                while (i < source.length()) {
                    if (source.charAt(i) == '\'') {
                        if (i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                blank(masked, source, start, i);
                continue;
            }
            masked.append(c);
            i++;
        }
        return masked.toString();
    }

    private static int blankUntil(StringBuilder masked, String source, int start, char closing) {
        int i = start;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '}' && closing == '}') {
                i++;
                break;
            }
            if (c == '*' && closing == ')' && i + 1 < source.length() && source.charAt(i + 1) == ')') {
                i += 2;
                break;
            }
            i++;
        }
        blank(masked, source, start, i);
        return i;
    }

    private static void blank(StringBuilder masked, String source, int start, int end) {
        for (int i = start; i < end; i++) {
            masked.append(source.charAt(i) == '\n' ? '\n' : ' ');
        }
    }

    /** @return the file's stem, without its extension. */
    public static String stem(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /** @return the file's text, or an empty string when it cannot be read. */
    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** @return every identifier in a source, used by the mutation and analysis passes. */
    static List<String> identifiers(String maskedSource) {
        List<String> identifiers = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(maskedSource);
        while (matcher.find()) {
            identifiers.add(matcher.group());
        }
        return identifiers;
    }
}

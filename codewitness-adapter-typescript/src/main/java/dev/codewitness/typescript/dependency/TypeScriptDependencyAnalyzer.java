package dev.codewitness.typescript.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventories a TypeScript project's declared dependencies from {@code package.json}.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads the dependency tables the manifest
 * declares and reports the package names; it never claims a project is free of vulnerable
 * dependencies, because it does not consult a vulnerability database, and the report says exactly that.
 *
 * <p>The manifest is not parsed with a general JSON parser because the adapter depends on the core
 * only. The extractor is deliberately narrow: it reads each dependency table's own brace-balanced body,
 * so a package named in a script, an {@code engines} constraint or another nested object cannot be
 * mistaken for a dependency.
 */
public final class TypeScriptDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    /** The dependency tables npm understands, from strongest to weakest intent. */
    private static final List<String> SECTIONS = List.of(
            "dependencies", "devDependencies", "peerDependencies", "optionalDependencies");

    private static final Pattern KEY = Pattern.compile("\"([^\"\\\\]+)\"\\s*:");

    @Override
    public String id() {
        return "package-json-dependencies";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.TYPESCRIPT);
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path manifest = context.projectRoot().resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return DependencyReport.notAnalysed("package-json",
                    "no package.json was found, so no dependency manifest could be read");
        }
        List<String> direct = List.copyOf(new LinkedHashSet<>(parse(read(manifest))));
        return new DependencyReport(true, "package-json",
                "inventory only: CodeWitness does not consult a vulnerability database, so it cannot claim "
                        + "these dependencies are free of known vulnerabilities",
                direct.size(), direct, List.of());
    }

    /** @return the package names declared in the manifest's dependency tables. */
    public static List<String> parse(String manifest) {
        List<String> names = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return names;
        }
        for (String section : SECTIONS) {
            String body = objectAt(manifest, section);
            if (body == null) {
                continue;
            }
            Matcher matcher = KEY.matcher(body);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** @return the body of the object stored under {@code "key"}, without its braces, or {@code null}. */
    static String objectAt(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{").matcher(json);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end() - 1;
        int end = balancedEnd(json, start);
        return end < 0 ? null : json.substring(start + 1, end);
    }

    /** @return the index of the brace that closes the object starting at {@code start}, or {@code -1}. */
    static int balancedEnd(String text, int start) {
        if (text == null || start < 0 || start >= text.length() || text.charAt(start) != '{') {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }
}

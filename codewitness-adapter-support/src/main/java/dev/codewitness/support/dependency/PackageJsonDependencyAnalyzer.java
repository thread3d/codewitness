package dev.codewitness.support.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventories a Node project's dependencies, resolved from {@code package-lock.json} when it exists and
 * declared from {@code package.json} otherwise.
 *
 * <p>This is an inventory, not a vulnerability scan. It never claims a project is free of vulnerable
 * dependencies; whether any of them has a known advisory is a separate question, recorded by the
 * advisory scan, and the report says exactly that.
 *
 * <p>The distinction between the two sources is not cosmetic. A manifest declares version
 * <em>ranges</em>, so an inventory built from it can name a dependency but cannot be asked whether a
 * named version is affected by anything - and an unaskable inventory must not be presented as a clean
 * one. A lock file resolves the graph, so when one is present the inventory carries versions and no
 * longer covers only the packages someone wrote down: it covers everything the install would ship.
 * {@code totalDependencies} therefore counts resolved packages, and the report's note says which source
 * was read.
 *
 * <p>{@code package.json} is read with a narrow brace-balanced extractor rather than a JSON parser: it
 * reads each dependency table's own body, so a package named in a script, an {@code engines} constraint
 * or another nested object cannot be mistaken for a dependency. {@code package-lock.json} is read with a
 * real parser, because its objects nest and each nested object has its own {@code version} field. A
 * manifest that exists but cannot be read is reported as not analysed rather than as an empty inventory.
 */
public final class PackageJsonDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    /** The language whose projects this analyzer inventories. */
    private final LanguageId language;

    /**
     * @param language the language whose projects this analyzer claims. A {@code package.json} is read
     *     identically for JavaScript and TypeScript, so the language is the only thing that varies
     *     between the two adapters that used to each carry a copy of this class.
     */
    public PackageJsonDependencyAnalyzer(LanguageId language) {
        this.language = language;
    }


    /** The dependency tables npm understands, from strongest to weakest intent. */
    private static final List<String> SECTIONS = List.of(
            "dependencies", "devDependencies", "peerDependencies", "optionalDependencies");

    private static final Pattern KEY = Pattern.compile("\"([^\"\\\\]+)\"\\s*:");

    /** What the inventory covers when a lock file was read. */
    static final String LOCK_NOTE = "inventory only: every package the project's package-lock.json "
            + "resolves, including transitive ones; whether any of them has a known advisory is recorded "
            + "by the advisory scan, which is a separate question from which dependencies exist";

    /** What the inventory covers when only the manifest could be read. */
    static final String MANIFEST_NOTE = "inventory only: the package names package.json declares, with the "
            + "version ranges it declares them at; no lock file was found, so no resolved version is known "
            + "and the advisory scan cannot query these dependencies";

    @Override
    public String id() {
        return "package-json-dependencies";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(language);
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path manifest = context.projectRoot().resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return DependencyReport.notAnalysed("package-json",
                    "no package.json was found, so no dependency manifest could be read");
        }
        return resolved(context);
    }

    /** {@inheritDoc} A package-lock.json resolves npm packages and nothing else. */
    @Override
    public Set<String> ecosystems() {
        return Set.of(PackageCoordinate.NPM);
    }

    /** {@inheritDoc} */
    @Override
    public String dependencyPathHint(PackageCoordinate coordinate) {
        return DependencyPathHints.npm(coordinate);
    }

    /**
     * Reads the lock file when there is one, and falls back to the manifest's declared names when there
     * is not.
     *
     * <p>The two are not merged. A lock file already contains everything the manifest declares, and
     * adding the manifest's names on top would report a dependency twice - once resolved, once not -
     * which would then be counted twice in the coverage the scan claims.
     */
    private DependencyReport resolved(VerificationContext context) {
        Path lock = context.projectRoot().resolve("package-lock.json");
        if (Files.isRegularFile(lock)) {
            try {
                List<PackageCoordinate> coordinates = NodeLockFile.parse(Files.readString(lock, StandardCharsets.UTF_8));
                if (!coordinates.isEmpty()) {
                    String module = moduleName(context);
                    Map<String, List<String>> byModule =
                            Map.of(module, coordinates.stream().map(PackageCoordinate::display).toList());
                    return DependencyReport.inventory("package-lock", LOCK_NOTE, coordinates, byModule);
                }
                // A lock file that parsed but recorded no package is indistinguishable from an empty
                // install; saying so is better than silently reporting the manifest's ranges as if they
                // had been resolved.
                return DependencyReport.notAnalysed("package-lock",
                        "package-lock.json exists but records no resolved package, so the dependencies "
                                + "could not be inventoried");
            } catch (IOException e) {
                return DependencyReport.notAnalysed("package-lock",
                        "package-lock.json exists but could not be read: " + e);
            }
        }
        Path manifest = context.projectRoot().resolve("package.json");
        String content;
        try {
            content = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return DependencyReport.notAnalysed("package-json",
                    "package.json exists but could not be read: " + e);
        }
        return DependencyReport.inventory("package-json", MANIFEST_NOTE,
                parse(content).stream().map(name -> PackageCoordinate.npm(name, "")).toList(),
                Map.of(moduleName(context), parse(content)));
    }

    /** @return the name the report should attribute this project's dependencies to. */
    private static String moduleName(VerificationContext context) {
        ProjectInfo info = context.projectInfo();
        if (!info.modules().isEmpty()) {
            return info.modules().getFirst().name();
        }
        return context.projectRoot().getFileName() == null
                ? "package" : context.projectRoot().getFileName().toString();
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
}

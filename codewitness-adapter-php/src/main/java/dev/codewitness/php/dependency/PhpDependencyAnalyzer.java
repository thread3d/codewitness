package dev.codewitness.php.dependency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventories a Composer project's dependencies, resolved from {@code composer.lock} or declared from
 * {@code composer.json}.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads what the project declares, and never claims
 * that a project is free of vulnerable dependencies; the report says exactly that. A manifest that exists
 * but cannot be read is reported as not analysed rather than as an empty inventory, because "nothing was
 * found" and "nothing could be read" must not look the same in a report.
 *
 * <p>A declared requirement is usually a version <em>range</em>, and an advisory is a statement about a
 * version, so a range cannot be queried. A constraint naming exactly one version ({@code 1.2.3},
 * {@code v1.2.3}, {@code =1.2.3}) is resolved, and everything else - {@code ^1.2}, {@code ~1.2},
 * {@code *}, {@code 1.*}, {@code dev-main}, a {@code -dev} branch alias, a compound range - is reported
 * with an empty version instead of a guessed one. A lock file is preferred when it is present, because it
 * records the version each package was actually resolved to; even there a development branch is recorded
 * as {@code dev-main} rather than as a version, and is reported unresolved for the same reason.
 */
public final class PhpDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A Composer constraint that names exactly one version.
     *
     * <p>An optional {@code v} prefix and an optional {@code =} are how the same version may be written,
     * and Composer reads {@code 1.2} as {@code 1.2.0}, so both are accepted. Everything a Composer range
     * can be - {@code ^}, {@code ~}, {@code *}, {@code -dev}, {@code ||}, {@code >=} - fails this match,
     * which is the point: a version that an advisory service is asked about has to be the one that would
     * actually be installed.
     */
    private static final Pattern EXACT_VERSION = Pattern.compile("^v?=?(\\d+(?:\\.\\d+)*)$");

    /** What a lock-file inventory covers, stated in the report's own vocabulary. */
    private static final String LOCK_NOTE = "inventory only: every package composer.lock resolves, runtime "
            + "and development, with the version the lock resolved; whether any of them has a known advisory "
            + "is recorded by the advisory scan, a separate question from which packages the code uses";

    @Override
    public String id() {
        return "php-dependencies";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The manifests are read directly, so no command is run: {@code composer show} would need a built
     * project and, for a project whose {@code vendor/} is absent, a network round trip that a verifier must
     * not make just to list what a file already says.
     */
    @Override
    public List<String> dependencyCommand(VerificationContext context) {
        return List.of();
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path root = context.projectRoot();
        Path manifest = root.resolve("composer.json");
        if (!Files.isRegularFile(manifest)) {
            return DependencyReport.notAnalysed("composer-manifest",
                    "no composer.json was found, so this project declares no Composer package; PHP has no "
                            + "other dependency manifest, and nothing was invented in its place");
        }
        JsonNode declared;
        try {
            declared = MAPPER.readTree(readText(manifest));
        } catch (Exception e) {
            return DependencyReport.notAnalysed("composer-manifest", "composer.json exists but could not be "
                    + "parsed (" + e.getClass().getSimpleName() + "); the declared dependencies are unknown "
                    + "rather than absent");
        }
        if (declared == null || !declared.isObject()) {
            return DependencyReport.notAnalysed("composer-manifest",
                    "composer.json exists but is not a JSON object, so it declares no dependency this "
                            + "inventory can name");
        }

        String lockNote = null;
        Path lock = root.resolve("composer.lock");
        if (Files.isRegularFile(lock)) {
            JsonNode resolved;
            try {
                resolved = MAPPER.readTree(readText(lock));
            } catch (Exception e) {
                // The lock file is the only source of resolved versions, so a lock that exists but cannot be
                // read is reported as an unknown inventory: falling back to the manifest would silently
                // present every dependency as unqueryable and call that a complete answer.
                return DependencyReport.notAnalysed("composer-lock", "composer.lock exists but could not be "
                        + "read (" + e.getClass().getSimpleName() + "); the resolved dependency graph is what "
                        + "an advisory scan is asked about, so the inventory was not produced");
            }
            List<PackageCoordinate> locked = fromLock(resolved);
            if (!locked.isEmpty()) {
                return DependencyReport.inventory("composer-lock", LOCK_NOTE, locked, byModule(context, locked));
            }
            lockNote = "composer.lock resolved no package, so the inventory is what composer.json declares";
        }

        List<PackageCoordinate> packages = fromManifest(declared);
        if (packages.isEmpty()) {
            return DependencyReport.notAnalysed("composer-manifest",
                    "composer.json declares no Composer package in require or require-dev"
                            + (lockNote == null ? "" : ", and composer.lock resolved none"));
        }
        long exact = packages.stream().filter(PackageCoordinate::resolved).count();
        StringBuilder note = new StringBuilder("inventory only: the ").append(packages.size())
                .append(" package(s) composer.json declares in require and require-dev, of which ")
                .append(exact).append(" name exactly one version and the rest are ranges, wildcards or "
                        + "branch aliases no advisory service can be asked about; whether any of them has a "
                        + "known advisory is recorded by the advisory scan, a separate question from which "
                        + "packages the code uses");
        if (lockNote != null) {
            note.append("; ").append(lockNote);
        }
        return DependencyReport.inventory("composer-manifest", note.toString(), packages,
                byModule(context, packages));
    }

    /**
     * @param manifest a parsed {@code composer.json}
     * @return one coordinate per package declared in {@code require} and {@code require-dev}, in
     *     declaration order, deduplicated by name
     */
    static List<PackageCoordinate> fromManifest(JsonNode manifest) {
        Map<String, PackageCoordinate> packages = new LinkedHashMap<>();
        for (String section : List.of("require", "require-dev")) {
            JsonNode entries = manifest.path(section);
            if (!entries.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = entries.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                register(packages, entry.getKey(), text(entry.getValue()));
            }
        }
        return List.copyOf(packages.values());
    }

    /**
     * @param lock a parsed {@code composer.lock}
     * @return one coordinate per package in {@code packages} and {@code packages-dev}
     */
    static List<PackageCoordinate> fromLock(JsonNode lock) {
        Map<String, PackageCoordinate> packages = new LinkedHashMap<>();
        for (String section : List.of("packages", "packages-dev")) {
            JsonNode entries = lock.path(section);
            if (!entries.isArray()) {
                continue;
            }
            for (JsonNode entry : entries) {
                register(packages, text(entry.path("name")), text(entry.path("version")));
            }
        }
        return List.copyOf(packages.values());
    }

    private static void register(Map<String, PackageCoordinate> packages, String name, String version) {
        if (name == null || isPlatformPackage(name) || packages.containsKey(name.strip())) {
            return;
        }
        packages.put(name.strip(), PackageCoordinate.packagist(name.strip(), exactVersion(version)));
    }

    /**
     * @return the version when the constraint names exactly one, otherwise the empty string
     *
     * <p>The empty version is what makes this honest rather than convenient: it says the inventory knows
     * the package exists but not which version is installed, which is what stops the advisory scan from
     * asking about a version that may never have been in the build.
     */
    static String exactVersion(String constraint) {
        if (constraint == null) {
            return "";
        }
        Matcher matcher = EXACT_VERSION.matcher(constraint.strip());
        return matcher.matches() ? matcher.group(1) : "";
    }

    /**
     * @return {@code true} when the entry is a platform requirement rather than a package
     *
     * <p>{@code php}, {@code php-64bit}, {@code hhvm}, {@code composer} and everything prefixed
     * {@code ext-}, {@code lib-} or {@code composer-} name a capability of the interpreter or of Composer
     * itself, not something installed from Packagist. An entry without a {@code vendor/package} shape is
     * not a package either, and inventing a vendor for it would query a package nobody depends on.
     */
    static boolean isPlatformPackage(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.strip().toLowerCase(Locale.ROOT);
        if (lower.equals("php") || lower.equals("php-64bit") || lower.equals("php-ipv6")
                || lower.equals("hhvm") || lower.equals("composer")) {
            return true;
        }
        if (lower.startsWith("ext-") || lower.startsWith("lib-") || lower.startsWith("composer-")) {
            return true;
        }
        return !name.contains("/");
    }

    /** @return the dependencies attributed to the project's own module, keyed by that module's name. */
    private static Map<String, List<String>> byModule(
            VerificationContext context, List<PackageCoordinate> packages) {
        return Map.of(moduleName(context), packages.stream().map(PackageCoordinate::display).toList());
    }

    /** @return the name the report should attribute this project's dependencies to. */
    private static String moduleName(VerificationContext context) {
        ProjectInfo info = context.projectInfo();
        if (!info.modules().isEmpty()) {
            return info.modules().getFirst().name();
        }
        Path fileName = context.projectRoot().getFileName();
        return fileName == null ? "php" : fileName.toString();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static String readText(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}

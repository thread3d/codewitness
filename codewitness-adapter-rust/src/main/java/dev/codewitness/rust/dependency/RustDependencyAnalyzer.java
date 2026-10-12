package dev.codewitness.rust.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.dependency.DependencyPathHints;
import dev.codewitness.support.dependency.TomlPackageTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventories a Rust project's declared dependencies from its Cargo manifests.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads what {@code Cargo.toml} declares —
 * including the {@code [workspace.dependencies]} table and the {@code [target.'cfg(...)'.dependencies]}
 * tables — and reports the crate names. It never claims a project is free of vulnerable
 * dependencies, because it does not consult a vulnerability database; the report says exactly that.
 *
 * <p>A manifest that exists but declares nothing is still an analysed manifest, so an empty project
 * reports "0 dependencies" rather than pretending the analysis could not run. A manifest that exists
 * but cannot be read is reported as not analysed, because an empty inventory would be
 * indistinguishable from a project with no dependencies.
 */
public final class RustDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    private static final Pattern SECTION = Pattern.compile("(?m)^\\s*\\[([^\\]]+)\\]\\s*$");
    private static final Pattern KEY = Pattern.compile("^\\s*([A-Za-z0-9_][A-Za-z0-9_-]*)\\s*=");
    private static final Pattern DEPENDENCY_TABLE = Pattern.compile(
            "^(?:dependencies|dev-dependencies|build-dependencies)\\.(.+)$");

    @Override
    public String id() {
        return "cargo-dependencies";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUST);
    }

    /** {@inheritDoc} A Cargo crate is an advisory's crates.io package. */
    @Override
    public Set<String> ecosystems() {
        return Set.of(PackageCoordinate.CRATES_IO);
    }

    /** {@inheritDoc} */
    @Override
    public String dependencyPathHint(PackageCoordinate coordinate) {
        return DependencyPathHints.crate(coordinate);
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path lock = context.projectRoot().resolve("Cargo.lock");
        if (Files.isRegularFile(lock)) {
            String locked;
            try {
                locked = Files.readString(lock, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return DependencyReport.notAnalysed("cargo-lock",
                        "Cargo.lock exists but could not be read: " + e);
            }
            List<PackageCoordinate> coordinates =
                    TomlPackageTable.parse(locked, PackageCoordinate.CRATES_IO, true);
            if (!coordinates.isEmpty()) {
                return DependencyReport.inventory("cargo-lock",
                        "inventory only: every crate Cargo.lock resolves, including transitive ones; "
                                + "whether any of them has a known advisory is recorded by the advisory "
                                + "scan, a separate question from which crates the program needs",
                        coordinates,
                        Map.of(moduleName(context), coordinates.stream().map(PackageCoordinate::display).toList()));
            }
            return DependencyReport.notAnalysed("cargo-lock",
                    "Cargo.lock exists but records no resolved package, so the crates could not be "
                            + "inventoried");
        }
        Path manifest = context.projectRoot().resolve("Cargo.toml");
        if (!Files.isRegularFile(manifest)) {
            return DependencyReport.notAnalysed("cargo-manifest",
                    "no Cargo.toml was found, so no dependency manifest could be read");
        }
        String content;
        try {
            content = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A manifest that exists but cannot be read is not an empty dependency set: reporting an
            // empty inventory here would be indistinguishable from "this project has no dependencies".
            return DependencyReport.notAnalysed("cargo-manifest",
                    "Cargo.toml exists but could not be read: " + e);
        }
        Set<String> dependencies = new LinkedHashSet<>(parse(content));
        List<PackageCoordinate> declared = dependencies.stream()
                .map(name -> PackageCoordinate.crate(name, ""))
                .toList();
        return DependencyReport.inventory("cargo-manifest",
                "inventory only: the crate names Cargo.toml declares, with the version ranges it declares "
                        + "them at; no Cargo.lock was found, so no resolved version is known and the "
                        + "advisory scan cannot query these crates",
                declared,
                Map.of(moduleName(context), declared.stream().map(PackageCoordinate::display).toList()));
    }

    /** @return the name the report should attribute this crate's dependencies to. */
    private static String moduleName(VerificationContext context) {
        ProjectInfo info = context.projectInfo();
        if (!info.modules().isEmpty()) {
            return info.modules().getFirst().name();
        }
        Path fileName = context.projectRoot().getFileName();
        return fileName == null ? "crate" : fileName.toString();
    }

    /** @return the crate names declared in the manifest's dependency tables. */
    static List<String> parse(String manifest) {
        List<String> names = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return names;
        }
        String section = "";
        StringBuilder body = new StringBuilder();
        for (String line : manifest.split("\\R", -1)) {
            Matcher header = SECTION.matcher(line);
            if (header.matches()) {
                addFromSection(section, body.toString(), names);
                section = header.group(1).strip();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        addFromSection(section, body.toString(), names);
        return names;
    }

    private static void addFromSection(String section, String body, List<String> names) {
        Matcher table = DEPENDENCY_TABLE.matcher(section);
        if (table.matches()) {
            // [dependencies.serde] style: the section name is the dependency.
            names.add(table.group(1));
            return;
        }
        if (!section.equals("dependencies") && !section.equals("dev-dependencies")
                && !section.equals("build-dependencies") && !section.endsWith(".dependencies")
                && !section.endsWith(".dev-dependencies") && !section.endsWith(".build-dependencies")) {
            return;
        }
        for (String line : body.split("\\R")) {
            Matcher key = KEY.matcher(line);
            if (key.find()) {
                names.add(key.group(1));
            }
        }
    }
}

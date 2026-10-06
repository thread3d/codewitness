package dev.codewitness.rust.dependency;

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
 * Inventories a Rust project's declared dependencies from its Cargo manifests.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads what {@code Cargo.toml} declares —
 * including the {@code [workspace.dependencies]} table and the {@code [target.'cfg(...)'.dependencies]}
 * tables — and reports the crate names. It never claims a project is free of vulnerable
 * dependencies, because it does not consult a vulnerability database; the report says exactly that.
 *
 * <p>A manifest that exists but declares nothing is still an analysed manifest, so an empty project
 * reports "0 dependencies" rather than pretending the analysis could not run.
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

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path manifest = context.projectRoot().resolve("Cargo.toml");
        if (!Files.isRegularFile(manifest)) {
            return DependencyReport.notAnalysed("cargo-manifest",
                    "no Cargo.toml was found, so no dependency manifest could be read");
        }
        Set<String> dependencies = new LinkedHashSet<>(parse(read(manifest)));
        List<String> direct = List.copyOf(dependencies);
        return new DependencyReport(true, "cargo-manifest",
                "inventory only: CodeWitness does not consult a vulnerability database, so it cannot claim "
                        + "these dependencies are free of known vulnerabilities",
                direct.size(), direct, List.of());
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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }
}

package dev.codewitness.go.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.dependency.DependencyPathHints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventories a Go module's declared dependencies from its {@code go.mod}.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads the {@code require} directives - in the
 * multi-line {@code require ( ... )} block and in the single-line {@code require <module> <version>}
 * form - and reports the module paths, distinguishing the direct requirements from the indirect ones
 * that Go added to complete the build list. It never claims a project is free of vulnerable
 * dependencies, because it does not consult a vulnerability database; the report says exactly that.
 *
 * <p>Every other directive ({@code module}, {@code go}, {@code toolchain}, {@code replace},
 * {@code exclude}, {@code retract}) is an instruction about how the build is resolved, not a
 * dependency of the program, so it is deliberately absent from the inventory. A module that exists
 * but requires nothing is still an analysed module, so an empty project reports "0 dependencies"
 * rather than pretending the analysis could not run.
 *
 * <p>A {@code go.mod} that exists but cannot be read is the opposite case: it is reported as not
 * analysed, because an empty inventory would be indistinguishable from a module with no dependencies.
 */
public final class GoDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    @Override
    public String id() {
        return "go-module";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    /** {@inheritDoc} A Go module path is an advisory's Go package name. */
    @Override
    public Set<String> ecosystems() {
        return Set.of(PackageCoordinate.GO);
    }

    /** {@inheritDoc} */
    @Override
    public String dependencyPathHint(PackageCoordinate coordinate) {
        return DependencyPathHints.go(coordinate);
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path manifest = context.projectRoot().resolve("go.mod");
        if (!Files.isRegularFile(manifest)) {
            return DependencyReport.notAnalysed("go-module",
                    "no go.mod was found, so no module manifest could be read");
        }
        String content;
        try {
            content = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A manifest that exists but cannot be read is not an empty module graph: reporting an empty
            // inventory here would be indistinguishable from "this project has no dependencies".
            return DependencyReport.notAnalysed("go-module",
                    "go.mod exists but could not be read: " + e);
        }
        List<PackageCoordinate> coordinates = coordinates(content);
        return DependencyReport.inventory("go-module",
                "inventory only: the module requirements and the versions they select, read from go.mod. "
                        + "The version on a require directive is the one the main module's build list "
                        + "selects, which is what an advisory query needs; whether any of these modules "
                        + "has a known advisory is recorded by the advisory scan, a separate question from "
                        + "which modules the program needs",
                coordinates,
                Map.of(moduleName(context), coordinates.stream().map(PackageCoordinate::display).toList()));
    }

    /** @return the name the report should attribute this module's dependencies to. */
    private static String moduleName(VerificationContext context) {
        ProjectInfo info = context.projectInfo();
        if (!info.modules().isEmpty()) {
            return info.modules().getFirst().name();
        }
        Path fileName = context.projectRoot().getFileName();
        return fileName == null ? "module" : fileName.toString();
    }

    /**
     * @return one coordinate per {@code require} directive, direct and indirect, carrying the version
     *     the directive declares.
     *
     * <p>The version comes from {@code go.mod} rather than {@code go.sum}. {@code go.sum} is a
     * download-integrity record: it keeps every module version that has ever been fetched for this
     * repository, including versions that a later {@code go get} replaced. Asking an advisory service
     * about those would report advisories against versions the build no longer uses, and a false
     * vulnerability finding is exactly the kind of wrong answer this tool exists to avoid. The
     * {@code require} version is the one the main module's build list selects, and a module not
     * required there is not in the build at all.
     */
    static List<PackageCoordinate> coordinates(String manifest) {
        List<PackageCoordinate> coordinates = new ArrayList<>();
        for (Requirement requirement : requirements(manifest)) {
            coordinates.add(PackageCoordinate.go(requirement.module(), requirement.version()));
        }
        return List.copyOf(coordinates);
    }

    /** @return every module path in a {@code require} directive, direct and indirect. */
    static List<String> parse(String manifest) {
        return collect(manifest, false);
    }

    /** @return only the module paths whose {@code require} line is not marked {@code // indirect}. */
    static List<String> direct(String manifest) {
        return collect(manifest, true);
    }

    private static List<String> collect(String manifest, boolean directOnly) {
        List<String> modules = new ArrayList<>();
        for (Requirement requirement : requirements(manifest)) {
            if (!directOnly || !requirement.indirect()) {
                modules.add(requirement.module());
            }
        }
        return modules;
    }

    /** One {@code require} directive: a module path, the version it selects, and whether it is indirect. */
    private record Requirement(String module, String version, boolean indirect) {
    }

    /**
     * Reads every {@code require} directive, in both the multi-line {@code require ( ... )} block and the
     * single-line {@code require <module> <version>} form.
     *
     * <p>A trailing comment is stripped before the fields are split because it is documentation, not part
     * of the version. The indirect marker is read from the raw line rather than from the stripped code: it
     * is exactly the annotation that says "this module is in the build list but nothing imports it
     * directly", which is what separates a direct dependency from a transitive one. Every other directive
     * ({@code module}, {@code go}, {@code toolchain}, {@code replace}, {@code exclude}, {@code retract}) is
     * an instruction about how the build is resolved, not a dependency of the program.
     */
    private static List<Requirement> requirements(String manifest) {
        List<Requirement> requirements = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return requirements;
        }
        boolean inRequireBlock = false;
        for (String raw : manifest.split("\\R", -1)) {
            String line = raw.strip();
            if (inRequireBlock) {
                if (line.startsWith(")")) {
                    inRequireBlock = false;
                    continue;
                }
                addRequirement(line, requirements);
                continue;
            }
            if (!line.startsWith("require")) {
                continue;
            }
            String rest = line.substring("require".length()).strip();
            if (rest.startsWith("(")) {
                inRequireBlock = true;
            } else {
                addRequirement(rest, requirements);
            }
        }
        return requirements;
    }

    /** Adds the requirement on one line, which is {@code <module> <version> [// indirect]}. */
    private static void addRequirement(String line, List<Requirement> requirements) {
        boolean indirect = line.contains("// indirect");
        int comment = line.indexOf("//");
        String code = (comment >= 0 ? line.substring(0, comment) : line).strip();
        if (code.isEmpty()) {
            return;
        }
        String[] parts = code.split("\\s+");
        if (parts.length < 2) {
            // A module path without a version is not a requirement; do not invent one.
            return;
        }
        requirements.add(new Requirement(parts[0], parts[1], indirect));
    }
}

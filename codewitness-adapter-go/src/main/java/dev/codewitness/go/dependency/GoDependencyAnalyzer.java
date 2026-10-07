package dev.codewitness.go.dependency;

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
import java.util.List;

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
        List<String> all = parse(content);
        List<String> direct = direct(content);
        return new DependencyReport(true, "go-module",
                "inventory only: the module graph is read from go.mod and no vulnerability database is "
                        + "consulted, so this cannot claim the dependencies are free of known vulnerabilities",
                all.size(), direct, List.of());
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
        if (manifest == null || manifest.isBlank()) {
            return modules;
        }
        boolean inRequireBlock = false;
        for (String raw : manifest.split("\\R", -1)) {
            String line = raw.strip();
            if (inRequireBlock) {
                if (line.startsWith(")")) {
                    inRequireBlock = false;
                    continue;
                }
                addRequirement(line, directOnly, modules);
                continue;
            }
            if (!line.startsWith("require")) {
                // module, go, toolchain, replace, exclude, retract, comments and blank lines are all
                // ignored: none of them declares a dependency of the program.
                continue;
            }
            String rest = line.substring("require".length()).strip();
            if (rest.startsWith("(")) {
                inRequireBlock = true;
            } else {
                addRequirement(rest, directOnly, modules);
            }
        }
        return modules;
    }

    /**
     * Adds the module path of one requirement line, which is {@code <module> <version> [// indirect]}.
     *
     * <p>A trailing comment is stripped before the fields are split because it is documentation, not
     * part of the version. The indirect marker is read from the raw line rather than from the stripped
     * code: it is exactly the annotation that says "this module is in the build list but nothing
     * imports it directly", which is what separates a direct dependency from a transitive one.
     */
    private static void addRequirement(String line, boolean directOnly, List<String> modules) {
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
        if (directOnly && indirect) {
            return;
        }
        modules.add(parts[0]);
    }
}

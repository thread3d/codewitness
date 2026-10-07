package dev.codewitness.python.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.dependency.TomlPackageTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventories a Python project's dependencies, resolved from {@code poetry.lock} or from pinned
 * requirements when either exists and declared from the manifests otherwise.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads what the project declares - in
 * {@code poetry.lock}, {@code requirements.txt}, {@code pyproject.toml}, {@code setup.py},
 * {@code setup.cfg} or {@code Pipfile} - and never claims that a project is free of vulnerable
 * dependencies; the report says exactly that. A manifest that exists but cannot be read is reported as
 * not analysed rather than as an empty inventory.
 *
 * <p>A declared requirement is a version <em>range</em>, and an advisory is a statement about a version,
 * so a range cannot be queried. Two sources therefore carry resolved versions and are preferred when
 * present: {@code poetry.lock}, and a requirement pinned with {@code ==} (or {@code ===}) in a
 * requirements file. Everything else is still inventoried, with an empty version, and the report states
 * which of the two situations it is in.
 */
public final class PythonDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    private static final Pattern REQUIREMENT = Pattern.compile(
            "(?m)^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(?:\\[[^\\]]*\\])?\\s*(?:[<>=!~]|;|$)");
    /** A requirement pinned to one exact version: {@code name==1.2.3}, {@code name[extra]===1.2.3}. */
    private static final Pattern PINNED = Pattern.compile(
            "(?m)^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(?:\\[[^\\]]*\\])?\\s*===?\\s*([^;\\s,]+)");
    private static final Pattern QUOTED_DEPENDENCY = Pattern.compile(
            "[\"']([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[[^\\]]*\\])?\\s*(?:[<>=!~;][^\"']*)?[\"']");
    private static final Pattern POETRY_DEPENDENCY = Pattern.compile(
            "(?m)^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*=\\s*[\"'{]");

    /** Test tooling and the interpreter itself are not runtime dependencies of the program. */
    private static final Set<String> NOT_A_DEPENDENCY = Set.of("python", "pytest", "coverage");

    @Override
    public String id() {
        return "python-dependencies";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        Path root = context.projectRoot();
        Set<String> dependencies = new LinkedHashSet<>();
        Map<String, String> pinnedVersions = new LinkedHashMap<>();
        boolean analysed = false;

        Path poetryLock = root.resolve("poetry.lock");
        if (Files.isRegularFile(poetryLock)) {
            String locked;
            try {
                locked = read(poetryLock);
            } catch (UncheckedIOException e) {
                return DependencyReport.notAnalysed("poetry-lock",
                        "poetry.lock exists but could not be read: " + e.getMessage());
            }
            List<PackageCoordinate> coordinates = TomlPackageTable.parse(locked, PackageCoordinate.PYPI);
            if (!coordinates.isEmpty()) {
                return DependencyReport.inventory("poetry-lock",
                        "inventory only: every distribution poetry.lock resolves, including transitive "
                                + "ones; whether any of them has a known advisory is recorded by the "
                                + "advisory scan, a separate question from which distributions the program "
                                + "imports",
                        coordinates,
                        Map.of(moduleName(context), coordinates.stream().map(PackageCoordinate::display).toList()));
            }
            return DependencyReport.notAnalysed("poetry-lock",
                    "poetry.lock exists but records no resolved package, so the distributions could not be "
                            + "inventoried");
        }

        try {
            for (Path requirements : requirementFiles(root)) {
                analysed = true;
                String content = read(requirements);
                dependencies.addAll(fromRequirements(content));
                pinnedVersions.putAll(pinnedRequirements(content));
            }
            Path pyproject = root.resolve("pyproject.toml");
            if (Files.isRegularFile(pyproject)) {
                analysed = true;
                String content = read(pyproject);
                dependencies.addAll(fromQuotedArray(content, "dependencies"));
                dependencies.addAll(fromSection(content, "[tool.poetry.dependencies]"));
                dependencies.addAll(fromSection(content, "[tool.pdm.dev-dependencies]"));
            }
            Path setupCfg = root.resolve("setup.cfg");
            if (Files.isRegularFile(setupCfg)) {
                analysed = true;
                dependencies.addAll(fromQuotedArray(read(setupCfg), "install_requires"));
            }
            Path setupPy = root.resolve("setup.py");
            if (Files.isRegularFile(setupPy)) {
                analysed = true;
                dependencies.addAll(fromQuotedArray(read(setupPy), "install_requires"));
            }
            Path pipfile = root.resolve("Pipfile");
            if (Files.isRegularFile(pipfile)) {
                analysed = true;
                dependencies.addAll(fromSection(read(pipfile), "[packages]"));
            }
        } catch (UncheckedIOException e) {
            // A manifest that exists but cannot be read is not an empty dependency set: reporting an
            // empty inventory here would be indistinguishable from "this project has no dependencies".
            return DependencyReport.notAnalysed("python-manifest",
                    "a dependency manifest exists but could not be read: " + e.getMessage());
        }

        if (!analysed) {
            return DependencyReport.notAnalysed("python-manifest",
                    "no dependency manifest (poetry.lock, pyproject.toml, requirements.txt, setup.py, "
                            + "setup.cfg, Pipfile) was found");
        }
        dependencies.removeAll(NOT_A_DEPENDENCY);
        List<PackageCoordinate> resolved = dependencies.stream()
                .map(name -> PackageCoordinate.python(name, pinnedVersions.getOrDefault(
                        name.toLowerCase(Locale.ROOT), "")))
                .toList();
        long pinned = resolved.stream().filter(PackageCoordinate::resolved).count();
        String note = pinned == 0
                ? "inventory only: the distribution names the manifests declare, with the version ranges "
                        + "they declare them at; nothing pins a resolved version, so no advisory service can "
                        + "be asked whether any of them has a known vulnerability"
                : "inventory only: " + pinned + " of " + resolved.size() + " distribution(s) are pinned to an "
                        + "exact version by the manifests and the rest are only declared as ranges; whether "
                        + "any pinned distribution has a known vulnerability is recorded by the advisory "
                        + "scan, a separate question from which distributions the code imports";
        return DependencyReport.inventory("python-manifest", note, resolved,
                Map.of(moduleName(context), resolved.stream().map(PackageCoordinate::display).toList()));
    }

    /** @return the name the report should attribute this project's dependencies to. */
    private static String moduleName(VerificationContext context) {
        ProjectInfo info = context.projectInfo();
        if (!info.modules().isEmpty()) {
            return info.modules().getFirst().name();
        }
        Path fileName = context.projectRoot().getFileName();
        return fileName == null ? "python" : fileName.toString();
    }

    private static List<Path> requirementFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("requirements")
                            && path.getFileName().toString().endsWith(".txt"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable directory simply yields no requirement files
        }
        return files;
    }

    /** Parses a requirements file, ignoring comments, options and blank lines. */
    static List<String> fromRequirements(String content) {
        List<String> names = new ArrayList<>();
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                continue;
            }
            Matcher matcher = REQUIREMENT.matcher(line);
            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * @return the requirements a file pins to exactly one version, keyed by normalised name.
     *
     * <p>Only {@code ==} and {@code ===} count. A range ({@code >=}, {@code ~=}, {@code !=}) and a
     * wildcard ({@code ==1.2.*}) both still admit more than one version, and a version an advisory
     * service is asked about has to be the version that would actually be installed - guessing one from
     * a range would produce findings about a package that was never in the build.
     */
    static Map<String, String> pinnedRequirements(String content) {
        Map<String, String> pins = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return pins;
        }
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                continue;
            }
            Matcher matcher = PINNED.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String version = matcher.group(2).strip();
            if (version.isEmpty() || version.contains("*") || version.contains(",")) {
                continue;
            }
            pins.put(matcher.group(1).toLowerCase(Locale.ROOT), version);
        }
        return pins;
    }

    /** Extracts the quoted dependency names from an inline TOML/Python array such as {@code dependencies = [...]}. */
    static List<String> fromQuotedArray(String content, String key) {
        List<String> names = new ArrayList<>();
        int index = content.indexOf(key);
        if (index < 0) {
            return names;
        }
        int open = content.indexOf('[', index);
        int close = content.indexOf(']', open < 0 ? index : open);
        if (open < 0) {
            return names;
        }
        String body = close < 0 ? content.substring(open) : content.substring(open, close);
        Matcher matcher = QUOTED_DEPENDENCY.matcher(body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Extracts {@code name = ...} keys from an INI/TOML section. */
    static List<String> fromSection(String content, String section) {
        List<String> names = new ArrayList<>();
        int start = content.indexOf(section);
        if (start < 0) {
            return names;
        }
        String body = content.substring(start + section.length());
        int next = body.indexOf("\n[");
        if (next >= 0) {
            body = body.substring(0, next);
        }
        Matcher matcher = POETRY_DEPENDENCY.matcher(body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Propagated rather than swallowed: a manifest that exists but cannot be read must not be
            // reported as a successful, empty inventory.
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}

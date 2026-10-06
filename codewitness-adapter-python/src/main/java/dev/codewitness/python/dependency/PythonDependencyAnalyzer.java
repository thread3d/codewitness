package dev.codewitness.python.dependency;

import dev.codewitness.core.model.DependencyReport;
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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventories a Python project's declared dependencies.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads what the project declares - in
 * {@code pyproject.toml}, {@code requirements.txt}, {@code setup.py}, {@code setup.cfg} or
 * {@code Pipfile} - and reports the names. It never claims that a project is free of vulnerable
 * dependencies, because it does not consult a vulnerability database; the report says exactly that.
 */
public final class PythonDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    private static final Pattern REQUIREMENT = Pattern.compile(
            "(?m)^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(?:\\[[^\\]]*\\])?\\s*(?:[<>=!~]|;|$)");
    private static final Pattern QUOTED_DEPENDENCY = Pattern.compile(
            "[\"']([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[[^\\]]*\\])?\\s*(?:[<>=!~;][^\"']*)?[\"']");
    private static final Pattern POETRY_DEPENDENCY = Pattern.compile(
            "(?m)^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*=\\s*[\"'{]");

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
        boolean analysed = false;

        for (Path requirements : requirementFiles(root)) {
            analysed = true;
            dependencies.addAll(fromRequirements(read(requirements)));
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

        if (!analysed) {
            return DependencyReport.notAnalysed("python-manifest",
                    "no dependency manifest (pyproject.toml, requirements.txt, setup.py, setup.cfg, Pipfile) was found");
        }
        dependencies.remove("python");
        dependencies.remove("pytest");
        dependencies.remove("coverage");
        List<String> direct = List.copyOf(dependencies);
        return new DependencyReport(true, "python-manifest",
                "inventory only: CodeWitness does not consult a vulnerability database, so it cannot claim "
                        + "these dependencies are free of known vulnerabilities",
                direct.size(), direct, List.of());
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
        } catch (IOException _) {
            return "";
        }
    }
}

package dev.codewitness.kotlin.dependency;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.AdvisoryScan;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.kotlin.KotlinBuildTools;
import dev.codewitness.support.dependency.DependencyPathHints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency inventory for Kotlin projects.
 *
 * <p>This is an inventory, not a vulnerability scan. It reports which JVM artifacts the project needs and
 * at which version, and says so in the report's own words; whether any of them has a known advisory is a
 * separate question, answered by the advisory scan, which is the only phase that consults a database.
 *
 * <p>The two build tools are read differently, and the difference is stated in the note rather than hidden:
 *
 * <ul>
 *   <li><strong>Maven</strong> is asked for its own resolved graph with {@code dependency:list}. A Maven
 *       reactor resolves transitive dependencies, dependency management and the BOMs a project imports, so
 *       asking Maven is the only way to know what the build actually puts on the classpath. Running a
 *       lifecycle phase first is load-bearing: a bare {@code dependency:list} is a goal-only invocation and
 *       cannot resolve a reactor's own modules, which makes it fail on a multi-module project.</li>
 *   <li><strong>Gradle</strong> has no equivalent single command that is cheap and machine readable, so its
 *       manifests are read instead: {@code gradle/libs.versions.toml} for the versions a project centralises,
 *       and the build scripts for the declarations themselves. That is the <em>declared</em> version, which is
 *       the version Gradle selects unless a constraint, a platform or a transitive requirement says
 *       otherwise. A declaration whose version is a range, a {@code +} dynamic version or an unresolvable
 *       catalog alias is reported without a version rather than with a guess, and the model treats that as
 *       meaning "the package exists, the resolved version is not known".</li>
 * </ul>
 */
public final class KotlinDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    /** {@code --- dependency:3.8.1:list (default-cli) @ module-name ---} */
    private static final String MAVEN_BANNER = "(default-cli) @ ";

    /** The head of a Gradle dependency declaration: {@code implementation(} and its siblings. */
    private static final Pattern GRADLE_DEPENDENCY_CALL = Pattern.compile(
            "\\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|"
                    + "testCompileOnly|kapt|ksp|annotationProcessor|classpath|platform|testFixtures)"
                    + "\\s*\\(");

    /** A literal coordinate inside a Gradle declaration, with or without an explicit version. */
    private static final Pattern GRADLE_LITERAL = Pattern.compile("[\"']([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+)(?::([^\"']*))?[\"']");

    /** {@code libs.kotlin.stdlib} or {@code libs.versions.kotlin.get()}, the latter having no coordinate. */
    private static final Pattern GRADLE_CATALOG_ALIAS = Pattern.compile("\\blibs\\.([A-Za-z0-9_.]+)");

    /** {@code [libraries]} in a Gradle version catalog. */
    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[([A-Za-z0-9_.\\-]+)]\\s*$");

    private final KotlinBuildTools tools;

    public KotlinDependencyAnalyzer(KotlinBuildTools tools) {
        this.tools = tools;
    }

    @Override
    public String id() {
        return "kotlin-dependencies";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.KOTLIN)
                && (project.buildSystem().id().equals("maven") || project.buildSystem().id().equals("gradle"));
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (project.buildSystem().id().equals("gradle")) {
            return gradleInventory(context);
        }
        return mavenInventory(context);
    }

    /** The Maven half: ask Maven for the graph it resolves. */
    private DependencyReport mavenInventory(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> command = dependencyCommand(context);
        if (command.isEmpty()) {
            return DependencyReport.notAnalysed(id(), "no Maven executable found");
        }
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, project.buildRoot())
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(500_000)
                .build());
        if (!result.success()) {
            // The reason has to travel with the result: this evidence is often the only trace of a
            // dependency phase that produced nothing, and "exit code 1" on its own is not diagnosable.
            return DependencyReport.notAnalysed(id(), "dependency:list failed (" + result.describeOutcome()
                    + "); last output: " + lastLines(result.combinedOutput(), 6));
        }
        List<String> dependencies = parseMavenDependencies(result.combinedOutput());
        Map<String, List<String>> byModule = parseMavenByModule(result.combinedOutput());
        if (dependencies.isEmpty()) {
            return DependencyReport.notAnalysed(id(), "Maven resolved no dependency for this project; last "
                    + "output: " + lastLines(result.combinedOutput(), 4));
        }
        return new DependencyReport(true, id(),
                "inventory only: the dependencies Maven resolves for this project, including the transitive "
                        + "graph, dependency management and imported BOMs; whether any of them has a known "
                        + "advisory is recorded by the advisory scan, which is a separate question from which "
                        + "dependencies exist",
                dependencies.size(), dependencies, byModule, PackageCoordinate.parseMaven(dependencies),
                List.of(), AdvisoryScan.notPerformed());
    }

    /** The Gradle half: read the manifests, which needs no build tool to be installed. */
    private DependencyReport gradleInventory(VerificationContext context) {
        Path root = context.projectRoot();
        Map<String, String> catalog = versionCatalog(read(root.resolve("gradle/libs.versions.toml")));
        Map<String, String> lock = gradleLockfile(read(root.resolve("gradle.lockfile")));

        List<PackageCoordinate> coordinates = new ArrayList<>();
        Map<String, List<String>> byModule = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        int scripts = 0;
        for (var module : context.projectInfo().modules()) {
            for (String name : List.of("build.gradle.kts", "build.gradle")) {
                Path script = module.path().resolve(name);
                if (!Files.isRegularFile(script)) {
                    continue;
                }
                scripts++;
                List<PackageCoordinate> declared = declaredGradleDependencies(read(script), catalog, lock);
                for (PackageCoordinate coordinate : declared) {
                    coordinates.add(coordinate);
                    if (!coordinate.resolved() && !unresolved.contains(coordinate.name())) {
                        unresolved.add(coordinate.name());
                    }
                }
                byModule.put(module.name(), declared.stream().map(PackageCoordinate::display).toList());
                break;
            }
        }
        if (scripts == 0) {
            return DependencyReport.notAnalysed(id(),
                    "no build.gradle or build.gradle.kts was found, so no dependency declaration could be read");
        }
        StringBuilder note = new StringBuilder("inventory only: the dependencies ")
                .append(scripts).append(scripts == 1 ? " build script declares" : " build scripts declare")
                .append(", read from the Gradle build scripts");
        if (!catalog.isEmpty()) {
            note.append(" with the versions this project centralises in gradle/libs.versions.toml");
        }
        if (!lock.isEmpty()) {
            note.append("; gradle.lockfile supplied the resolved version of ").append(lock.size())
                    .append(lock.size() == 1 ? " dependency" : " dependencies");
        } else {
            note.append(". Without a gradle.lockfile this is the declared version, which is the version Gradle "
                    + "selects unless a constraint, a platform or a transitive requirement says otherwise");
        }
        if (!unresolved.isEmpty()) {
            note.append(". ").append(unresolved.size())
                    .append(unresolved.size() == 1 ? " dependency has" : " dependencies have")
                    .append(" no readable version and cannot be asked about: ")
                    .append(String.join(", ", unresolved));
        }
        note.append(". Whether any of these has a known advisory is recorded by the advisory scan, a separate "
                + "question from which packages the program needs");
        List<PackageCoordinate> distinct = PackageCoordinate.distinct(coordinates);
        if (distinct.isEmpty()) {
            return DependencyReport.notAnalysed(id(),
                    "the build scripts declare no dependency this adapter can read; " + note);
        }
        return DependencyReport.inventory(id(), note.toString(), distinct, byModule);
    }

    @Override
    public List<String> dependencyCommand(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (!project.buildSystem().id().equals("maven")) {
            // The Gradle inventory reads manifests and runs nothing.
            return List.of();
        }
        List<String> executable = tools.resolve(project, project.buildRoot());
        if (executable == null) {
            return List.of();
        }
        List<String> goals = new ArrayList<>(executable);
        goals.add("-B");
        goals.add("-ntp");
        goals.add("-Dkotlin.compiler.execution.strategy=in-process");
        // One lifecycle phase before the goal, and this is load-bearing. `dependency:list` alone is a
        // goal-only invocation, and such an invocation cannot resolve the modules of a reactor: it looks
        // for their jars in the local repository, where nothing puts them until someone runs `install`.
        goals.add("compile");
        goals.add("dependency:list");
        goals.add("-DincludeScope=runtime");
        goals.add("-DoutputAbsoluteArtifactFilename=false");
        return List.copyOf(goals);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Kotlin resolves the same Maven Central artifacts a Java project does, whether through Maven or
     * Gradle, so an advisory about one of them is filed under the Maven ecosystem and this analyzer
     * answers for it.
     */
    @Override
    public Set<String> ecosystems() {
        return ECOSYSTEMS;
    }

    /**
     * The one advisory ecosystem this analyzer's coordinates belong to.
     *
     * <p>Published so the runner-aware providers the bundle registers report the same ecosystem without
     * restating it: those wrappers delegate the inventory to this class, and a second copy of the id is a
     * second place for the two to drift apart.
     */
    public static final Set<String> ECOSYSTEMS = Set.of(PackageCoordinate.MAVEN);

    /**
     * {@inheritDoc}
     *
     * <p>The recipe hangs {@code dependency:tree} on the build command the run observed, so it is only
     * built for a Maven project: on a Gradle project the recorded command would be a Gradle invocation
     * that cannot run a Maven goal, and an empty list is the honest answer there.
     */
    @Override
    public List<String> dependencyPathCommand(VerificationContext context, PackageCoordinate coordinate) {
        if (!context.projectInfo().buildSystem().id().equals("maven")) {
            return List.of();
        }
        return DependencyPathHints.mavenCommand(context, coordinate);
    }

    // ------------------------------------------------------------------ Maven output

    /**
     * Splits {@code dependency:list} output into one coordinate list per module.
     *
     * <p>Maven prints a {@code --- dependency:<version>:list (default-cli) @ <module> ---} banner before each
     * module's block, and a module's blocks appear in reactor order. Attributing a coordinate to the module
     * that resolves it is what lets an advisory be tied to the code it actually ships with, so a banner that
     * cannot be read simply ends the current block rather than being guessed at.
     */
    static Map<String, List<String>> parseMavenByModule(String output) {
        Map<String, List<String>> byModule = new LinkedHashMap<>();
        String current = null;
        Set<String> seenForModule = new LinkedHashSet<>();
        for (String line : lines(output)) {
            if (!line.startsWith("[INFO]")) {
                continue;
            }
            String module = moduleBanner(line);
            if (module != null) {
                current = module;
                seenForModule = new LinkedHashSet<>();
                byModule.computeIfAbsent(current, key -> new ArrayList<>());
                continue;
            }
            if (current == null) {
                continue;
            }
            String value = coordinateOf(line);
            if (value != null && seenForModule.add(value)) {
                byModule.get(current).add(value);
            }
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        byModule.forEach((module, coordinates) -> immutable.put(module, List.copyOf(coordinates)));
        return Map.copyOf(immutable);
    }

    /** @return the resolved coordinates in {@code dependency:list} output, in first-seen order. */
    static List<String> parseMavenDependencies(String output) {
        Set<String> coordinates = new LinkedHashSet<>();
        for (String line : lines(output)) {
            if (!line.startsWith("[INFO]")) {
                continue;
            }
            String value = coordinateOf(line);
            if (value != null) {
                coordinates.add(value);
            }
        }
        return List.copyOf(coordinates);
    }

    private static List<String> lines(String output) {
        List<String> lines = new ArrayList<>();
        if (output == null) {
            return lines;
        }
        for (String line : output.split("\\R")) {
            lines.add(line.strip());
        }
        return lines;
    }

    /** @return the module name in a plugin banner line, or {@code null} when the line is not one. */
    private static String moduleBanner(String trimmedLine) {
        int marker = trimmedLine.indexOf(MAVEN_BANNER);
        if (marker < 0) {
            return null;
        }
        String module = trimmedLine.substring(marker + MAVEN_BANNER.length()).strip();
        int end = module.indexOf(' ');
        if (end > 0) {
            module = module.substring(0, end);
        }
        // Maven appends dashes around the banner; a module name never contains them at the edges.
        module = module.replaceAll("^-+|-+$", "").strip();
        return module.isEmpty() ? null : module;
    }

    /** @return the coordinate on a resolved-artifact line, with module-path notes removed. */
    private static String coordinateOf(String trimmedLine) {
        String value = trimmedLine.substring("[INFO]".length()).strip();
        if (value.startsWith("Building") || value.startsWith("The following files")) {
            return null;
        }
        if (!value.contains(":") || value.split(":").length < 4) {
            return null;
        }
        if (!value.contains(":jar") && !value.contains(":pom")) {
            return null;
        }
        int moduleNote = value.indexOf(" -- ");
        return moduleNote > 0 ? value.substring(0, moduleNote).strip() : value;
    }

    // ------------------------------------------------------------------ Gradle manifests

    /**
     * Reads a Gradle version catalog's {@code [libraries]} table.
     *
     * @param toml contents of {@code gradle/libs.versions.toml}
     * @return catalog alias to the coordinate it names, one entry per readable library
     */
    static Map<String, String> versionCatalog(String toml) {
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, String> libraries = new LinkedHashMap<>();
        String section = null;
        if (toml == null || toml.isBlank()) {
            return libraries;
        }
        for (String rawLine : toml.split("\\R", -1)) {
            String line = stripTomlComment(rawLine).strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher header = TOML_SECTION.matcher(line);
            if (header.matches()) {
                section = header.group(1).strip();
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0 || section == null) {
                continue;
            }
            String alias = line.substring(0, equals).strip().replace("\"", "").replace("'", "");
            String value = line.substring(equals + 1).strip();
            if (section.equals("versions")) {
                versions.put(alias, unquote(value));
                continue;
            }
            if (!section.equals("libraries")) {
                continue;
            }
            String coordinate = catalogCoordinate(value, versions);
            if (coordinate != null) {
                libraries.put(alias, coordinate);
            }
        }
        return libraries;
    }

    /** @return the coordinate a catalog library entry names, or {@code null} when it names none. */
    private static String catalogCoordinate(String value, Map<String, String> versions) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            return unquote(value);
        }
        if (!value.startsWith("{")) {
            return null;
        }
        String module = attribute(value, "module");
        String group = attribute(value, "group");
        String name = attribute(value, "name");
        String artifact = module != null ? module : (group != null && name != null ? group + ":" + name : null);
        if (artifact == null) {
            return null;
        }
        String version = attribute(value, "version");
        if (version == null) {
            // `version.ref = "kotlin"` names an entry in [versions]; `version = { ref = "kotlin" }` is the
            // same statement written as a table.
            String ref = reference(value);
            version = ref == null ? null : versions.get(ref);
        }
        return version == null ? artifact : artifact + ":" + version;
    }

    /** @return the value of {@code key = "..."} inside an inline table, or {@code null}. */
    private static String attribute(String inlineTable, String key) {
        Matcher matcher = Pattern.compile("\\b" + key + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(inlineTable);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    /** @return the name referenced by {@code version.ref = "x"} or {@code version = \{ ref = "x" \}}. */
    private static String reference(String inlineTable) {
        Matcher matcher = Pattern.compile("\\bref\\s*=\\s*[\"']([^\"']+)[\"']").matcher(inlineTable);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * @param buildScript {@code build.gradle} or {@code build.gradle.kts}
     * @param catalog     alias to coordinate, as {@link #versionCatalog} reads it
     * @param lock        dependency name to resolved version, as {@link #gradleLockfile} reads it
     * @return one coordinate per dependency declaration the script contains, in the order declared
     */
    static List<PackageCoordinate> declaredGradleDependencies(
            String buildScript, Map<String, String> catalog, Map<String, String> lock) {
        List<PackageCoordinate> coordinates = new ArrayList<>();
        if (buildScript == null || buildScript.isBlank()) {
            return coordinates;
        }
        String script = stripLineComments(buildScript);
        for (String argument : dependencyArguments(script)) {
            Matcher literal = GRADLE_LITERAL.matcher(argument);
            if (literal.find()) {
                coordinates.add(coordinate(literal.group(1), literal.group(2), lock));
                continue;
            }
            Matcher alias = GRADLE_CATALOG_ALIAS.matcher(argument);
            if (alias.find()) {
                // A catalog accessor spells an alias with dots where the file spells it with dashes:
                // `kotlin-stdlib` in gradle/libs.versions.toml is `libs.kotlin.stdlib` in the build script.
                String named = catalog.get(alias.group(1));
                if (named == null) {
                    named = catalog.get(alias.group(1).replace('.', '-'));
                }
                if (named == null) {
                    continue;
                }
                int colon = named.indexOf(':');
                int second = colon < 0 ? -1 : named.indexOf(':', colon + 1);
                String artifact = second < 0 ? named : named.substring(0, second);
                String version = second < 0 ? null : named.substring(second + 1);
                coordinates.add(coordinate(artifact, version, lock));
            }
        }
        return List.copyOf(coordinates);
    }

    /**
     * @param script a Gradle build script with its line comments removed
     * @return the raw argument text of every dependency declaration, in the order declared.
     *
     * <p>The argument is found by scanning for the closing parenthesis rather than by matching up to the
     * first one, because a version range contains a parenthesis of its own:
     * {@code implementation("org.example:ranged:[1.0,2.0)")} is one declaration, and a pattern that stopped
     * at the first {@code )} would cut the coordinate in half and report nothing.
     */
    static List<String> dependencyArguments(String script) {
        List<String> arguments = new ArrayList<>();
        Matcher call = GRADLE_DEPENDENCY_CALL.matcher(script);
        while (call.find()) {
            int open = script.indexOf('(', call.start());
            int depth = 0;
            char quote = 0;
            int i = open;
            for (; i < script.length(); i++) {
                char c = script.charAt(i);
                if (quote != 0) {
                    if (c == quote && (i == 0 || script.charAt(i - 1) != '\\')) {
                        quote = 0;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    quote = c;
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            arguments.add(script.substring(open + 1, Math.min(i, script.length())));
        }
        return List.copyOf(arguments);
    }

    private static PackageCoordinate coordinate(String artifact, String version, Map<String, String> lock) {
        String resolved = version == null || version.isBlank() ? lock.get(artifact) : version;
        if (resolved != null && (resolved.contains("+") || resolved.startsWith("[") || resolved.contains("$"))) {
            // A dynamic version, a range or a property is not a version anything can be asked about, and
            // inventing one would put a wrong version in front of an advisory service.
            resolved = lock.get(artifact);
        }
        return PackageCoordinate.of(PackageCoordinate.MAVEN, artifact, resolved == null ? "" : resolved);
    }

    /**
     * Reads a {@code gradle.lockfile}, which is Gradle's own record of the versions it resolved.
     *
     * @param content the lock file's contents
     * @return coordinate name ({@code group:artifact}) to resolved version
     */
    static Map<String, String> gradleLockfile(String content) {
        Map<String, String> locked = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return locked;
        }
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("empty=")) {
                continue;
            }
            // `group:artifact:version=configuration1,configuration2`
            int equals = line.indexOf('=');
            String coordinate = equals < 0 ? line : line.substring(0, equals);
            String[] parts = coordinate.split(":");
            if (parts.length == 3 && !parts[2].isBlank()) {
                locked.putIfAbsent(parts[0] + ":" + parts[1], parts[2]);
            }
        }
        return locked;
    }

    private static String stripTomlComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /** Removes {@code //} comments while leaving string literals alone. */
    private static String stripLineComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\\R", -1)) {
            StringBuilder kept = new StringBuilder();
            boolean inString = false;
            char quote = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inString) {
                    kept.append(c);
                    if (c == quote && (i == 0 || line.charAt(i - 1) != '\\')) {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    inString = true;
                    quote = c;
                    kept.append(c);
                    continue;
                }
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    break;
                }
                kept.append(c);
            }
            out.append(kept).append('\n');
        }
        return out.toString();
    }

    private static String unquote(String value) {
        String stripped = value.strip();
        if (stripped.length() >= 2 && (stripped.startsWith("\"") || stripped.startsWith("'"))) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }

    private static String lastLines(String text, int count) {
        if (text == null || text.isBlank()) {
            return "(no output)";
        }
        String[] lines = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
            sb.append(lines[i].strip()).append(" | ");
        }
        return sb.toString();
    }
}

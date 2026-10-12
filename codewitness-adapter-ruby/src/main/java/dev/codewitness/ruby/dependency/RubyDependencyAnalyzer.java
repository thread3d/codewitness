package dev.codewitness.ruby.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.ruby.RubyProjectDetector;
import dev.codewitness.support.dependency.DependencyPathHints;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventories a Bundler project's dependencies, resolved from {@code Gemfile.lock}.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads what Bundler resolved, and never claims that
 * a project is free of vulnerable gems; the report says exactly that. A lock file that exists but cannot be
 * read is reported as not analysed rather than as an empty inventory, because "nothing was found" and
 * "nothing could be read" must not look the same in a report.
 *
 * <p>The ecosystem has to be named explicitly because a gem name alone is not queryable. An advisory is a
 * statement about a <em>version</em> in a named ecosystem, and a bare name such as {@code rake} carries
 * neither: it could be a gem, a file, a method. Recording every entry as
 * {@link PackageCoordinate#rubygem(String, String)} is what lets the advisory scan ask RubyGems the
 * question it can actually answer, instead of asking the wrong ecosystem or asking nothing.
 *
 * <p>The lock file's shape is its indentation, so it is read line by line rather than parsed. Under each of
 * the {@code GEM}, {@code PATH} and {@code GIT} sections there is a {@code specs:} block whose entries are
 * the resolved graph: a gem is a line indented exactly four spaces and written {@code name (version)}, and
 * the requirements that gem depends on are indented six or more spaces and belong to the gem above them,
 * not to the project. {@code PATH} and {@code GIT} are read the same way as {@code GEM} because a gem
 * resolved from a git revision or from a local directory still ships with the project and still has a
 * version an advisory applies to.
 *
 * <p>An entry with no version parenthesis, and a gem named in {@code DEPENDENCIES} that no {@code specs}
 * block resolved, is reported with an empty version instead of a guessed one. That empty version is what
 * makes the inventory honest: it says the package is part of the project but not which version is
 * installed, which is what stops the advisory scan from pretending it was checked. Bundler writes a
 * platform-specific variant as {@code nokogiri (1.16.0-arm64-darwin)}; the platform is not part of the
 * version RubyGems or an advisory service knows, so it is dropped and the version Bundler resolved is
 * kept.
 */
public final class RubyDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    /** The lock file this provider reads; it is the only record of the versions Bundler resolved. */
    private static final String LOCK_FILE = "Gemfile.lock";

    /** The declaration a Bundler project is recognised by when no lock file has been created yet. */
    private static final String MANIFEST_FILE = "Gemfile";

    /** How deep in a {@code specs:} block a resolved gem sits; anything deeper belongs to the gem above. */
    private static final int SPEC_INDENT = 4;

    /** What the inventory covers, stated in the report's own vocabulary. */
    private static final String LOCK_NOTE = "inventory only: every gem Gemfile.lock resolves, direct and "
            + "transitive, with the version Bundler resolved; whether any of them has a known advisory is "
            + "recorded by the advisory scan, a separate question from which gems the code uses";

    @Override
    public String id() {
        return "bundler";
    }

    /**
     * {@inheritDoc}
     *
     * <p>A Ruby tree with no {@code Gemfile} and no {@code Gemfile.lock} is not a Bundler project: the
     * sources can still be built, tested and analysed, but there is no resolved graph to inventory, and
     * claiming one would attribute dependencies to a project that never declared them.
     */
    @Override
    public boolean supports(ProjectInfo project) {
        if (project == null || !LanguageId.RUBY.equals(project.language())) {
            return false;
        }
        return projectRoots(project).stream().anyMatch(RubyDependencyAnalyzer::holdsManifest);
    }

    /** {@inheritDoc} A Bundler-resolved gem is an advisory's RubyGems package. */
    @Override
    public Set<String> ecosystems() {
        return Set.of(PackageCoordinate.RUBYGEMS);
    }

    /** {@inheritDoc} */
    @Override
    public String dependencyPathHint(PackageCoordinate coordinate) {
        return DependencyPathHints.rubygem(coordinate);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The lock file is read directly, so no command is run: {@code bundle lock} would rewrite the file
     * this analysis is a report about, and {@code bundle list} needs the gems installed and, for a project
     * whose bundle path is empty, a network round trip that a verifier must not make just to list what a
     * file already says.
     */
    @Override
    public List<String> dependencyCommand(VerificationContext context) {
        return List.of();
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        Path lock = context.projectRoot().resolve(LOCK_FILE);
        if (!Files.isRegularFile(lock)) {
            return DependencyReport.notAnalysed("bundler-lock",
                    "no Gemfile.lock was found, so Bundler has not resolved this project's gems; the "
                            + "resolved graph is what an advisory scan is asked about, and a Gemfile names "
                            + "requirements rather than the versions that were installed");
        }
        String text = RubyProjectDetector.readString(lock);
        if (text.isEmpty()) {
            return DependencyReport.notAnalysed("bundler-lock", "Gemfile.lock exists but could not be read "
                    + "(it is unreadable or empty); the resolved dependency graph is what an advisory scan "
                    + "is asked about, so the inventory was not produced");
        }
        List<PackageCoordinate> coordinates = fromLock(text);
        if (coordinates.isEmpty()) {
            return DependencyReport.notAnalysed("bundler-lock",
                    "Gemfile.lock exists but resolved no gem in its GEM, PATH and GIT sections, so there is "
                            + "nothing to inventory");
        }
        long unresolved = coordinates.stream().filter(coordinate -> !coordinate.resolved()).count();
        StringBuilder note = new StringBuilder(LOCK_NOTE);
        if (unresolved > 0) {
            note.append("; ").append(unresolved).append(" of the ").append(coordinates.size())
                    .append(" name no resolved version - a gem line with no version parenthesis, or a gem "
                            + "DEPENDENCIES asks for that no specs line resolved - so no advisory service "
                            + "can be asked about them");
        }
        return DependencyReport.inventory("bundler-lock", note.toString(), coordinates,
                byModule(context, coordinates));
    }

    /**
     * Parses a {@code Gemfile.lock}.
     *
     * @param lock the lock file's text
     * @return one coordinate per gem the lock resolves, in the order the lock lists them, followed by one
     *     unresolved coordinate per requested gem that no {@code specs} block resolved
     */
    static List<PackageCoordinate> fromLock(String lock) {
        Map<String, PackageCoordinate> resolved = new LinkedHashMap<>();
        List<String> requested = new ArrayList<>();
        String section = "";
        boolean inSpecs = false;
        for (String raw : lock.split("\\R", -1)) {
            if (raw.isBlank()) {
                continue;
            }
            String line = raw.strip();
            int indent = indentation(raw);
            if (indent == 0) {
                // A top-level line names the section; Bundler writes one section per block.
                section = line;
                inSpecs = false;
                continue;
            }
            if (section.equals("GEM") || section.equals("PATH") || section.equals("GIT")) {
                if (line.equals("specs:")) {
                    inSpecs = true;
                    continue;
                }
                if (inSpecs && indent == SPEC_INDENT) {
                    registerSpec(resolved, line);
                }
                // Anything indented further is a requirement of the gem above it, not a gem of this
                // project, and reading one as a dependency would invent a package the lock does not list.
                continue;
            }
            if (section.equals("DEPENDENCIES") && indent == 2) {
                String name = requestedName(line);
                if (!name.isEmpty() && !requested.contains(name)) {
                    requested.add(name);
                }
            }
        }
        List<PackageCoordinate> coordinates = new ArrayList<>(resolved.values());
        for (String name : requested) {
            if (!resolved.containsKey(name)) {
                coordinates.add(PackageCoordinate.rubygem(name, ""));
            }
        }
        return List.copyOf(coordinates);
    }

    /**
     * @param specs the coordinates found so far, keyed by gem name
     * @param line  one four-space-indented line of a {@code specs:} block
     */
    private static void registerSpec(Map<String, PackageCoordinate> specs, String line) {
        String name = line;
        String version = "";
        int open = line.indexOf(" (");
        if (open > 0 && line.endsWith(")")) {
            name = line.substring(0, open).strip();
            version = line.substring(open + 2, line.length() - 1).strip();
            int platform = version.indexOf('-');
            if (platform > 0) {
                // A platform-specific variant: the platform is part of the build, not of the version an
                // advisory is a statement about, and a version like "1.16.0-arm64-darwin" would be asked
                // about as if it were a release that exists.
                version = version.substring(0, platform);
            }
        }
        if (name.isEmpty() || name.indexOf(' ') >= 0) {
            return;
        }
        specs.putIfAbsent(name, PackageCoordinate.rubygem(name, version));
    }

    /**
     * @param entry one line of the {@code DEPENDENCIES} section, for example {@code rspec (~> 3.12)} or
     *              {@code rails!}
     * @return the gem name the line asks for, or the empty string when the line names none
     */
    static String requestedName(String entry) {
        String value = entry.strip();
        int end = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '(') {
                end = i;
                break;
            }
        }
        String name = value.substring(0, end);
        // A trailing "!" marks a gem pinned to a git revision or a path; it is Bundler's syntax, not part
        // of the name, and a coordinate carrying it would never match the advisory service.
        return name.endsWith("!") ? name.substring(0, name.length() - 1) : name;
    }

    /** @return the number of leading spaces, which is how the lock file states its structure. */
    private static int indentation(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** @return {@code true} when the directory holds the manifest or the lock file a Bundler project has. */
    private static boolean holdsManifest(Path root) {
        return Files.isRegularFile(root.resolve(MANIFEST_FILE))
                || Files.isRegularFile(root.resolve(LOCK_FILE));
    }

    /**
     * @return every directory a Bundler manifest can sit in: the build root, each module, and the
     *     checked-out tree
     *
     * <p>{@code supports} is handed project information rather than a context, and the detector that
     * builds it does not always set a build root, so all three are consulted. Testing only one would
     * either claim a project Bundler has never seen or refuse one that is plainly a Bundler project.
     */
    private static List<Path> projectRoots(ProjectInfo project) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        if (project.buildRoot() != null) {
            roots.add(project.buildRoot());
        }
        project.modules().forEach(module -> roots.add(module.path()));
        if (project.project() != null && project.project().root() != null) {
            roots.add(project.project().root());
        }
        return List.copyOf(roots);
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
        return fileName == null ? "ruby" : fileName.toString();
    }
}

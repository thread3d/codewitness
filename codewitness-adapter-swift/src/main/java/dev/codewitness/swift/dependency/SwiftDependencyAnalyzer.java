package dev.codewitness.swift.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.dependency.DependencyPathHints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventories a Swift package's dependencies from its {@code Package.resolved}.
 *
 * <p>This is an inventory, not a vulnerability scan. It reads what SwiftPM resolved and never claims
 * that a package is free of vulnerable dependencies; the report says exactly that. A lock file that
 * exists but cannot be parsed is reported as not analysed rather than as an empty inventory, because
 * "nothing was found" and "nothing could be read" must not look the same in a report.
 *
 * <p>SwiftPM identifies a package by where it is fetched from, not by a registry-assigned name, so a
 * pin's URL is the identity {@link PackageCoordinate#swiftPackage(String, String)} records. That factory
 * reduces the URL to the host-and-path form the advisory service matches on
 * ({@code https://github.com/apple/swift-nio.git} to {@code github.com/apple/swift-nio}), because the
 * raw URL never matches a {@code SwiftURL} record and the scan would come back empty for every package.
 * A bare identity such as {@code Alamofire} is not queryable in that ecosystem - the same objection the
 * Ruby and PHP analyzers make about a bare gem or package name - which is why the URL is preferred even
 * when the pin also carries an identity.
 *
 * <p>Two shapes are in the wild and both are read: the version 1 layout nests the pins under
 * {@code object}, and the version 2 and 3 layouts put them at the top level. The pin's URL is
 * {@code repositoryURL} in the first and {@code location} in the others, and the resolved version is
 * {@code state.version} with {@code state.revision} as the fallback for a pin that tracks a revision
 * rather than a release. A pin that declares neither is recorded with an empty version - meaningful,
 * not missing: it says the package is part of the graph but no advisory service can be asked about it -
 * and a branch name is never promoted to a version.
 *
 * <p>No command is executed: the lock file is read directly, so the phase works without the network and
 * without rewriting the file it is a report about.
 */
public final class SwiftDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    /** How the report names this inventory. */
    private static final String TOOL = "swiftpm";

    /** The lock file this analyzer reads; it is the only record of the versions SwiftPM resolved. */
    private static final String RESOLVED_FILE = "Package.resolved";

    /** The declaration a Swift package is recognised by when no lock file has been written yet. */
    private static final String MANIFEST_FILE = "Package.swift";

    /**
     * What an inventory of zero means when the package has a manifest but no lock file.
     *
     * <p>It is deliberately careful about which claim it makes: a package that has only {@code Package.swift}
     * either declares no external dependencies or has not resolved them yet, and in both cases there is no
     * resolved graph to inventory. Saying "declares no external dependencies" without that qualification
     * would turn an unresolved package into a false statement about its manifest.
     */
    private static final String NO_DEPENDENCIES_NOTE = "the package declares no external dependencies "
            + "(no Package.resolved); SwiftPM records the resolved external graph in Package.resolved, so a "
            + "package with only Package.swift either declares none or has not resolved them yet. Either way "
            + "the resolved inventory is empty, which is a measurement of zero rather than a failure; the "
            + "advisory scan has nothing to ask about";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return TOOL;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project != null && LanguageId.SWIFT.equals(project.language());
    }

    /** {@inheritDoc} A pin in {@code Package.resolved} is an advisory's SwiftURL package. */
    @Override
    public Set<String> ecosystems() {
        return Set.of(PackageCoordinate.SWIFT_URL);
    }

    /** {@inheritDoc} */
    @Override
    public String dependencyPathHint(PackageCoordinate coordinate) {
        return DependencyPathHints.swift(coordinate);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The lock file is read directly, so no command is run: {@code swift package resolve} would rewrite
     * the very {@code Package.resolved} this analysis is a report about, and {@code swift package
     * show-dependencies} needs the package graph fetched first - a network round trip a verifier must not
     * make just to list what a file already says. An empty list is the model's statement that the analysis
     * reads the project's manifests, not that it has no way to find them.
     */
    @Override
    public List<String> dependencyCommand(VerificationContext context) {
        return List.of();
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        try {
            Path root = context.projectRoot();
            Path resolved = root.resolve(RESOLVED_FILE);
            if (!Files.isRegularFile(resolved)) {
                if (Files.isRegularFile(root.resolve(MANIFEST_FILE))) {
                    return DependencyReport.inventory(TOOL, NO_DEPENDENCIES_NOTE, List.of(), Map.of());
                }
                // Neither file: this tree is not a SwiftPM package, so there is no resolved graph at all.
                // Not analysed, because an empty inventory would claim a package that was never there.
                return DependencyReport.notAnalysed(TOOL, "neither " + RESOLVED_FILE + " nor "
                        + MANIFEST_FILE + " was found, so this tree is not a SwiftPM package and there is "
                        + "no resolved dependency graph to inventory");
            }

            String json;
            try {
                json = Files.readString(resolved, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return DependencyReport.notAnalysed(TOOL, RESOLVED_FILE + " exists but could not be read ("
                        + e.getClass().getSimpleName() + "): " + e.getMessage());
            }

            JsonNode document;
            try {
                document = MAPPER.readTree(json);
            } catch (Exception e) {
                // The message is the parser's own, so a malformed lock file is diagnosable rather than
                // reported as an empty dependency set.
                return DependencyReport.notAnalysed(TOOL, RESOLVED_FILE + " exists but could not be parsed ("
                        + e.getClass().getSimpleName() + "): " + e.getMessage());
            }

            JsonNode pins = pins(document);
            if (pins == null) {
                return DependencyReport.notAnalysed(TOOL, RESOLVED_FILE + " exists but carries no pins array "
                        + "in either the version 1 (object.pins) or the version 2/3 (pins) shape, so the "
                        + "resolved graph could not be read");
            }

            List<PackageCoordinate> coordinates = new ArrayList<>();
            int unnamed = 0;
            for (JsonNode pin : pins) {
                PackageCoordinate coordinate = coordinate(pin);
                if (coordinate == null) {
                    unnamed++;
                } else {
                    coordinates.add(coordinate);
                }
            }
            return DependencyReport.inventory(TOOL, note(coordinates, unnamed), coordinates,
                    byModule(context, coordinates));
        } catch (RuntimeException e) {
            return DependencyReport.notAnalysed(TOOL, "the SwiftPM dependency inventory could not be "
                    + "produced (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }

    /**
     * @param document a parsed {@code Package.resolved}
     * @return the pins array of either shape, or {@code null} when the document carries neither
     *
     * <p>Version 1 nests the pins under {@code object}; versions 2 and 3 put them at the top level. The
     * shapes are not merged: only one of them is present, and following both on one document would read
     * an unrelated {@code pins} key from a document of the other shape.
     */
    static JsonNode pins(JsonNode document) {
        if (document == null || !document.isObject()) {
            return null;
        }
        JsonNode top = document.path("pins");
        if (top.isArray()) {
            return top;
        }
        JsonNode legacy = document.path("object").path("pins");
        return legacy.isArray() ? legacy : null;
    }

    /**
     * @param pin one entry of a {@code Package.resolved} pins array
     * @return the coordinate the pin records, or {@code null} when the pin carries no identifier at all
     *
     * <p>The URL is the identity: {@code location} in versions 2 and 3, {@code repositoryURL} in version 1.
     * A pin that carries neither still names its package under {@code identity} (versions 2 and 3) or
     * {@code package} (version 1); that is not the URL the ecosystem matches on, but it is the only
     * identity the pin has, and dropping it would under-report the graph the project resolved. A name is
     * required to build a coordinate, so a pin with none of the four is left out and counted in the note
     * rather than given an invented name that an advisory service would be asked about.
     */
    static PackageCoordinate coordinate(JsonNode pin) {
        if (pin == null || !pin.isObject()) {
            return null;
        }
        String name = firstText(pin, "location");
        if (name == null) {
            name = firstText(pin, "repositoryURL");
        }
        if (name == null) {
            name = firstText(pin, "identity");
        }
        if (name == null) {
            name = firstText(pin, "package");
        }
        if (name == null) {
            return null;
        }
        // state.version is the release the pin resolved; state.revision is what a pin that tracks a branch
        // or a revision carries instead. A branch name is neither, so a pin that declares only a branch is
        // recorded unresolved rather than being asked about as if it were a version.
        JsonNode state = pin.path("state");
        String version = text(state.path("version"));
        if (version == null) {
            version = text(state.path("revision"));
        }
        return PackageCoordinate.swiftPackage(name, version == null ? "" : version);
    }

    /** @return the sentence the report uses to describe what this inventory covers. */
    static String note(List<PackageCoordinate> coordinates, int unnamed) {
        long unresolved = coordinates.stream().filter(coordinate -> !coordinate.resolved()).count();
        StringBuilder note = new StringBuilder("inventory only: every pin in Package.resolved, with the "
                + "version SwiftPM resolved or the revision when the pin declares no version. SwiftPM "
                + "identifies a package by its location, so each coordinate is queryable under the "
                + "location reduced to the host and path the advisory service names a Swift package by, "
                + "while the display keeps the URL the pin recorded; whether any of them has a known "
                + "advisory is recorded by the advisory scan, a separate question from which packages the "
                + "code uses");
        if (unresolved > 0) {
            note.append("; ").append(unresolved).append(" of the ").append(coordinates.size())
                    .append(" pin(s) name neither a version nor a revision, so they are recorded unresolved "
                            + "and no advisory service can be asked about them");
        }
        if (unnamed > 0) {
            note.append("; ").append(unnamed).append(" pin(s) carried no location, repository URL, identity "
                    + "or package name, so they could not be named as a SwiftURL coordinate and were left "
                    + "out rather than invented");
        }
        return note.toString();
    }

    /** @return the dependencies attributed to the project's own module, keyed by that module's name. */
    private static Map<String, List<String>> byModule(
            VerificationContext context, List<PackageCoordinate> coordinates) {
        return Map.of(moduleName(context), coordinates.stream().map(PackageCoordinate::display).toList());
    }

    /** @return the name the report should attribute this package's dependencies to. */
    private static String moduleName(VerificationContext context) {
        ProjectInfo info = context.projectInfo();
        if (info != null && !info.modules().isEmpty()) {
            return info.modules().getFirst().name();
        }
        Path fileName = context.projectRoot().getFileName();
        return fileName == null ? "swift" : fileName.toString();
    }

    private static String firstText(JsonNode object, String field) {
        return object == null ? null : text(object.path(field));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }
}

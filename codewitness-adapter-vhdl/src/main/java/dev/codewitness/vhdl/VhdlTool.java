package dev.codewitness.vhdl;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;
import dev.codewitness.support.Text;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates the GHDL executable a project is analysed and simulated with, and describes what it reported.
 *
 * <p>GHDL is one program with three roles: {@code ghdl -a} analyses a design unit into a work library,
 * {@code ghdl -e} elaborates an entity, and {@code ghdl -r} runs it. The single executable is resolved
 * once, during inspection, and recorded in the project attributes so every later phase uses the same
 * toolchain. There is no build system to select and no manifest to read: the executable is the whole
 * toolchain, and a machine that lacks it cannot verify a VHDL tree at all, which the probe reports
 * rather than hiding.
 *
 * <p>Unlike a package manager's, the environment needs nothing redirected. GHDL reads only the sources
 * it is given and writes the work library where it is told to ({@code --workdir}), so a run cannot read
 * or write a developer's global state through it. The adapter always passes that directory explicitly,
 * inside the run's artefact store, rather than letting GHDL default to the project tree.
 */
public final class VhdlTool {

    /** Project attributes set by this class. */
    public static final String ATTR_GHDL = "ghdl";
    public static final String ATTR_VERSION = "ghdlVersion";

    /** The language revision every command passes: VHDL-2008, whose {@code std.env} the adapter uses. */
    public static final String STD_FLAG = "--std=08";

    /** GHDL's work library file, written into whatever directory {@code --workdir} names. */
    public static final String WORK_LIBRARY = "work-obj08.cf";

    private static final Pattern VERSION = Pattern.compile("GHDL\\s+(\\S+)");

    private VhdlTool() {
    }

    /** @return the {@code ghdl} executable to invoke, resolved from the run's host environment. */
    public static String ghdl(ProjectInfo project, VerificationContext context) {
        String recorded = project.attributes().get(ATTR_GHDL);
        if (recorded != null && !recorded.isBlank()) {
            return recorded;
        }
        return resolve("ghdl", context.projectRoot(), System.getenv());
    }

    /**
     * Resolves the GHDL executable without running anything.
     *
     * @param name            {@code ghdl}
     * @param projectRoot     project directory, used to find a project-local toolchain
     * @param hostEnvironment environment to read the override variable and {@code PATH} from
     * @return an absolute path when one can be found, otherwise the bare command name, which the
     *     sandbox resolves through {@code PATH}
     */
    public static String resolve(String name, Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : candidates(name, projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return isWindows() ? name + ".exe" : name;
    }

    private static List<Path> candidates(String name, Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        String executable = isWindows() ? name + ".exe" : name;
        // A project may vendor its own toolchain under tools/, which is the convention several HDL CI
        // images use; checking it first honours the toolchain the developer pinned.
        if (projectRoot != null) {
            candidates.add(projectRoot.resolve("tools").resolve(executable));
            candidates.add(projectRoot.resolve("tools").resolve("bin").resolve(executable));
            candidates.add(projectRoot.resolve(".tools").resolve("bin").resolve(executable));
        }
        String configured = hostEnvironment == null ? null : hostEnvironment.get("GHDL");
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        for (String directory : List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin")) {
            candidates.add(Path.of(directory, executable));
        }
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (!element.isBlank()) {
                    candidates.add(Path.of(element, executable));
                }
            }
        }
        return candidates;
    }

    /**
     * Asks GHDL for its version.
     *
     * @param context run context, providing the sandboxed runner
     * @param ghdlCommand the resolved {@code ghdl} command
     * @return what the toolchain reported; a missing toolchain is a reported fact, not a failure
     */
    public static Probe probe(VerificationContext context, String ghdlCommand) {
        ProcessResult version = context.runner().run(ProcessSpec.builder(
                        List.of(ghdlCommand, "--version"), context.projectRoot())
                .timeout(java.time.Duration.ofSeconds(60))
                .build());
        if (!version.started() || !version.success()) {
            return new Probe(null, version.describeOutcome());
        }
        Matcher matcher = VERSION.matcher(version.combinedOutput());
        return new Probe(matcher.find() ? matcher.group(1) : firstLine(version.combinedOutput()), null);
    }

    /** @return the environment a GHDL command should run with; the toolchain needs none of its own. */
    public static Map<String, String> environment(VerificationContext context) {
        // Deliberately empty rather than omitted: a ProcessSpec merges this over the sandbox baseline,
        // and stating that GHDL needs nothing keeps the fact visible in the one place a reader looks.
        return new LinkedHashMap<>();
    }

    /** @return {@code true} when GHDL was found and recorded during inspection. */
    public static boolean isAvailable(VerificationContext context) {
        String recorded = context.projectInfo().attributes().get(ATTR_GHDL);
        return recorded != null && !recorded.isBlank();
    }

    /** @return the last {@code count} lines of a captured output, for reports and diagnostics. */
    public static String lastLines(String text, int count) {
        return Text.lastLines(text, count);
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.strip().split("\\R")[0].strip();
    }

    static boolean isWindows() {
        return Platform.isWindows();
    }

    /**
     * What the toolchain reported about itself.
     *
     * @param version       the version string, for example {@code 5.0.1}, or {@code null}
     * @param failureReason why the probe failed, or {@code null}
     */
    public record Probe(String version, String failureReason) {

        public boolean available() {
            return failureReason == null;
        }
    }
}

package dev.codewitness.rust;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import dev.codewitness.support.Text;
import dev.codewitness.support.Platform;

/**
 * Locates the Cargo executable used to build a project, and describes what it can do.
 *
 * <p>Cargo is resolved once, during inspection, and recorded in the project attributes so every
 * phase uses the same one. Everything else in the adapter is a thin wrapper over a Cargo command:
 * Cargo is the single, universal Rust build tool, so unlike Java there is no build-system selection
 * to make.
 *
 * <p>Network access is governed by the run's sandbox configuration rather than assumed: when
 * {@code sandbox.allow_network} is false the adapter sets {@code CARGO_NET_OFFLINE}, so a build that
 * would need to download a crate fails loudly instead of silently reaching the network.
 */
public final class Cargo {

    /** Project attributes set by this class. */
    public static final String ATTR_CARGO = "cargo";
    public static final String ATTR_VERSION = "cargoVersion";
    public static final String ATTR_HAS_LLVM_COV = "hasCargoLlvmCov";
    public static final String ATTR_HAS_TARPAULIN = "hasCargoTarpaulin";
    public static final String ATTR_HAS_MUTANTS = "hasCargoMutants";

    private Cargo() {
    }

    /**
     * Resolves a Cargo executable without running anything.
     *
     * @param projectRoot project directory, used to find a project-local toolchain
     * @param hostEnvironment environment to read {@code CARGO_HOME} and {@code PATH} from
     * @return an absolute path when one can be found, otherwise a bare command name the sandbox
     *     resolves through {@code PATH}
     */
    public static String resolve(Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : candidates(projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return isWindows() ? "cargo.exe" : "cargo";
    }

    private static List<Path> candidates(Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        String cargoHome = hostEnvironment == null ? null : hostEnvironment.get("CARGO_HOME");
        if (cargoHome != null && !cargoHome.isBlank()) {
            candidates.add(Path.of(cargoHome, "bin", executableName()));
        }
        String home = hostEnvironment == null ? null : hostEnvironment.get("HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, ".cargo", "bin", executableName()));
        }
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (!element.isBlank()) {
                    candidates.add(Path.of(element, executableName()));
                }
            }
        }
        return candidates;
    }

    private static String executableName() {
        return isWindows() ? "cargo.exe" : "cargo";
    }

    /**
     * Asks Cargo for its version and lists the subcommands that are installed.
     *
     * <p>The subcommand list is how optional tools such as {@code cargo-llvm-cov} are discovered
     * without assuming they exist: a missing optional tool is a reported fact, not a failure.
     *
     * @param context run context, providing the sandboxed runner
     * @param cargo   cargo command or absolute path
     * @return what Cargo reported; every optional tool defaults to unavailable
     */
    public static Probe probe(VerificationContext context, String cargo) {
        Map<String, String> environment = environment(context.config().sandbox().allowNetwork());
        ProcessResult version = context.runner().run(ProcessSpec.builder(
                        List.of(cargo, "--version"), context.projectRoot())
                .environment(environment)
                .timeout(Duration.ofSeconds(60))
                .build());
        if (!version.started() || !version.success()) {
            return new Probe(null, false, false, false, version.describeOutcome());
        }
        ProcessResult list = context.runner().run(ProcessSpec.builder(
                        List.of(cargo, "--list"), context.projectRoot())
                .environment(environment)
                .timeout(Duration.ofSeconds(60))
                .maxOutputBytes(200_000)
                .build());
        String commands = list.success() ? list.combinedOutput() : "";
        String reported = firstLine(version.combinedOutput());
        return new Probe(
                reported,
                containsCommand(commands, "llvm-cov"),
                containsCommand(commands, "tarpaulin"),
                containsCommand(commands, "mutants"),
                null);
    }

    private static boolean containsCommand(String listOutput, String command) {
        if (listOutput == null || listOutput.isBlank()) {
            return false;
        }
        for (String line : listOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.equals(command) || trimmed.startsWith(command + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the environment for a Cargo command.
     *
     * <p>The sandbox drops the host environment, so everything Cargo needs is stated explicitly. A
     * run that is not allowed network access is forced offline: that turns "Cargo silently downloaded
     * a dependency" into a loud, reproducible failure.
     *
     * @param allowNetwork whether the run's sandbox configuration permits network access
     * @return environment additions for a {@link ProcessSpec}
     */
    public static Map<String, String> environment(boolean allowNetwork) {
        Map<String, String> environment = new LinkedHashMap<>();
        // Colour escape sequences make captured output harder to parse and to read in a report.
        environment.put("CARGO_TERM_COLOR", "never");
        environment.put("RUST_BACKTRACE", "0");
        if (!allowNetwork) {
            environment.put("CARGO_NET_OFFLINE", "true");
        }
        return environment;
    }

    /** @return the Cargo command recorded during inspection, or a PATH-resolved default. */
    public static String cargo(ProjectInfo project, VerificationContext context) {
        String resolved = project.attributes().get(ATTR_CARGO);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return resolve(context.projectRoot(), System.getenv());
    }

    /** @return the environment a Cargo command should run with. */
    public static Map<String, String> environment(VerificationContext context) {
        return environment(context.config().sandbox().allowNetwork());
    }

    /** @return {@code true} when the inspected project has a Cargo manifest and can be built. */
    public static boolean hasManifest(VerificationContext context) {
        return Files.isRegularFile(context.projectRoot().resolve("Cargo.toml"));
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
     * What Cargo reported about itself.
     *
     * @param version       the string printed by {@code cargo --version}, or {@code null}
     * @param llvmCov       whether {@code cargo llvm-cov} is installed
     * @param tarpaulin     whether {@code cargo tarpaulin} is installed
     * @param mutants       whether {@code cargo mutants} is installed
     * @param failureReason why the probe failed, or {@code null}
     */
    public record Probe(
            String version, boolean llvmCov, boolean tarpaulin, boolean mutants, String failureReason) {

        public boolean available() {
            return failureReason == null;
        }
    }
}

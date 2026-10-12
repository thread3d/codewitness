package dev.codewitness.csharp;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;
import dev.codewitness.support.Text;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates the {@code dotnet} executable used to build a project, and describes what it reported.
 *
 * <p>Like the Go toolchain, the .NET SDK is one driver for everything: {@code dotnet build},
 * {@code dotnet test}, {@code dotnet list package}. It is resolved once, during inspection, and
 * recorded in the project attributes so every phase uses the same toolchain, and every phase states
 * the same environment explicitly.
 *
 * <p>Three environment decisions are worth naming, because each of them is a way a verification run
 * could otherwise depend on something the report does not mention:
 *
 * <ul>
 *   <li><b>NuGet's own audit is disabled</b> ({@code -p:NuGetAudit=false}). It would make every build
 *       fetch advisory data from {@code api.nuget.org} and print warnings about packages that
 *       CodeWitness never reports. CodeWitness runs its own advisory scan over the same packages, one
 *       that is recorded as evidence; a second, invisible one inside the compiler is noise at best and
 *       a network dependency at worst.</li>
 *   <li><b>Per-run state lives in the run directory.</b> The CLI's home and NuGet's HTTP cache are
 *       redirected under the run's artefact directory, so a run cannot read or write a developer's
 *       global .NET state. The <em>package</em> folder is deliberately left alone: it is where the
 *       machine already keeps the packages it has restored, and pointing it somewhere else would turn
 *       every project into a download.</li>
 *   <li><b>Diagnostics are pinned to English</b> ({@code DOTNET_CLI_UI_LANGUAGE=en}). The compiler's
 *       diagnostic format is parsed, and a localised MSBuild would change the words being matched
 *       without changing anything the report says.</li>
 * </ul>
 *
 * <p>MSBuild's node reuse is disabled as well. A verification run mutates sources and rebuilds;
 * a reused build node could answer from the in-memory state of the previous build, which is exactly
 * the kind of "remembered pass" this tool refuses elsewhere.
 */
public final class DotnetTool {

    /** Project attributes set by this class. */
    public static final String ATTR_DOTNET = "dotnet";
    public static final String ATTR_VERSION = "dotnetVersion";

    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    private DotnetTool() {
    }

    /**
     * Resolves a {@code dotnet} executable without running anything.
     *
     * @param projectRoot     project directory, used to find a project-local toolchain
     * @param hostEnvironment environment to read {@code DOTNET_ROOT} and {@code PATH} from
     * @return an absolute path when one can be found, otherwise a bare command name the sandbox
     *     resolves through {@code PATH}
     */
    public static String resolve(Path projectRoot, Map<String, String> hostEnvironment) {
        return resolve(projectRoot, hostEnvironment, conventionalInstallRoots());
    }

    /**
     * Resolves a {@code dotnet} executable against an explicit list of machine-wide install roots.
     *
     * <p>The conventional locations are a fact about the host, and a test cannot remove them from the
     * machine it runs on. Taking them as an argument is what lets a test state the whole search space -
     * including that there is none - so the fallback is exercised both on a machine with no .NET SDK
     * and on a build agent that has one installed at a conventional location.
     *
     * @param projectRoot     project directory, used to find a project-local toolchain
     * @param hostEnvironment environment to read {@code DOTNET_ROOT}, {@code HOME} and {@code PATH} from
     * @param installRoots    machine-wide install directories to probe, before {@code PATH}
     * @return an absolute path when one can be found, otherwise a bare command name the sandbox
     *     resolves through {@code PATH}
     */
    static String resolve(Path projectRoot, Map<String, String> hostEnvironment, List<Path> installRoots) {
        for (Path candidate : candidates(projectRoot, hostEnvironment, installRoots)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return executableName();
    }

    /** @return the machine-wide install locations the SDK's installer and the package managers use. */
    static List<Path> conventionalInstallRoots() {
        return List.of(
                Path.of("/usr/local/share/dotnet"),
                Path.of("/usr/share/dotnet"),
                Path.of("/opt/homebrew/opt/dotnet/libexec"));
    }

    private static List<Path> candidates(Path projectRoot, Map<String, String> hostEnvironment,
            List<Path> installRoots) {
        List<Path> candidates = new ArrayList<>();
        String dotnetRoot = hostEnvironment == null ? null : hostEnvironment.get("DOTNET_ROOT");
        if (dotnetRoot != null && !dotnetRoot.isBlank()) {
            candidates.add(Path.of(dotnetRoot, executableName()));
        }
        // A project that pins its toolchain with a global.json installs it beside the project; that copy
        // is preferred over the machine-wide one, because it is the one the project asked for.
        if (projectRoot != null) {
            candidates.add(projectRoot.resolve(".dotnet").resolve(executableName()));
        }
        // The conventional install locations, checked before PATH so a project is verified with the
        // toolchain the developer actually uses rather than with whichever one a login shell exported.
        for (Path root : installRoots) {
            candidates.add(root.resolve(executableName()));
        }
        String home = hostEnvironment == null ? null : hostEnvironment.get("HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, ".dotnet", executableName()));
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
        return isWindows() ? "dotnet.exe" : "dotnet";
    }

    /**
     * Asks the .NET SDK for its version.
     *
     * @param context       run context, providing the sandboxed runner
     * @param dotnetCommand dotnet command or absolute path
     * @return what the SDK reported; a missing toolchain is a reported fact, not a failure
     */
    public static Probe probe(VerificationContext context, String dotnetCommand) {
        ProcessResult version = context.runner().run(ProcessSpec.builder(
                        List.of(dotnetCommand, "--version"), context.projectRoot())
                .environment(environment(context))
                .timeout(Duration.ofSeconds(120))
                .build());
        if (!version.started() || !version.success()) {
            return new Probe(null, version.describeOutcome());
        }
        Matcher matcher = VERSION.matcher(version.combinedOutput());
        return new Probe(matcher.find() ? matcher.group(1) : firstLine(version.combinedOutput()), null);
    }

    /**
     * Builds the environment for a {@code dotnet} command.
     *
     * <p>The sandbox replaces the child environment, so everything the SDK needs is stated explicitly.
     * The caches that a run may own - the CLI's home, NuGet's HTTP cache and NuGet's scratch folder - live
     * under {@code cacheRoot} (the run's artefact directory) rather than in the user's home. The NuGet
     * <em>package</em> folder is deliberately not redirected, see the class javadoc.
     *
     * <p>NuGet has no switch that forbids the network, the way Cargo's {@code --offline} or Go's
     * {@code GOPROXY=off} do, so {@code sandbox.allow_network: false} cannot be enforced here the way the
     * other adapters enforce it, and this method does not pretend otherwise. What it does do is remove the
     * requests that are not part of restoring a package: the certificate revocation check of package
     * signatures is set to its documented offline value, and NuGet's own vulnerability audit is disabled
     * on every command. A restore that still needs a package the machine has never downloaded will
     * therefore reach for the network even when the configuration says not to - which is why the report
     * records the sandbox and its network position, and why the build fails loudly rather than silently
     * when the package cannot be found.
     *
     * @param allowNetwork whether the run's sandbox configuration permits network access
     * @param cacheRoot    writable directory for the CLI home, the HTTP cache and NuGet's scratch folder
     * @return environment additions for a {@link ProcessSpec}
     */
    public static Map<String, String> environment(boolean allowNetwork, Path cacheRoot) {
        Map<String, String> environment = new LinkedHashMap<>();
        Path base = cacheRoot == null ? Path.of(".") : cacheRoot.resolve("dotnet");
        environment.put("DOTNET_CLI_HOME", base.resolve("cli-home").toString());
        environment.put("NUGET_HTTP_CACHE_PATH", base.resolve("nuget-http").toString());
        environment.put("NUGET_SCRATCH", base.resolve("nuget-scratch").toString());
        environment.put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        environment.put("DOTNET_NOLOGO", "1");
        environment.put("DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "1");
        environment.put("MSBUILDDISABLENODEREUSE", "1");
        // The diagnostic format is parsed, so the language the SDK prints it in is pinned rather than
        // inherited from the machine that happens to run the verification.
        environment.put("DOTNET_CLI_UI_LANGUAGE", "en");
        if (!allowNetwork) {
            environment.put("NUGET_CERT_REVOCATION_MODE", "offline");
        }
        return environment;
    }

    /** @return the environment a {@code dotnet} command should run with for this context. */
    public static Map<String, String> environment(VerificationContext context) {
        return environment(context.config().sandbox().allowNetwork(), context.artifacts().artifactsDirectory());
    }

    /**
     * @return the arguments every {@code dotnet} command in this adapter carries.
     *
     * <p>{@code -p:NuGetAudit=false} is part of the command rather than of the environment because it is
     * an MSBuild property, and stating it on the command line means it holds whatever the project file
     * says. See the class javadoc for why the audit is not run here.
     */
    public static List<String> commonArguments() {
        return List.of("--nologo", "-p:NuGetAudit=false");
    }

    /** @return the dotnet command recorded during inspection, or a PATH-resolved default. */
    public static String dotnet(ProjectInfo project, VerificationContext context) {
        String resolved = project.attributes().get(ATTR_DOTNET);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return resolve(context.projectRoot(), System.getenv());
    }

    /** @return the projects the build phase should compile for this run. */
    public static List<Path> buildTargets(VerificationContext context) {
        return CSharpProjectDetector.buildTargets(context.projectRoot());
    }

    /** @return {@code true} when the project has something the .NET SDK can build. */
    public static boolean hasProject(VerificationContext context) {
        return !buildTargets(context).isEmpty();
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
     * What the .NET SDK reported about itself.
     *
     * @param version       the SDK version, for example {@code 8.0.425}, or {@code null}
     * @param failureReason why the probe failed, or {@code null}
     */
    public record Probe(String version, String failureReason) {

        public boolean available() {
            return failureReason == null;
        }
    }
}

package dev.codewitness.swift;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the Swift toolchain and builds the commands every later phase drives.
 *
 * <p>Swift has one toolchain and one driver for everything this adapter does: {@code swift build}
 * compiles a package, {@code swift test} runs its XCTest suite, and {@code swift package describe}
 * reads its layout. The driver is a single executable, so - unlike the Java or Kotlin adapters - there
 * is nothing per-project to resolve here; the adapter resolves the machine's {@code swift} once and
 * records where it is and what version it reported.
 *
 * <p>Every invocation is given its own cache, configuration and security directories inside the run's
 * artefact store. That is not tidiness: SwiftPM's defaults live under the user's home
 * ({@code ~/Library/org.swift.swiftpm} on macOS, {@code ~/.cache/org.swift.swiftpm} on Linux), which
 * is outside the sandbox roots a run is allowed to write to, and a shared cache would let one run's
 * manifest compilation leak into the next. The two module cache variables are redirected for the same
 * reason, one level lower: the Swift driver hands them to Clang, which otherwise writes into a
 * per-user module cache.
 *
 * <p>{@code --disable-sandbox} is passed to every command. SwiftPM wraps manifest evaluation in
 * {@code sandbox-exec} on macOS, and a process that is already inside a boundary that forbids a nested
 * one - CodeWitness's own sandbox on some hosts - fails at manifest compilation before a single line of
 * the package is read. CodeWitness's declared boundary is its own sandbox, and
 * {@code sandbox.mode=docker} is the one that is a security boundary; SwiftPM's nested sandbox is a
 * second, weaker boundary that only makes the run fail. The limitation is stated in
 * {@code docs/security.md} rather than hidden.
 */
public final class SwiftToolchain {

    /** Attribute naming the {@code swift} executable that was resolved. */
    public static final String ATTR_SWIFT = "swift";

    /** Attribute naming the interpreter version the probe reported. */
    public static final String ATTR_SWIFT_VERSION = "swiftVersion";

    /** Attribute naming the toolchain's own name, as {@code swift --version} reports it. */
    public static final String ATTR_TOOLCHAIN = "toolchain";

    /** Attribute recording why the probe could not produce a usable toolchain. */
    public static final String ATTR_PROBE_FAILURE = "swiftProbeFailure";

    /**
     * Attribute naming the module a generated test must {@code import} and be reported under.
     *
     * <p>XCTest names a case {@code Module.ClassName}: the test target's module, a dot, and the class
     * that declares the case. The generator writes a class into the project's test target, so it needs
     * that module name to name the result the same way the runner will. The detector records it from the
     * test target's own directory.
     */
    public static final String ATTR_TEST_MODULE = "testModule";

    /**
     * Attribute naming the module a generated test must {@code @testable import}: the package's own
     * library (or executable) target, not the test target the class is written into.
     *
     * <p>The two are different facts and both are needed. XCTest reports a case under the <em>test</em>
     * target's module ({@link #ATTR_TEST_MODULE}), which is what the engine matches a result against,
     * while the class's own source has to import the code it exercises from the <em>library</em> target.
     * A generated class that imported its own test module would not compile at all.
     */
    public static final String ATTR_HOST_MODULE = "hostModule";

    /** {@code version} as {@code swift --version} prints it. */
    private static final Pattern VERSION = Pattern.compile("Swift version\\s+([0-9][^\\s(]*)");

    /** The first version component, for the coarse "is there a toolchain at all" message. */
    private static final Pattern TOOLCHAIN = Pattern.compile("Swift version\\s+([^\\r\\n]+)");

    private SwiftToolchain() {
    }

    /**
     * @param root project root the adapter will run in
     * @param environment the host environment, so an explicitly configured toolchain wins over PATH
     * @return the {@code swift} executable to use, or {@code null} when none can be found
     *
     * <p>{@code SWIFT_EXECUTABLE} is checked first so a run can be pointed at a toolchain the user chose
     * without changing the machine's PATH, exactly as the Rust adapter honours {@code CARGO}. The
     * conventional install locations come next, because a macOS host almost always has a Swift driver at
     * {@code /usr/bin/swift} even when it is not on the PATH of the process running CodeWitness; the PATH
     * lookup is last so that a caller's explicit configuration is never silently overridden.
     */
    public static String resolve(Path root, Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        String configured = env.get("SWIFT_EXECUTABLE");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured.strip());
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        String name = Platform.isWindows() ? "swift.exe" : "swift";
        if (root != null) {
            Path local = root.resolve(".swift").resolve(name);
            if (Files.isExecutable(local)) {
                return local.toAbsolutePath().normalize().toString();
            }
        }
        for (String directory : conventionalDirectories()) {
            Path candidate = Path.of(directory, name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return onPath(env.get("PATH"), name);
    }

    /**
     * @return directories a Swift toolchain is conventionally installed in.
     *
     * <p>An Xcode toolchain is the one that matters on macOS: {@code /usr/bin/swift} is a shim that only
     * works once a developer toolchain is selected, and the toolchain's own driver is the stable path.
     * The Linux entries cover the open-source tarballs, whose installer unpacks under
     * {@code /usr/share/swift} or {@code /usr/local/swift}.
     */
    private static List<String> conventionalDirectories() {
        List<String> directories = new ArrayList<>();
        if (Platform.isWindows()) {
            directories.add("C:\\Library\\Developer\\Toolchains\\unknown-Asserts-development.xctoolchain\\usr\\bin");
            return List.copyOf(directories);
        }
        directories.add("/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin");
        directories.add("/Library/Developer/Toolchains/swift-latest.xctoolchain/usr/bin");
        directories.add("/usr/local/swift/usr/bin");
        directories.add("/usr/share/swift/usr/bin");
        directories.add("/usr/bin");
        return List.copyOf(directories);
    }

    /** @return the executable under the first matching {@code PATH} element, or {@code null}. */
    private static String onPath(String path, String name) {
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String element : path.split(java.io.File.pathSeparator)) {
            if (element.isBlank()) {
                continue;
            }
            Path candidate = Path.of(element, name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    /**
     * Runs {@code swift --version} and records what it reported.
     *
     * <p>A machine without a Swift toolchain is not an error: the probe returns {@code available=false}
     * with a reason, and every later phase reports that reason rather than a score it did not observe.
     */
    public static Probe probe(VerificationContext context, String executable) {
        if (executable == null) {
            return new Probe(false, null, null,
                    "no swift executable was found on PATH, in SWIFT_EXECUTABLE, or in a conventional "
                            + "toolchain location");
        }
        ProcessResult result = context.runner().run(ProcessSpec.builder(List.of(executable, "--version"),
                        context.projectRoot())
                .environment(environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(64 * 1024)
                .build());
        if (!result.started()) {
            return new Probe(false, null, null, "swift --version could not be started: "
                    + result.failureReason());
        }
        String output = result.combinedOutput();
        if (!result.success()) {
            return new Probe(false, null, null, "swift --version exited with " + result.exitCode()
                    + ": " + dev.codewitness.support.Text.lastLines(output, 4));
        }
        Matcher version = VERSION.matcher(output);
        String versionText = version.find() ? version.group(1) : null;
        Matcher toolchain = TOOLCHAIN.matcher(output);
        String toolchainText = toolchain.find() ? toolchain.group(1).strip() : null;
        if (versionText == null) {
            return new Probe(false, null, null,
                    "swift --version did not report a version: "
                            + dev.codewitness.support.Text.lastLines(output, 4));
        }
        return new Probe(true, versionText, toolchainText, null);
    }

    /** What {@code swift --version} said. */
    public record Probe(boolean available, String version, String toolchain, String failureReason) {
    }

    /**
     * @param executable the resolved {@code swift} driver
     * @param context the run context
     * @param arguments the subcommand and its own options, after the shared ones
     * @return an argv-style command with SwiftPM's caches redirected inside the run
     */
    public static List<String> command(String executable, VerificationContext context, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        command.addAll(commonOptions(context));
        return List.copyOf(command);
    }

    /**
     * @return the options every SwiftPM invocation carries: the nested sandbox disabled and every
     *     SwiftPM-owned directory pointed inside the run's artefact store.
     */
    static List<String> commonOptions(VerificationContext context) {
        return List.of(
                "--disable-sandbox",
                "--cache-path", directory(context, "cache").toString(),
                "--config-path", directory(context, "config").toString(),
                "--security-path", directory(context, "security").toString());
    }

    /**
     * Prepares and returns the shared directories a run's SwiftPM commands write to.
     *
     * @return the cache directory, so the caller can assert it exists before a command runs
     */
    static Path prepareDirectories(VerificationContext context) {
        Path cache = directory(context, "cache");
        try {
            Files.createDirectories(cache);
            Files.createDirectories(directory(context, "config"));
            Files.createDirectories(directory(context, "security"));
            Files.createDirectories(Path.of(environment(context).get("CLANG_MODULE_CACHE_PATH")));
            Files.createDirectories(Path.of(environment(context).get("SWIFT_MODULECACHE_PATH")));
        } catch (java.io.IOException _) {
            // SwiftPM creates what it needs and reports its own failure; a run that cannot create one of
            // these directories will fail loudly at the first command rather than here.
        }
        return cache;
    }

    /** @return where SwiftPM's shared state for this run is kept. */
    static Path directory(VerificationContext context, String name) {
        return context.artifacts().artifactsDirectory().resolve("swift").resolve(name);
    }

    /**
     * @return the environment every Swift command runs with: the two module cache variables, both inside
     *     the run, because the Swift driver hands them to Clang and Clang's own default is per-user.
     */
    public static Map<String, String> environment(VerificationContext context) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("CLANG_MODULE_CACHE_PATH", directory(context, "clang-module-cache").toString());
        environment.put("SWIFT_MODULECACHE_PATH", directory(context, "swift-module-cache").toString());
        return Map.copyOf(environment);
    }

    /**
     * @return the package-relative path of the first test target's directory, as SwiftPM's implicit
     *     layout names it, or {@code null} when the package has no test target.
     *
     * <p>Read from the detector's report rather than assumed: a package may lay its targets out with
     * explicit {@code path:} arguments, and a generator that wrote into {@code Tests/} regardless would
     * produce a Swift file no test target compiles.
     */
    public static Path testRoot(VerificationContext context, String testModule) {
        if (testModule == null || testModule.isBlank()) {
            return null;
        }
        for (Path candidate : context.projectInfo().testSourceDirs()) {
            if (candidate.getFileName() != null && candidate.getFileName().toString().equals(testModule)) {
                return candidate;
            }
        }
        return context.projectInfo().testSourceDirs().isEmpty()
                ? null : context.projectInfo().testSourceDirs().getFirst();
    }
}

package dev.codewitness.ruby;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.Platform;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Locates the Ruby interpreter that runs a project, and describes what that interpreter can see.
 *
 * <p>The interpreter is resolved once, during inspection, and recorded in the project attributes so
 * that every phase drives the same one. Nothing here guesses that a tool is available: an interpreter or
 * gem is reported present only when it answered a probe, and a tool that did not answer is reported as a
 * reason it could not be used.
 *
 * <p>RSpec and SimpleCov are the things that make this adapter different from the Java and Kotlin ones,
 * and the difference is worth stating plainly: <strong>they are not part of the Ruby toolchain</strong>.
 * They are gems that only exist once the project's build has run {@code bundle install}. Inspection
 * therefore happens before they can exist, and an "RSpec was found" fact recorded at inspection time
 * would be false for every project that has not been built already. The probe still records what it saw
 * - it is a useful hint, and it is what an already-built project honestly reports - but the test runner
 * resolves RSpec again at test time, after the build that may have installed it.
 * {@link #rspec(ProjectInfo, VerificationContext)} says which order it uses.
 *
 * <p>Bundler is the exception: it is a tool of the machine in the ordinary case, not of the project, so
 * it is resolved the same way the interpreter is.
 *
 * <p>The probe deliberately does not {@code require} anything the project declares. Ruby resolves a gem
 * by executing it, so requiring RSpec or SimpleCov to ask whether they are installed would run a
 * dependency's code before the run has decided to - the exact thing the engine's isolation exists to
 * prevent. The probe asks the gem index what is installed instead.
 */
public final class RubyInterpreter {

    /** Project attributes set by this class. */
    public static final String ATTR_RUBY = "ruby";
    public static final String ATTR_VERSION = "rubyVersion";
    public static final String ATTR_BUNDLER = "bundler";
    public static final String ATTR_BUNDLER_VERSION = "bundlerVersion";
    public static final String ATTR_RSPEC = "rspec";
    public static final String ATTR_HAS_RSPEC = "hasRspec";
    public static final String ATTR_COVERAGE_DRIVER = "coverageDriver";

    /**
     * Attribute naming why the interpreter probe failed, set only when it did.
     *
     * <p>Its absence, not its value, is the fact that matters: a build that reads this attribute knows
     * the interpreter never answered, and can say so instead of reporting a failed build against the
     * project's code. The name is the one the Python and PHP adapters use for the same purpose.
     */
    public static final String ATTR_PROBE_FAILURE = "interpreterProbeFailure";

    /** Bundler's own version banner, for example {@code Bundler version 2.5.9}. */
    private static final Pattern BUNDLER_VERSION = Pattern.compile("Bundler version\\s+(\\S+)");

    /** A probe only asks the interpreter about itself; a minute is generous and still bounded. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(60);

    private RubyInterpreter() {
    }

    /**
     * Resolves an interpreter without running anything.
     *
     * @param projectRoot     project directory, used to find an interpreter the project ships itself
     * @param hostEnvironment environment to read {@code CODEWITNESS_RUBY} and {@code PATH} from
     * @return an absolute interpreter path when one can be found, otherwise a bare command name that the
     *     sandbox will resolve through {@code PATH}
     */
    public static String resolve(Path projectRoot, Map<String, String> hostEnvironment) {
        for (Path candidate : rubyCandidates(projectRoot, hostEnvironment)) {
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return rubyName();
    }

    private static List<Path> rubyCandidates(Path projectRoot, Map<String, String> hostEnvironment) {
        List<Path> candidates = new ArrayList<>();
        String explicit = hostEnvironment == null ? null : hostEnvironment.get("CODEWITNESS_RUBY");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Path.of(explicit));
        }
        if (projectRoot != null) {
            // A project that pins its own interpreter is tested with that interpreter, not with whatever
            // happens to be installed; the checked-in .tools directory is how the CodeWitness Ruby
            // fixtures do it. Ruby installs its executable under bin/, unlike PHP's flat layout.
            candidates.add(projectRoot.resolve(".tools").resolve("ruby").resolve("bin").resolve(rubyName()));
            candidates.add(projectRoot.resolve(".tools").resolve("ruby").resolve("bin").resolve("ruby.exe"));
        }
        String path = hostEnvironment == null ? null : hostEnvironment.get("PATH");
        if (path != null) {
            for (String element : path.split(File.pathSeparator)) {
                if (element.isBlank()) {
                    continue;
                }
                candidates.add(Path.of(element, rubyName()));
                candidates.add(Path.of(element, "ruby.exe"));
            }
        }
        return candidates;
    }

    /**
     * Builds the environment for a Bundler command.
     *
     * <p>The sandbox replaces the child environment, so this is the only channel through which Bundler
     * learns where it may write. Every Bundler directory is put inside the run's artefact store because a
     * global {@code ~/.bundle} is shared with every other project on the machine and would make one
     * run's downloads visible to the next - and because a verifier that writes into a user's gem home can
     * change what a later, unrelated build resolves. {@code BUNDLE_PATH} is the install directory,
     * {@code BUNDLE_APP_CONFIG}, {@code BUNDLE_USER_HOME}, {@code BUNDLE_USER_CACHE},
     * {@code BUNDLE_USER_CONFIG} and {@code BUNDLE_USER_PLUGIN} are the config, home, cache, config file
     * and plugin directories Bundler would otherwise take from the user's account.
     *
     * <p>{@code BUNDLE_SILENCE_ROOT_WARNING} is set because a run's workspace is often owned by root and
     * the warning is about the account, not about the project; {@code BUNDLE_DISABLE_SHARED_GEMS} stops
     * Bundler from reaching into the system gem home, so the dependency set a run is tested against is
     * exactly the one this project's lock file names.
     *
     * <p>Nothing else is added and nothing here depends on the project: a tree without a Gemfile never
     * reaches a Bundler command in the first place, so there is no second, Gemfile-specific environment
     * to build.
     *
     * @param info               project information; part of the fixed contract, unused here because this
     *                           adapter's Bundler configuration is about where Bundler may write rather
     *                           than about where the project keeps its files
     * @param projectRoot        project root; likewise part of the contract, unused for the same reason
     * @param artifactsDirectory the run's artefact directory, which is what the Bundler directories are
     *                           derived from
     * @return environment additions for a {@link ProcessSpec}
     */
    public static Map<String, String> environment(ProjectInfo info, Path projectRoot, Path artifactsDirectory) {
        Path rubyArtifacts = artifactsDirectory.resolve("ruby");
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("BUNDLE_PATH", rubyArtifacts.resolve("bundle").toString());
        environment.put("BUNDLE_APP_CONFIG", rubyArtifacts.resolve("bundle-config").toString());
        environment.put("BUNDLE_USER_HOME", rubyArtifacts.resolve("bundle-home").toString());
        environment.put("BUNDLE_USER_CACHE", rubyArtifacts.resolve("bundle-cache").toString());
        environment.put("BUNDLE_USER_CONFIG", rubyArtifacts.resolve("bundle-user-config").toString());
        environment.put("BUNDLE_USER_PLUGIN", rubyArtifacts.resolve("bundle-plugin").toString());
        environment.put("BUNDLE_SILENCE_ROOT_WARNING", "1");
        environment.put("BUNDLE_DISABLE_SHARED_GEMS", "1");
        return environment;
    }

    /**
     * Asks the interpreter for its version, the gems it can see and the RSpec it can find.
     *
     * <p>One {@code ruby -e} run answers everything the interpreter itself knows, and a second run asks
     * the resolved Bundler for its version when one could be resolved at all. Both answers are recorded,
     * including "nothing found": an unavailable tool defaults to unavailable, never to the optimistic
     * branch.
     *
     * @param context run context, providing the sandboxed runner and the run's artefact directory
     * @param ruby    interpreter command or absolute path
     * @return what was observed; every optional tool defaults to unavailable
     */
    public static Probe probe(VerificationContext context, String ruby) {
        Map<String, String> environment =
                environment(context.projectInfo(), context.projectRoot(), context.artifacts().artifactsDirectory());
        ProcessResult result = context.runner().run(ProcessSpec.builder(List.of(ruby, "-e", PROBE_SCRIPT), context.projectRoot())
                .environment(environment)
                .timeout(PROBE_TIMEOUT)
                .build());
        if (!result.started() || !result.success()) {
            return new Probe(null, null, null, false, null, result.describeOutcome());
        }
        Map<String, String> values = parseKeyValues(result.stdout());
        boolean simplecov = Boolean.parseBoolean(values.getOrDefault("simplecov", "false"));
        // SimpleCov is the only coverage library this adapter can honestly claim: Ruby ships no coverage
        // tool of its own, and every result set the coverage provider reads is SimpleCov's.
        String driver = simplecov ? "simplecov" : null;
        return new Probe(values.get("version"), bundlerVersion(context, ruby, environment),
                blankToNull(values.get("rspec")), driver != null, driver, null);
    }

    private static String bundlerVersion(VerificationContext context, String ruby, Map<String, String> environment) {
        List<String> command = bundler(context.projectRoot(), ruby, System.getenv("PATH"));
        if (command.isEmpty()) {
            return null;
        }
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(environment)
                .timeout(PROBE_TIMEOUT)
                .build());
        if (!result.started()) {
            return null;
        }
        Matcher matcher = BUNDLER_VERSION.matcher(result.combinedOutput());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> parseKeyValues(String output) {
        Map<String, String> values = new LinkedHashMap<>();
        if (output == null) {
            return values;
        }
        for (String line : output.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return values;
    }

    /** @return the interpreter a provider should run, never blank. */
    public static String ruby(ProjectInfo project, VerificationContext context) {
        String recorded = project.attributes().get(ATTR_RUBY);
        if (recorded != null && !recorded.isBlank()) {
            return recorded;
        }
        return resolve(context.projectRoot(), System.getenv());
    }

    /**
     * @return the argv that runs Bundler for this project, never empty.
     *
     * <p>A project may pin its own Bundler through {@code bin/}, which is why that binstub comes first:
     * it is the version the project's own layout decided on. The bundle tree the run's own
     * {@code bundle install} created comes next, then a Bundler anywhere on {@code PATH}. When none of
     * those exists the interpreter itself is asked for Bundler with {@code -S}, because a Ruby that ships
     * Bundler as a default gem has no standalone executable but can always run one.
     */
    public static List<String> bundler(ProjectInfo project, VerificationContext context) {
        return bundler(context.projectRoot(), ruby(project, context), System.getenv("PATH"));
    }

    static List<String> bundler(Path projectRoot, String rubyCommand, String pathVariable) {
        Path binstub = projectRoot.resolve("bin").resolve(bundlerName());
        if (Files.isRegularFile(binstub)) {
            return List.of(binstub.toString());
        }
        Path installed = vendorBundleExecutable(projectRoot, bundlerName());
        if (installed != null) {
            return List.of(installed.toString());
        }
        String onPath = findOnPath(bundlerName(), pathVariable, true);
        if (onPath != null) {
            return List.of(onPath);
        }
        return List.of(rubyCommand, "-S", "bundle");
    }

    /**
     * @return the argv that runs the project's RSpec, or an empty list when there is none.
     *
     * <p>Resolution runs at <em>test</em> time, not at inspection time, in this order:
     *
     * <ol>
     *   <li>{@code bin/rspec} below the project root. It is a Bundler binstub, so it runs the project's
     *       own locked RSpec version rather than whichever one happens to be installed;
     *   <li>{@code bundle exec rspec}, which is the only invocation guaranteed to see the gems this
     *       run's own {@code bundle install} installed. It is used whenever the project has a Gemfile,
     *       because that is what makes the project's bundle the bundle RSpec runs in;
     *   <li>an RSpec installed below {@code vendor/bundle}, or one on {@code PATH}, for a tree that has
     *       no Gemfile at all - an already-built project whose dependencies arrived some other way.
     * </ol>
     *
     * <p>When a Gemfile exists the Bundler answer is always available, because
     * {@link #bundler(Path, String, String)} falls back to the interpreter's own {@code -S bundle}; an
     * empty list therefore means "this tree has no Gemfile and no RSpec was found", and the test provider
     * reports that rather than running a command that cannot work.
     */
    public static List<String> rspec(ProjectInfo project, VerificationContext context) {
        return rspec(context.projectRoot(), ruby(project, context), System.getenv("PATH"));
    }

    static List<String> rspec(Path projectRoot, String rubyCommand, String pathVariable) {
        Path binstub = projectRoot.resolve("bin").resolve(rspecName());
        if (Files.isRegularFile(binstub)) {
            return List.of(binstub.toString());
        }
        if (Files.isRegularFile(projectRoot.resolve("Gemfile"))) {
            List<String> command = new ArrayList<>(bundler(projectRoot, rubyCommand, pathVariable));
            command.add("exec");
            command.add("rspec");
            return List.copyOf(command);
        }
        Path installed = vendorBundleExecutable(projectRoot, rspecName());
        if (installed != null) {
            return List.of(installed.toString());
        }
        String onPath = findOnPath(rspecName(), pathVariable, true);
        return onPath == null ? List.of() : List.of(onPath);
    }

    /**
     * @return an executable Bundler installed below {@code vendor/bundle/ruby/<abi>/bin}, or {@code null}.
     *
     * <p>Bundler nests its install tree one level deeper than Composer or npm does - the Ruby ABI version
     * names the directory between {@code ruby} and {@code bin} - so the path is found by listing rather
     * than by constructing it.
     */
    private static Path vendorBundleExecutable(Path projectRoot, String name) {
        Path ruby = projectRoot.resolve("vendor").resolve("bundle").resolve("ruby");
        if (!Files.isDirectory(ruby)) {
            return null;
        }
        try (Stream<Path> versions = Files.list(ruby)) {
            return versions.filter(Files::isDirectory)
                    .map(directory -> directory.resolve("bin").resolve(name))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException _) {
            return null;
        }
    }

    private static String findOnPath(String name, String pathVariable, boolean executable) {
        if (pathVariable == null) {
            return null;
        }
        for (String element : pathVariable.split(File.pathSeparator)) {
            if (element.isBlank()) {
                continue;
            }
            Path candidate = Path.of(element, name);
            if (executable ? Files.isExecutable(candidate) : Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private static String rubyName() {
        return Platform.isWindows() ? "ruby.exe" : "ruby";
    }

    private static String bundlerName() {
        return Platform.isWindows() ? "bundle.bat" : "bundle";
    }

    private static String rspecName() {
        return Platform.isWindows() ? "rspec.bat" : "rspec";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * What one interpreter reported about itself and its neighbours.
     *
     * @param version           the interpreter's own version such as {@code 3.3.6}, or {@code null}
     * @param bundlerVersion    the resolved Bundler's version, or {@code null} when none answered
     * @param rspec             the RSpec the probe could see, or {@code null}; a hint for the test phase,
     *                          never a gate, because RSpec is installed by the build
     * @param hasCoverageDriver whether the SimpleCov gem is installed
     * @param coverageDriver    {@code simplecov}, or {@code null} when SimpleCov is not installed
     * @param failureReason     why the probe failed, or {@code null}
     */
    public record Probe(
            String version,
            String bundlerVersion,
            String rspec,
            boolean hasCoverageDriver,
            String coverageDriver,
            String failureReason) {

        /** @return {@code true} when the interpreter answered the probe at all. */
        public boolean available() {
            return failureReason == null;
        }
    }

    /**
     * The single {@code ruby -e} program behind {@link #probe}.
     *
     * <p>It never requires a gem, because requiring one executes it; it asks the gem index what is
     * installed instead. It resolves RSpec the way the test provider does for an already-built project
     * - the project's own binstub, the bundle tree the build will have created, an installed
     * {@code rspec-core}, then {@code PATH} - and prints one {@code key=value} line per answer, so the
     * Java side parses facts instead of prose. It deliberately invents nothing: an answer it cannot
     * determine is printed empty, and the whole program is wrapped so that an interpreter which cannot
     * even load RubyGems still prints the four keys.
     *
     * <p>The {@code bundler} line is empty here. Bundler's version is not something the interpreter
     * knows; it takes a {@code bundle --version} run of its own, which the Java side performs so that a
     * machine without Bundler yields an empty answer instead of a failed probe.
     */
    static final String PROBE_SCRIPT = """
            version = ''
            simplecov = false
            rspec = ''
            begin
              version = RUBY_VERSION.to_s
              begin
                simplecov = !Gem::Specification.find_all_by_name('simplecov').empty?
              rescue Exception
                simplecov = false
              end
              found = ''
              candidate = File.join(Dir.pwd, 'bin', 'rspec')
              found = candidate if File.file?(candidate)
              if found.empty?
                vendored = Dir.glob(File.join(Dir.pwd, 'vendor', 'bundle', 'ruby', '*', 'bin', 'rspec')).sort
                found = vendored.first unless vendored.empty?
              end
              if found.empty?
                Gem::Specification.find_all_by_name('rspec-core').each do |spec|
                  [File.join(spec.full_gem_path, 'bin', 'rspec'),
                   File.join(spec.full_gem_path, 'exe', 'rspec')].each do |path|
                    found = path if found.empty? && File.file?(path)
                  end
                  break unless found.empty?
                end
              end
              if found.empty?
                (ENV['PATH'] || '').split(File::PATH_SEPARATOR).each do |directory|
                  next if directory.empty?
                  path = File.join(directory, 'rspec')
                  if File.file?(path)
                    found = path
                    break
                  end
                end
              end
              rspec = found.empty? ? '' : File.expand_path(found)
            rescue Exception
              version = ''
              simplecov = false
              rspec = ''
            end
            puts 'version=' + version.to_s
            puts 'simplecov=' + (simplecov ? 'true' : 'false')
            puts 'rspec=' + rspec.to_s
            puts 'bundler='
            """;
}

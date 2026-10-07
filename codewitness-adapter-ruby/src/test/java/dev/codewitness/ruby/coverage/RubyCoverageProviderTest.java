package dev.codewitness.ruby.coverage;

import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.ruby.RubyInterpreter;
import dev.codewitness.ruby.RubyTestContexts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage measurement for Ruby, read from SimpleCov's own result set.
 *
 * <p>Nothing here needs Ruby, Bundler or SimpleCov: the result set is parsed from a synthetic file, and
 * the run path is driven through a substituted process runner. What the tests are really about is the two
 * ways a coverage number can lie - a result set that names somebody else's file, and a run that never
 * happened - and both are asserted to end in "not measured" rather than in a percentage. The third lie
 * is the one this adapter has and PHP does not: the file lives at a fixed path inside the project, so a
 * result set from an earlier run would otherwise read as this run's numbers.
 */
class RubyCoverageProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the provider names itself and claims Ruby and nothing else")
    void supportsOnlyRuby() {
        RubyCoverageProvider provider = new RubyCoverageProvider();

        assertEquals("simplecov", provider.id());
        assertTrue(provider.supports(RubyTestContexts.projectInfo(tmp)));

        ProjectInfo javaProject = ProjectInfo.builder(ProjectRef.of(tmp))
                .language(LanguageId.JAVA)
                .build();
        assertFalse(provider.supports(javaProject),
                "the Java adapter has its own coverage provider; two would both run");
    }

    @Test
    @DisplayName("null entries in SimpleCov's line data are not executable lines and stay out of the denominator")
    void parsesSimpleCovResultSet() throws IOException {
        Path ledger = RubyTestContexts.write(tmp, "lib/ledger.rb", "class Ledger\nend\n");
        Path report = RubyTestContexts.write(tmp, "coverage/.resultset.json",
                resultSet(coverageOfFile(ledger, "[null,1,1,0,null]")));

        CoverageReport coverage = RubyCoverageProvider.parse(report, tmp, "test");

        assertTrue(coverage.measured(), coverage.note());
        assertEquals("simplecov", coverage.tool());
        assertEquals(2, coverage.lineCovered());
        assertEquals(3, coverage.lineTotal(),
                "the two null entries are lines no test could ever move, so they are not in the "
                        + "denominator that would make a healthy project read as badly covered");
        assertEquals(0, coverage.branchCovered());
        assertEquals(0, coverage.branchTotal(),
                "SimpleCov's default JSON carries line data only, and a zero total is how this model says "
                        + "'no branch data' rather than claiming the project has no branches");
        assertEquals(1, coverage.perClassLineCoverage().size(), coverage.perClassLineCoverage().toString());
        assertEquals(200.0 / 3, coverage.perClassLineCoverage().get("lib/ledger.rb"), 0.001,
                "the key is the path relative to the project root, which is what a reader can open");
        assertEquals(List.of(report), coverage.reports());
    }

    @Test
    @DisplayName("a file under vendor/, outside the project, or already deleted is not the project's code")
    void countsOnlyTheProjectsOwnFiles() throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path ledger = RubyTestContexts.write(project, "lib/ledger.rb", "class Ledger\nend\n");
        Path dependency = RubyTestContexts.write(project, "vendor/acme/lib/thing.rb", "class Thing\nend\n");
        Path outside = RubyTestContexts.write(tmp, "elsewhere/thing.rb", "class Thing\nend\n");
        Path deleted = project.resolve("lib/gone.rb");
        Path report = RubyTestContexts.write(tmp, "coverage/.resultset.json", resultSet(
                coverageOfFile(ledger, "[1]"),
                coverageOfFile(dependency, "[1]"),
                coverageOfFile(outside, "[1]"),
                coverageOfFile(deleted, "[1]")));

        CoverageReport coverage = RubyCoverageProvider.parse(report, project, "test");

        assertTrue(coverage.measured(), coverage.note());
        assertEquals(1, coverage.lineCovered(),
                "a dependency's covered lines say nothing about the code under verification");
        assertEquals(1, coverage.lineTotal());
        assertEquals(1, coverage.perClassLineCoverage().size(), coverage.perClassLineCoverage().toString());
        assertTrue(coverage.perClassLineCoverage().containsKey("lib/ledger.rb"),
                coverage.perClassLineCoverage().toString());
        assertFalse(coverage.perClassLineCoverage().keySet().stream()
                        .anyMatch(name -> name.contains("vendor") || name.contains("thing")
                                || name.contains("Elsewhere")),
                "neither a vendor file, a file outside the project, nor a deleted one may appear: "
                        + coverage.perClassLineCoverage());
    }

    @Test
    @DisplayName("a result set that names no file of this project is not measured, never zero")
    void aResultSetWithoutAProjectFileIsNotMeasured() throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path dependency = RubyTestContexts.write(project, "vendor/acme/lib/thing.rb", "class Thing\nend\n");
        Path report = RubyTestContexts.write(tmp, "coverage/.resultset.json",
                resultSet(coverageOfFile(dependency, "[1,0]")));

        CoverageReport coverage = RubyCoverageProvider.parse(report, project, "test");

        assertFalse(coverage.measured(),
                "a percentage over vendor code is not a measurement of this project");
        assertTrue(coverage.note().contains("no file of this project"), coverage.note());
        assertEquals(0, coverage.lineCovered(), "not measured carries no counts at all");
        assertEquals(0, coverage.lineTotal(), "which is not the same thing as a measured zero");
    }

    @Test
    @DisplayName("a result set that could not be read, or carries no coverage section, is not measured")
    void unreadableResultSetsAreNotMeasured() throws IOException {
        CoverageReport missing = RubyCoverageProvider.parse(
                Path.of("/does/not/exist/.resultset.json"), tmp, "test");
        CoverageReport malformed = RubyCoverageProvider.parse(
                RubyTestContexts.write(tmp, "broken.json", "{\"RSpec\":{\"coverage\":{"), tmp, "test");
        CoverageReport noCoverage = RubyCoverageProvider.parse(
                RubyTestContexts.write(tmp, "empty-resultset.json", "{\"RSpec\":{\"timestamp\":1}}"), tmp, "test");
        CoverageReport notAnObject = RubyCoverageProvider.parse(
                RubyTestContexts.write(tmp, "array-resultset.json", "[1,2]"), tmp, "test");

        assertFalse(missing.measured());
        assertTrue(missing.note().contains("could not be read"), missing.note());
        assertFalse(malformed.measured());
        assertTrue(malformed.note().contains("could not be read"), malformed.note());
        assertFalse(noCoverage.measured(),
                "a result set with no coverage section measured nothing about the code under verification");
        assertTrue(noCoverage.note().contains("carries no coverage section"), noCoverage.note());
        assertFalse(notAnObject.measured());
        assertTrue(notAnObject.note().contains("carries no coverage section"), notAnObject.note());
    }

    @Test
    @DisplayName("RSpec's own result set is preferred, a differently named one is the fallback, and a merged one is followed")
    void selectsTheResultSetToRead() throws IOException {
        assertNull(RubyCoverageProvider.coverageOf(null));
        assertNull(RubyCoverageProvider.coverageOf(MAPPER.readTree("[1,2]")),
                "a result set file is an object, so anything else has no coverage to read");

        JsonNode preferred = RubyCoverageProvider.coverageOf(MAPPER.readTree(
                "{\"SomeOther\":{\"coverage\":{\"/a.rb\":{}}},\"RSpec\":{\"coverage\":{\"/b.rb\":{}}}}"));
        assertNotNull(preferred);
        assertTrue(preferred.has("/b.rb"),
                "RSpec's own set is the one that describes the suite this run executed");

        JsonNode fallback = RubyCoverageProvider.coverageOf(MAPPER.readTree(
                "{\"Minitest\":{\"coverage\":{\"/c.rb\":{}}}}"));
        assertNotNull(fallback);
        assertTrue(fallback.has("/c.rb"),
                "a number from a differently named result set is still this project's own code");

        JsonNode merged = RubyCoverageProvider.coverageOf(MAPPER.readTree(
                "{\"RSpec\":{\"all\":{\"coverage\":{\"/d.rb\":{}}}}}"));
        assertNotNull(merged);
        assertTrue(merged.has("/d.rb"), "SimpleCov can nest a merged result set under an all key");
    }

    @Test
    @DisplayName("the provider reads SimpleCov's fixed result set location below the project root")
    void reportPathIsSimpleCovsFixedLocation() {
        VerificationContext context = context(Map.of(), refusingToRun());

        assertEquals(tmp.resolve("coverage").resolve(".resultset.json"),
                RubyCoverageProvider.reportPath(context),
                "SimpleCov writes there itself, which is why a stale file has to be deleted before a run");
    }

    @Test
    @DisplayName("the coverage command runs the resolved RSpec over the detected test roots")
    void coverageCommandSelectsTheDetectedTestRoots() throws IOException {
        RubyTestContexts.write(tmp, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(tmp, "spec/ledger_spec.rb", "# the suite under test\n");
        RubyTestContexts.write(tmp, "bin/rspec", "#!/usr/bin/env ruby\n");
        VerificationContext context = context(Map.of(), refusingToRun());

        List<String> command = RubyCoverageProvider.coverageCommand(context);

        assertTrue(command.stream().anyMatch(part -> part.endsWith("bin/rspec")),
                "the project's own RSpec binstub runs the version its lock file pins: " + command);
        assertTrue(command.contains(tmp.resolve("spec").toString()),
                "the suite is selected by the detected test root: " + command);

        RubyTestContexts.write(tmp, ".rspec", "--require spec_helper\n");
        List<String> withRspecFile = RubyCoverageProvider.coverageCommand(context);

        assertFalse(withRspecFile.contains(tmp.resolve("spec").toString()),
                "a project's own .rspec already says which files to run, and adding the root again would "
                        + "override it: " + withRspecFile);
    }

    @Test
    @DisplayName("the run asks for coverage through the opt-in variables and parses the result set the suite writes")
    void measureRunsTheSuiteUnderSimpleCov() throws IOException {
        Path ledger = RubyTestContexts.write(tmp, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(tmp, "spec/ledger_spec.rb", "# the suite under test\n");
        RubyTestContexts.write(tmp, "bin/rspec", "#!/usr/bin/env ruby\n");
        String resultSet = resultSet(coverageOfFile(ledger, "[null,1,1,0,null]"));
        List<ProcessSpec> specs = new ArrayList<>();
        VerificationContext context = context(Map.of(), sandboxWritingResultSet(resultSet, specs));

        CoverageReport coverage = new RubyCoverageProvider().measure(context);

        assertTrue(coverage.measured(), coverage.note());
        assertEquals(2, coverage.lineCovered());
        assertEquals(3, coverage.lineTotal());
        assertTrue(coverage.note().contains("branch coverage is not measured"),
                "the note must state that SimpleCov's JSON carries no branch data: " + coverage.note());

        assertEquals(1, specs.size(), "a measurement is one run");
        ProcessSpec spec = specs.getFirst();
        assertTrue(spec.command().stream().anyMatch(part -> part.endsWith("bin/rspec")),
                "the command must run the resolved RSpec: " + spec.command());
        assertEquals("true", spec.environment().get("COVERAGE"),
                "the sandbox replaces the child environment, so the opt-in flags are the only channel "
                        + "through which the project learns a coverage run is wanted: " + spec.environment());
        assertEquals("true", spec.environment().get("SIMPLECOV"),
                "both spellings are set, because projects differ on which one gates " + "'require \"simplecov\"': "
                        + spec.environment());
    }

    @Test
    @DisplayName("a stale result set is deleted before the run, so a run that writes nothing is never read as these numbers")
    void aStaleResultSetIsNotRead() throws IOException {
        Path ledger = RubyTestContexts.write(tmp, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(tmp, "spec/ledger_spec.rb", "# the suite under test\n");
        RubyTestContexts.write(tmp, "bin/rspec", "#!/usr/bin/env ruby\n");
        Path stale = RubyTestContexts.write(tmp, "coverage/.resultset.json",
                resultSet(coverageOfFile(ledger, "[1,1,1]")));
        VerificationContext context = context(Map.of(), sandboxWritingNothing());
        assertTrue(Files.isRegularFile(stale), "the test needs a stale result set to begin with");

        CoverageReport coverage = new RubyCoverageProvider().measure(context);

        assertFalse(coverage.measured(), "a run that measured nothing must not wear the previous run's score");
        assertTrue(coverage.note().contains("wrote no coverage/.resultset.json"), coverage.note());
        assertFalse(Files.exists(stale),
                "the stale file must be gone: SimpleCov's location is fixed, so leaving it would let a "
                        + "suite that wrote nothing be read as a suite that produced those numbers");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @return a context whose project declares an interpreter that does not exist, so that RSpec
     *     resolution cannot depend on what the machine running the tests happens to have installed
     */
    private VerificationContext context(Map<String, String> attributes, ExecutionSandbox sandbox) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY);
        merged.putAll(attributes);
        return RubyTestContexts.context(
                RubyTestContexts.withAttributes(tmp, merged), tmp.resolve("run"), List.of(), sandbox);
    }

    /** Builds a SimpleCov result set around the given per-file coverage entries. */
    private static String resultSet(String... files) {
        return "{\"RSpec\":{\"coverage\":{" + String.join(",", files) + "},\"timestamp\":1700000000}}";
    }

    /** One {@code "path": {"lines": [...]}} entry of a SimpleCov coverage object. */
    private static String coverageOfFile(Path file, String lines) {
        return "\"" + file.toString().replace("\\", "\\\\") + "\":{\"lines\":" + lines + "}";
    }

    /** A sandbox whose runner fails the test if anything is executed. */
    private static ExecutionSandbox refusingToRun() {
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "test-sandbox";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: no process may run on this path";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    throw new AssertionError("no process may run: " + spec.command());
                };
            }
        };
    }

    /**
     * @return a sandbox whose runner records its argv and writes the SimpleCov result set where the
     *     provider looked for it
     *
     * <p>The result set is written after the provider's own delete, which is exactly the ordering a real
     * run has: the suite is what writes the file, not the provider.
     */
    private ExecutionSandbox sandboxWritingResultSet(String resultSet, List<ProcessSpec> specs) {
        Path report = tmp.resolve("coverage").resolve(".resultset.json");
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "test-sandbox";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: writes the result set the provider expects without starting RSpec";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    specs.add(spec);
                    try {
                        Files.createDirectories(report.getParent());
                        Files.writeString(report, resultSet, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return successful(spec);
                };
            }
        };
    }

    /** @return a sandbox whose runner reports a clean, empty run and writes nothing at all. */
    private static ExecutionSandbox sandboxWritingNothing() {
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "test-sandbox";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: a suite that ran and wrote no result set";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return RubyCoverageProviderTest::successful;
            }
        };
    }

    private static ProcessResult successful(ProcessSpec spec) {
        return new ProcessResult(spec.command(), spec.workingDirectory(), true, 0, "", "",
                Duration.ZERO, false, null);
    }
}

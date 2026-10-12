package dev.codewitness.sql;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.sql.analysis.SqlHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The SQL language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for SQL. The individual tools - the schema
 * loader that is the language's first failure, the test-script runner, the rule tables - are separate
 * providers registered in {@link SqlAdapterBundle}; this class wires detection and delegates.
 *
 * <p>Nothing is installed: SQL is executed through the JDBC driver the build already carries, so a
 * schema and its test scripts need no server, no container and no network. The adapter declares no
 * coverage capability, because statement coverage over a schema is not a meaningful measurement, and no
 * dependency capability, because SQL has no package manager and no advisory ecosystem: the report then
 * says coverage was not measured and dependencies were not analysed, which is the truth rather than a
 * zero this adapter cannot support.
 */
public final class SqlLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "sql";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return SqlProjectDetector.looksLikeSqlProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = SqlProjectDetector.inspect(workspaceRef);
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(detected.language())
                .buildSystem(detected.buildSystem())
                .buildRoot(detected.buildRoot())
                .sourceExtensions(detected.sourceExtensions())
                .attributes(detected.attributes())
                .modules(detected.modules());
        detected.buildWrapper().ifPresent(builder::buildWrapper);
        detected.detectedDescriptors().forEach(builder::addDetectedDescriptor);
        detected.testFrameworks().forEach(builder::addTestFramework);
        context.emit("inspect", detected.attributes().get(SqlProjectDetector.ATTR_SCHEMA_FILES).isBlank()
                ? "no schema script was found; only test scripts are present"
                : "schema and migration scripts detected");
        return builder.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return new SqlBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new SqlTestProvider().runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new SqlHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        // No COVERAGE: statement coverage over a schema is not a measurement this adapter can make
        // meaningfully, and reporting a number anyway would be worse than reporting none. No
        // DEPENDENCY_SCAN: SQL has no package manager and no advisory ecosystem to consult.
        // GENERATED_TESTS is what makes a requirement verifiable rather than only refutable: the
        // schema-derived constraint probes are executed, and a constraint that does not fire fails.
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.SECURITY_SCAN, Capability.GENERATED_TESTS, Capability.PROPERTY_TESTING);
    }
}

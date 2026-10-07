package dev.codewitness.typescript.coverage;

import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.coverage.NodeCoverageProfile;
import dev.codewitness.support.coverage.NodeCoverageProvider;
import dev.codewitness.typescript.NodeRuntime;
import dev.codewitness.typescript.TypeScriptTestProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Measures TypeScript coverage through the V8 coverage the runtime already produces.
 *
 * <p>The measurement is the same job for JavaScript and TypeScript, so it lives once in
 * {@link NodeCoverageProvider}. This class exists to say where TypeScript's tests are, where the
 * adapter wants its LCOV report, and how this adapter resolves Node.
 */
public final class TypeScriptCoverageProvider implements VerificationTools.CoverageProvider {

    private final NodeCoverageProvider delegate = new NodeCoverageProvider(new Profile());

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        return delegate.measure(context);
    }

    /** @see NodeCoverageProvider#parse(Path, String) */
    public static CoverageReport parse(Path report, String note) {
        return NodeCoverageProvider.parse(report, note);
    }

    /** @see NodeCoverageProvider#parse(String, String, List) */
    public static CoverageReport parse(String text, String note, List<Path> reports) {
        return NodeCoverageProvider.parse(text, note, reports);
    }

    /** How this adapter differs from the other Node language. */
    private static final class Profile implements NodeCoverageProfile {

        @Override
        public LanguageId languageId() {
            return LanguageId.TYPESCRIPT;
        }

        @Override
        public List<Path> testFiles(VerificationContext context) {
            return TypeScriptTestProvider.testFiles(context);
        }

        @Override
        public Path prepareReport(VerificationContext context) {
            return TypeScriptTestProvider.prepareReport(context, "typescript/coverage/lcov.info");
        }

        @Override
        public String nodeExecutable(ProjectInfo project, VerificationContext context) {
            return NodeRuntime.node(project, context);
        }

        @Override
        public Map<String, String> nodeEnvironment(VerificationContext context) {
            return NodeRuntime.environment(context);
        }
    }
}

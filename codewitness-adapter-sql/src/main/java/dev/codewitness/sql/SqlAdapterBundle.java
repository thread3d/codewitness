package dev.codewitness.sql;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.sql.analysis.SqlHeuristicAnalyzer;
import dev.codewitness.sql.analysis.SqlSecurityAnalyzer;

/**
 * Wires the SQL adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete SQL tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so swapping the schema loader for one that
 * targets another database engine, or adding a mutation tester beside the rule tables, is a change to
 * this class and nothing else.
 *
 * <p>There is no coverage provider and no dependency analyzer, and that is deliberate rather than an
 * omission: statement coverage over a schema is not a meaningful measurement, and SQL has no package
 * manager or advisory ecosystem. The engine reports both as not measured/not analysed, which is the
 * truth.
 */
public final class SqlAdapterBundle {

    private SqlAdapterBundle() {
    }

    /** Registers the SQL language adapter and every SQL verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new SqlLanguageAdapter());

        providers.register(new SqlBuildProvider());
        providers.register(new SqlTestProvider());
        // The schema-derived probes are the experiment that can verify a SQL requirement: a declared
        // constraint is tested by the row that violates it, and a constraint that does not fire is the
        // defect.
        providers.register(new SqlTestGenerator());
        providers.register(new SqlGeneratedTestExecutor());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new SqlHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new SqlSecurityAnalyzer());
        }
    }
}

package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises {@code SQL} projects.
 *
 * <p>Reported as present and not verified when nothing claims them. It declares {@code sql} as the
 * adapter language that covers it, so a tree the SQL adapter verified is not described a second time as
 * present and unverifiable - and a tree with SQL files that the adapter does not claim, or a classpath
 * without the adapter, is still recognised and reported rather than passed over.
 *
 * <p>It scans deeper than the other descriptors - six levels instead of three - because SQL has no
 * conventional file at the project root to announce it. The files are normally under a tool's own tree,
 * such as {@code src/main/resources/db/migration} for Flyway, {@code db/migrate} for Rails, or
 * {@code migrations} for a hand-rolled runner, and at the default depth the walk stops one directory short
 * of the only place the language lives. The root descriptors are still worth listing, because a migration
 * tool's configuration file names the language even when its migration directory sits deeper than any scan
 * would reach.
 */
public final class SqlLanguageDetector extends DescriptorLanguageDetector {

    /**
     * How deep to look for a {@code .sql} file: deep enough for a migration tree inside a build's resource
     * directory, not so deep that the scan becomes a search of the whole repository. Vendored and
     * build-output directories are skipped at every level regardless.
     */
    private static final int MAX_DEPTH = 6;

    public SqlLanguageDetector() {
        super("sql", "SQL",
                List.of("sqitch.conf", "flyway.conf", "liquibase.properties", "atlas.hcl", "dbt_project.yml"),
                List.of(".sql", ".ddl"),
                MAX_DEPTH);
    }

    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("sql");
    }
}


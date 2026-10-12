package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code SQL} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing SQL does not have every verdict computed as though that code were absent. That
 * matters more for SQL than for most languages on this path: schema, migrations and views are code with
 * constraints and queries in them, they are frequently written by the same process that wrote the
 * application, and a report that is silent about them reads as though the database layer had been checked.
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
}

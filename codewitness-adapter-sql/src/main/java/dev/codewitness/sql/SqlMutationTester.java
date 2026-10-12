package dev.codewitness.sql;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.database.SqlDatabase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mutation testing for a SQL schema, judged by the project's own test scripts.
 *
 * <p>A surviving mutant says the schema's rules are not all constrained by what the project checks: a
 * boundary that no script exercises, or a constraint whose removal nothing notices. It is not proof that
 * a requirement is violated - it is proof that nobody checked - so it is reported prominently and never
 * changes a verdict by itself.
 *
 * <p>Each mutant is written to the run's artefact directory and loaded into a fresh database; every test
 * script then runs against its <em>own</em> fresh database loaded from that mutant, which is the same
 * guarantee the test runner gives each test. A mutant whose schema does not load is non-viable and is
 * excluded from the score rather than counted as killed, so "the database refused the schema" and "the
 * tests detected the change" are never confused. The run is bounded by a mutant cap and the configured
 * mutation budget.
 */
public final class SqlMutationTester implements VerificationTools.MutationTester {

    /** Upper bound on mutants per run, independent of the wall-clock budget. */
    private static final int MAX_MUTANTS = 12;

    /** The tool id written on a measured report. */
    static final String REPORT_TOOL = "sql-schema";

    @Override
    public String id() {
        return "sql-schema-mutation";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SQL);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        List<Path> schema = SqlProjectDetector.schemaFiles(context.projectRoot());
        List<Path> tests = SqlProjectDetector.testFiles(context.projectRoot());
        if (schema.isEmpty()) {
            return MutationReport.notMeasured(id(), "no schema or migration script was found to mutate");
        }
        if (tests.isEmpty()) {
            return MutationReport.notMeasured(id(),
                    "no SQL test script was found, so a mutant could not be killed by anything");
        }
        context.emit("mutation", "mutating " + schema.size() + " schema file(s) with the built-in schema mutator");

        Duration budget = context.config().mutation().timeout();
        Instant deadline = Instant.now().plus(budget);
        int generated = 0;
        int killed = 0;
        int survived = 0;
        int timedOut = 0;
        int nonViable = 0;
        List<SurvivingMutant> survivors = new ArrayList<>();
        List<Path> reports = new ArrayList<>();

        for (Path file : schema) {
            if (generated >= MAX_MUTANTS || Instant.now().isAfter(deadline)) {
                break;
            }
            String original = SqlProjectDetector.readString(file);
            if (original.isBlank()) {
                continue;
            }
            for (SqlSchemaMutator.Site site : SqlSchemaMutator.sites(original)) {
                if (generated >= MAX_MUTANTS || Instant.now().isAfter(deadline)) {
                    break;
                }
                generated++;
                String mutated = original.substring(0, site.start()) + site.replacement()
                        + original.substring(site.end());
                Status status = judge(context, site, mutated, tests, deadline, generated, reports);
                switch (status) {
                    case KILLED -> killed++;
                    case TIMED_OUT -> timedOut++;
                    case NON_VIABLE -> nonViable++;
                    case SURVIVED -> {
                        survived++;
                        String relative = relative(context.projectRoot(), file);
                        survivors.add(new SurvivingMutant(
                                String.format(Locale.ROOT, "MUT-%03d", generated),
                                MutantStatus.SURVIVED, site.mutator(), relative, null, site.line(),
                                "the test scripts did not detect " + site.description() + " at " + relative
                                        + ":" + site.line(),
                                null));
                    }
                }
            }
        }

        if (generated == 0) {
            return MutationReport.notMeasured(id(), "no mutatable CHECK constraint was found in the selected schema");
        }
        String note = "schema-level mutation over " + schema.size() + " schema file(s); " + nonViable
                + " non-viable mutant(s) excluded. The operator set is deliberately small (a comparison "
                + "boundary inside a CHECK, and a CHECK replaced by CHECK (1) which accepts every row); "
                + "NOT NULL, UNIQUE and key constraints are not mutated.";
        return new MutationReport(true, REPORT_TOOL, note, generated, killed, survived, 0, timedOut, survivors,
                reports);
    }

    /** What a mutant's run amounted to. */
    private enum Status {
        KILLED,
        SURVIVED,
        TIMED_OUT,
        NON_VIABLE
    }

    /**
     * Runs every test script against a fresh database loaded from the mutant.
     *
     * @return {@code KILLED} when some script failed, {@code SURVIVED} when none did, and the two
     *     infrastructure outcomes otherwise
     */
    private Status judge(
            VerificationContext context,
            SqlSchemaMutator.Site site,
            String mutated,
            List<Path> tests,
            Instant deadline,
            int index,
            List<Path> reports) {
        Path directory = context.artifacts().artifactsDirectory().resolve("sql").resolve("mutants");
        Path mutantSchema = directory.resolve(String.format(Locale.ROOT, "%03d-%s.sql", index,
                site.mutator().replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT)));
        write(mutantSchema, mutated);
        reports.add(mutantSchema);
        for (Path test : tests) {
            if (Instant.now().isAfter(deadline)) {
                return Status.TIMED_OUT;
            }
            // A fresh database per test, exactly as the test runner does it: one script's rows must not
            // decide another's result, or a mutant could be killed by an accident of ordering.
            Path database = directory.resolve(String.format(Locale.ROOT, "%03d-%s.db", index,
                    SqlTestProvider.safeName(test.getFileName().toString())));
            SqlDatabase.Load load = SqlDatabase.load(database, List.of(mutantSchema));
            if (!load.loaded()) {
                return Status.NON_VIABLE;
            }
            try (Connection connection = SqlTestProvider.open(database)) {
                SqlScriptRunner.Outcome outcome =
                        SqlScriptRunner.run(connection, SqlProjectDetector.readString(test));
                if (outcome.outcome().isFailure()) {
                    return Status.KILLED;
                }
            } catch (SQLException _) {
                return Status.NON_VIABLE;
            }
        }
        return Status.SURVIVED;
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the mutant schema " + file, e);
        }
    }

    private static String relative(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString() : file.toString();
    }
}

package dev.codewitness.sql;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the constraints a schema declares into executable probes.
 *
 * <p>This is what lets a SQL requirement be <em>verified</em> rather than only refuted. A SQL tree
 * carries its own specification in its DDL - {@code NOT NULL}, {@code PRIMARY KEY}, {@code UNIQUE},
 * {@code CHECK} and {@code FOREIGN KEY} - and each one makes a claim that can be tested: a row that
 * violates it must be rejected. The generated test is an ordinary SQL script, the same shape a
 * hand-written test has, so a reader can run it by hand and the counterexample names the statement.
 *
 * <p>Probes are chosen for the obligation at hand rather than dumped wholesale: a {@code REJECTION}
 * obligation is tested against every constraint, a {@code NULL_HANDLING} one against the {@code NOT
 * NULL} constraints, and a boundary obligation against the {@code CHECK} constraints that state a bound.
 * An obligation no constraint bears on produces no generated test, and the strategy then reports it as
 * unverified instead of scoring an unrelated probe as evidence for it.
 */
public final class SqlTestGenerator implements VerificationTools.TestGenerator {

    /** Generator id, recorded in evidence so a generated script can be traced to it. */
    public static final String ID = "sql-constraint-probes";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SQL);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        SqlSchemaModel model = SqlSchemaModel.of(context.projectRoot());
        if (model.isEmpty()) {
            context.emit("generate", "no CREATE TABLE statement was found, so no constraint probe exists");
            return List.of();
        }
        List<GeneratedTest> tests = new ArrayList<>();
        int index = 0;
        for (VerificationObligation obligation : obligations) {
            List<SqlConstraintProbe.Probe> probes = probesFor(model, requirement, obligation);
            if (probes.isEmpty()) {
                context.emit("generate", "no schema constraint bears on " + obligation.id()
                        + " (" + obligation.kind() + "), so nothing was generated for it");
                continue;
            }
            index++;
            String className = "cw_" + sanitize(requirement.id()) + "_" + index;
            tests.add(new GeneratedTest(
                    className,
                    render(requirement, obligation, probes),
                    null,
                    List.of(requirement.id()),
                    List.of(obligation.id()),
                    probes.size() + " schema constraint probe(s) for " + obligation.id()
                            + ": " + obligation.description(),
                    null,
                    null,
                    // Every probe asserts: a violating statement that is accepted fails the test, and the
                    // setup statements that must succeed fail it too. A test with no probe is not generated
                    // at all, so this generator never produces a script that asserts nothing.
                    true));
        }
        context.emit("generate", tests.size() + " constraint probe script(s) for " + requirement.id());
        return List.copyOf(tests);
    }

    /**
     * @return the probes that bear on an obligation <em>and</em> on this requirement
     */
    private static List<SqlConstraintProbe.Probe> probesFor(
            SqlSchemaModel model, Requirement requirement, VerificationObligation obligation) {
        // A bound the requirement states is probed against that bound, not against whatever the schema
        // declares: probing the schema's own check could only ever confirm the schema. This is the path
        // that finds a constraint which is missing, or weaker than the requirement.
        Optional<SqlConstraintProbe.Probe> stated = statedBoundProbe(model, requirement, obligation);
        if (stated.isPresent()) {
            return List.of(stated.get());
        }
        List<SqlSchemaModel.Constraint> candidates = candidatesFor(model, obligation.kind());
        return relevant(model, requirement, candidates).stream()
                .map(constraint -> SqlConstraintProbe.forConstraint(model, constraint))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * @return a probe for a numeric bound the requirement states, when one is derivable and exactly one
     *     schema column is named; empty otherwise, so a general obligation falls back to the schema's own
     *     constraints rather than guessing which column a bound belongs to
     */
    private static Optional<SqlConstraintProbe.Probe> statedBoundProbe(
            SqlSchemaModel model, Requirement requirement, VerificationObligation obligation) {
        if (obligation.kind() != ObligationKind.UPPER_BOUND
                && obligation.kind() != ObligationKind.LOWER_BOUND
                && obligation.kind() != ObligationKind.BOUNDARY_VALUE) {
            return Optional.empty();
        }
        Optional<BigDecimal> bound = decimal(obligation.parameters().get("value"));
        if (bound.isEmpty()) {
            return Optional.empty();
        }
        boolean upper = !"lower".equalsIgnoreCase(String.valueOf(obligation.parameters().get("direction")));
        Set<String> mentioned = mentionedColumns(model, requirement);
        if (mentioned.size() != 1) {
            return Optional.empty();
        }
        String column = mentioned.iterator().next();
        for (SqlSchemaModel.Table table : model.tables()) {
            if (table.column(column).isPresent()) {
                return SqlConstraintProbe.boundProbe(model, table.name(), column, bound.get(), upper);
            }
        }
        return Optional.empty();
    }

    /** @return the bound an obligation carries as a number, whatever shape the planner recorded it in. */
    private static Optional<BigDecimal> decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return Optional.of(decimal);
        }
        if (value instanceof Number number) {
            return Optional.of(new BigDecimal(number.toString()));
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Optional.of(new BigDecimal(text.trim().replace(",", "")));
            } catch (NumberFormatException _) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * @return the constraints an obligation of this kind can be tested by.
     *
     * <p>The mapping is deliberately narrow. A {@code REJECTION} requirement is about a rule that must
     * refuse a value, which in a schema is a {@code CHECK}; testing it against the primary key or a
     * foreign key would let an unrelated structural constraint discharge it - a requirement about
     * negative amounts would be "verified" by a schema that only declares a key. A {@code NULL_HANDLING}
     * requirement is about nullability, which is a {@code NOT NULL}. Only a general invariant is tested
     * against everything the schema declares.
     */
    private static List<SqlSchemaModel.Constraint> candidatesFor(SqlSchemaModel model, ObligationKind kind) {
        return switch (kind) {
            case REJECTION, BOUNDARY_VALUE, UPPER_BOUND, LOWER_BOUND ->
                    ofKind(model, SqlSchemaModel.ConstraintKind.CHECK);
            case NULL_HANDLING -> ofKind(model, SqlSchemaModel.ConstraintKind.NOT_NULL);
            case PROPERTY, GENERIC, INVARIANT, DETERMINISM -> model.constraints();
            // A CONCURRENCY obligation is not something a single-connection constraint probe can bear on,
            // and a STATIC, SECURITY or MANUAL one is not this generator's to attempt at all.
            default -> List.of();
        };
    }

    /**
     * Narrows probes to the columns the requirement actually talks about.
     *
     * <p>A requirement that names a column is not verified by a constraint on another one: "a negative
     * amount is rejected" is not demonstrated by a check on a transaction's kind. When the requirement
     * names no schema column at all it is a statement about the schema as a whole, and every constraint
     * the schema declares bears on it.
     */
    private static List<SqlSchemaModel.Constraint> relevant(
            SqlSchemaModel model, Requirement requirement, List<SqlSchemaModel.Constraint> candidates) {
        Set<String> mentioned = mentionedColumns(model, requirement);
        if (mentioned.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(constraint -> constraint.columns().stream()
                        .anyMatch(column -> mentioned.contains(column.toLowerCase(Locale.ROOT))))
                .toList();
    }

    /** @return the schema columns whose name the requirement's own text names. */
    private static Set<String> mentionedColumns(SqlSchemaModel model, Requirement requirement) {
        String text = ((requirement.id() == null ? "" : requirement.id()) + " "
                + (requirement.title() == null ? "" : requirement.title()) + " "
                + (requirement.description() == null ? "" : requirement.description()))
                .toUpperCase(Locale.ROOT);
        Set<String> mentioned = new LinkedHashSet<>();
        for (SqlSchemaModel.Table table : model.tables()) {
            for (SqlSchemaModel.Column column : table.columns()) {
                if (Pattern.compile("\\b" + Pattern.quote(column.name().toUpperCase(Locale.ROOT)) + "\\b")
                        .matcher(text).find()) {
                    mentioned.add(column.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        return mentioned;
    }

    private static List<SqlSchemaModel.Constraint> ofKind(
            SqlSchemaModel model, SqlSchemaModel.ConstraintKind kind) {
        return model.constraints().stream().filter(constraint -> constraint.kind() == kind).toList();
    }

    /** @return the SQL script a generated test is made of. */
    private static String render(
            Requirement requirement, VerificationObligation obligation, List<SqlConstraintProbe.Probe> probes) {
        StringBuilder script = new StringBuilder();
        script.append("-- CodeWitness generated constraint probes; run against a database loaded with the\n")
                .append("-- project's schema, on a connection with foreign keys enabled.\n")
                .append("-- requirement: ").append(requirement.id()).append('\n')
                .append("-- obligation: ").append(obligation.id()).append('\n')
                .append("-- ").append(oneLine(obligation.description())).append('\n');
        for (SqlConstraintProbe.Probe probe : probes) {
            script.append('\n').append("-- ").append(oneLine(probe.description())).append('\n');
            for (String setup : probe.setup()) {
                script.append(setup).append(";\n");
            }
            // The directive is the convention from SqlTestScript: this statement must be rejected.
            script.append("-- expect: error\n").append(probe.violating()).append(";\n");
        }
        return script.toString();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** @return an identifier-safe form of a requirement id, for a generated script's name. */
    static String sanitize(String value) {
        String cleaned = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        cleaned = cleaned.replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "requirement" : cleaned;
    }
}

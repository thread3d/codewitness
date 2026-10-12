package dev.codewitness.sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A statement, or a pair of statements, that a declared constraint must reject.
 *
 * <p>A constraint is only worth verifying by trying to break it: a migration that quietly dropped or
 * weakened a {@code NOT NULL}, a {@code CHECK} or a foreign key is the failure this catches, and the
 * database's own refusal is the evidence. So a probe is an insert the constraint says must fail, written
 * so that it fails <em>for that reason and no other</em>.
 *
 * <p>That second property is what makes this class conservative. A row is built with a value for every
 * other column chosen to satisfy the constraints on it; when a column's check is expressed in a shape
 * this reader does not understand, the probe is not generated at all, because a statement that is
 * rejected for some other reason would make the probe pass without the constraint ever firing - a false
 * verification, which is worse than an unverified requirement. A nullable column whose check is not
 * understood is set to {@code NULL}, which every check accepts.
 */
final class SqlConstraintProbe {

    /**
     * @param setup       statements that must succeed first, such as the row a duplicate then repeats
     * @param violating   the statement the constraint must reject
     * @param description what the probe demonstrates
     * @param inputs      the values the probe used, so a counterexample can name them
     */
    record Probe(List<String> setup, String violating, String description, Map<String, Object> inputs) {

        Probe {
            setup = List.copyOf(setup == null ? List.of() : setup);
            inputs = Map.copyOf(inputs == null ? Map.of() : inputs);
        }
    }

    private static final Pattern COMPARISON = Pattern.compile(
            "(?is)\\b([\\w\"`\\[\\].]+)\\s*(>=|<=|<>|!=|==|=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern BETWEEN =
            Pattern.compile("(?is)\\b([\\w\"`\\[\\].]+)\\s+between\\s+(-?\\d+(?:\\.\\d+)?)\\s+and\\s+(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern IN_LIST = Pattern.compile("(?is)\\b([\\w\"`\\[\\].]+)\\s+in\\s*\\(([^)]*)\\)");
    private static final Pattern LENGTH = Pattern.compile(
            "(?is)\\blength\\s*\\(\\s*([\\w\"`\\[\\].]+)\\s*\\)\\s*(>=|<=|>|<|=)\\s*(\\d+)");

    private SqlConstraintProbe() {
    }

    /** @return a probe for the constraint, or empty when one cannot be built without guessing. */
    static Optional<Probe> forConstraint(SqlSchemaModel model, SqlSchemaModel.Constraint constraint) {
        Optional<SqlSchemaModel.Table> found = model.table(constraint.table());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SqlSchemaModel.Table table = found.get();
        return switch (constraint.kind()) {
            case NOT_NULL -> notNull(table, constraint);
            case PRIMARY_KEY, UNIQUE -> duplicate(table, constraint);
            case CHECK -> check(table, constraint);
            case FOREIGN_KEY -> foreignKey(table, constraint);
        };
    }

    private static Optional<Probe> notNull(SqlSchemaModel.Table table, SqlSchemaModel.Constraint constraint) {
        if (constraint.columns().size() != 1) {
            return Optional.empty();
        }
        String column = constraint.columns().getFirst();
        return withOverride(table, Map.of(column, "NULL"))
                .map(row -> new Probe(List.of(), insert(table, row),
                        "inserting NULL into " + table.name() + "." + column + " must be rejected",
                        Map.of("table", table.name(), "column", column, "value", "NULL")));
    }

    private static Optional<Probe> duplicate(SqlSchemaModel.Table table, SqlSchemaModel.Constraint constraint) {
        return withOverride(table, Map.of()).map(row -> {
            String insert = insert(table, row);
            // The first insert must succeed, so the second is rejected for the duplicate and not because
            // the row itself is invalid. A schema that rejects the row would fail the setup, and the test
            // then fails for that reason rather than passing on a rejection it did not earn.
            return new Probe(List.of(insert), insert,
                    "inserting the same " + String.join(", ", constraint.columns())
                            + " twice into " + table.name() + " must be rejected",
                    Map.of("table", table.name(), "columns", String.join(", ", constraint.columns())));
        });
    }

    private static Optional<Probe> check(SqlSchemaModel.Table table, SqlSchemaModel.Constraint constraint) {
        String expression = constraint.expression();
        if (expression == null) {
            return Optional.empty();
        }
        // A check must be attributable to exactly one column for the probe to isolate it. The model
        // attributes a table-level check when it names a single column; anything else is left alone.
        if (constraint.columns().size() != 1) {
            return Optional.empty();
        }
        String column = constraint.columns().getFirst();
        Optional<String> violating = violatingValue(expression, column);
        if (violating.isEmpty()) {
            return Optional.empty();
        }
        return withOverride(table, Map.of(column, violating.get()))
                .map(row -> new Probe(List.of(), insert(table, row),
                        "a value violating CHECK (" + expression + ") on " + table.name() + "." + column
                                + " must be rejected",
                        Map.of("table", table.name(), "column", column, "value", violating.get())));
    }

    private static Optional<Probe> foreignKey(SqlSchemaModel.Table table, SqlSchemaModel.Constraint constraint) {
        if (constraint.columns().size() != 1) {
            return Optional.empty();
        }
        String column = constraint.columns().getFirst();
        Optional<SqlSchemaModel.Column> found = table.column(column);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        // The parent table is not seeded by the probe, so a value no parent row has is one this insert
        // invents. A numeric key gets an unlikely number and a text key an unlikely string.
        String missing = isNumeric(found.get().type()) ? "-987654321" : "'__codewitness_missing_parent__'";
        return withOverride(table, Map.of(column, missing))
                .map(row -> new Probe(
                        // sqlite does not enforce foreign keys unless the connection asks it to, and the
                        // pragma is per connection, so the probe turns it on itself and the statement that
                        // must be rejected is the insert alone.
                        List.of("PRAGMA foreign_keys = ON"), insert(table, row),
                        "a " + table.name() + "." + column + " that names no "
                                + (constraint.referenceTable() == null ? "parent" : constraint.referenceTable())
                                + " row must be rejected",
                        Map.of("table", table.name(), "column", column, "value", missing)));
    }

    /**
     * Builds a row for the table with the given columns overridden.
     *
     * @return the column-to-literal map, or empty when a NOT NULL column cannot be given a value that
     *     satisfies its own checks
     */
    private static Optional<Map<String, String>> withOverride(
            SqlSchemaModel.Table table, Map<String, String> overrides) {
        Map<String, String> row = new LinkedHashMap<>();
        for (SqlSchemaModel.Column column : table.columns()) {
            if (overrides.containsKey(column.name())) {
                continue;
            }
            Optional<String> satisfying = satisfyingValue(column);
            if (satisfying.isPresent()) {
                row.put(column.name(), satisfying.get());
                continue;
            }
            if (column.notNull()) {
                // A required column with a check this reader cannot satisfy would make the probe reject
                // the row for the wrong reason, so no probe is built at all.
                return Optional.empty();
            }
            row.put(column.name(), "NULL");
        }
        row.putAll(overrides);
        return Optional.of(row);
    }

    /**
     * @return a literal for the column that satisfies its NOT NULL and its simple checks, or empty when
     *     one of its checks is not understood
     */
    private static Optional<String> satisfyingValue(SqlSchemaModel.Column column) {
        String literal = literalFor(column).orElse(null);
        for (String check : column.checks()) {
            Optional<String> within = satisfyingValueFor(check, column.name());
            if (within.isEmpty()) {
                // A check this reader cannot satisfy means the probe cannot be built without risking a
                // rejection for the wrong reason.
                return Optional.empty();
            }
            literal = within.get();
        }
        if (literal == null && column.notNull()) {
            return Optional.empty();
        }
        return Optional.ofNullable(literal);
    }

    /** @return a type-appropriate literal for a column, or empty for a type this reader cannot write. */
    static Optional<String> literalFor(SqlSchemaModel.Column column) {
        String type = column.type() == null ? "" : column.type().toUpperCase(Locale.ROOT);
        if (type.startsWith("BLOB") || type.isEmpty()) {
            // An unknown or binary type is written as a small blob, which SQLite accepts for any column.
            return Optional.of("x'00'");
        }
        if (type.startsWith("TEXT") || type.startsWith("CHAR") || type.startsWith("CLOB") || type.startsWith("VARCHAR")) {
            return Optional.of("'codewitness'");
        }
        return Optional.of("1");
    }

    private static boolean isNumeric(String type) {
        String upper = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return !(upper.startsWith("TEXT") || upper.startsWith("CHAR") || upper.startsWith("CLOB")
                || upper.startsWith("VARCHAR") || upper.startsWith("BLOB"));
    }

    /** @return the value that makes a check false, or empty when the check's shape is not understood. */
    static Optional<String> violatingValue(String expression, String column) {
        Matcher length = LENGTH.matcher(expression);
        if (length.find() && length.group(1).equalsIgnoreCase(column)) {
            int bound = Integer.parseInt(length.group(3));
            return switch (length.group(2)) {
                case ">=", ">" -> Optional.of(stringLiteral(Math.max(0, bound - 1)));
                case "<=", "<" -> Optional.of(stringLiteral(bound + 1));
                default -> Optional.of(stringLiteral(bound + 1));
            };
        }
        Matcher between = BETWEEN.matcher(expression);
        if (between.find() && between.group(1).equalsIgnoreCase(column)) {
            return Optional.of(number(between.group(2), -1));
        }
        Matcher comparison = COMPARISON.matcher(expression);
        if (comparison.find() && comparison.group(1).equalsIgnoreCase(column)) {
            String operator = comparison.group(2);
            String bound = comparison.group(3);
            return Optional.of(switch (operator) {
                // The value that makes a comparison false: strictly greater than a bound needs exactly the
                // bound, and at-least needs the step below it.
                case ">=" -> number(bound, -1);
                case ">" -> number(bound, 0);
                case "<=" -> number(bound, 1);
                case "<" -> number(bound, 0);
                default -> number(bound, 1);
            });
        }
        Matcher in = IN_LIST.matcher(expression);
        if (in.find() && in.group(1).equalsIgnoreCase(column)) {
            return Optional.of("-987654321");
        }
        return Optional.empty();
    }

    /** @return a value that satisfies a check, or empty when the check's shape is not understood. */
    static Optional<String> satisfyingValueFor(String expression, String column) {
        Matcher length = LENGTH.matcher(expression);
        if (length.find() && length.group(1).equalsIgnoreCase(column)) {
            int bound = Integer.parseInt(length.group(3));
            return switch (length.group(2)) {
                case ">=", ">" -> Optional.of(stringLiteral(bound));
                case "<=", "<" -> Optional.of(stringLiteral(Math.max(0, bound - 1)));
                default -> Optional.of(stringLiteral(bound));
            };
        }
        Matcher between = BETWEEN.matcher(expression);
        if (between.find() && between.group(1).equalsIgnoreCase(column)) {
            return Optional.of(number(between.group(2), 0));
        }
        Matcher comparison = COMPARISON.matcher(expression);
        if (comparison.find() && comparison.group(1).equalsIgnoreCase(column)) {
            String operator = comparison.group(2);
            String bound = comparison.group(3);
            return Optional.of(switch (operator) {
                // The value that satisfies a comparison: the bound itself for a strict one, and the bound
                // for an inclusive one.
                case ">" -> number(bound, 1);
                case "<" -> number(bound, -1);
                default -> number(bound, 0);
            });
        }
        Matcher in = IN_LIST.matcher(expression);
        if (in.find() && in.group(1).equalsIgnoreCase(column)) {
            String[] values = in.group(2).split(",");
            return values.length == 0 ? Optional.empty() : Optional.of(values[0].trim());
        }
        // A check with a shape this reader does not understand is left for the caller to skip: guessing a
        // value would risk a probe that is rejected for a reason other than the constraint under test.
        return Optional.empty();
    }

    private static String insert(SqlSchemaModel.Table table, Map<String, String> row) {
        List<String> columns = new ArrayList<>(row.keySet());
        List<String> values = columns.stream().map(row::get).toList();
        return "INSERT INTO " + table.name() + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", values) + ")";
    }

    private static String number(String bound, int delta) {
        BigDecimal value = new BigDecimal(bound).add(BigDecimal.valueOf(delta));
        return value.stripTrailingZeros().toPlainString();
    }

    private static String stringLiteral(int length) {
        return "'" + "x".repeat(Math.max(0, length)) + "'";
    }
}

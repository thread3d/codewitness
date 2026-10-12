package dev.codewitness.sql;

import dev.codewitness.sql.analysis.SqlSourceScan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tables and constraints a SQL tree declares, read from its own DDL.
 *
 * <p>This is what makes schema-derived verification possible: the constraints are already written down,
 * so CodeWitness does not have to invent property tests for a data model it cannot see. Each declared
 * constraint becomes a probe - a statement that must be rejected - and a constraint that does not fire
 * is a real defect, because a migration that silently dropped or weakened it is exactly the failure
 * mode this checks for.
 *
 * <p>Only {@code CREATE TABLE} is read. Views, triggers and stored procedures are not modelled, because
 * they are not a source of constraints; the rule tables cover their risks separately. Parsing is
 * deliberately conservative: a construct this reader does not recognise yields no constraint rather
 * than a guessed one, and a table whose row cannot be constructed without violating another constraint
 * yields no probe. A probe that could pass for the wrong reason would be worse than no probe.
 */
public record SqlSchemaModel(List<Table> tables) {

    /** One declared table. */
    public record Table(String name, List<Column> columns, List<Constraint> constraints) {

        /** @return the column with that name, case-insensitively, or empty when there is none. */
        public Optional<Column> column(String name) {
            return columns.stream()
                    .filter(column -> column.name().equalsIgnoreCase(name))
                    .findFirst();
        }

        /** @return the single-column primary key, or empty when the key is composite or absent. */
        public Optional<Column> singlePrimaryKey() {
            return constraints.stream()
                    .filter(constraint -> constraint.kind() == ConstraintKind.PRIMARY_KEY)
                    .filter(constraint -> constraint.columns().size() == 1)
                    .findFirst()
                    .flatMap(constraint -> column(constraint.columns().getFirst()));
        }
    }

    /** One declared column. */
    public record Column(
            String name,
            String type,
            boolean notNull,
            boolean primaryKey,
            boolean unique,
            List<String> checks) {

        public Column {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    /** The kinds of constraint this reader recognises. */
    public enum ConstraintKind {
        NOT_NULL,
        PRIMARY_KEY,
        UNIQUE,
        CHECK,
        FOREIGN_KEY
    }

    /**
     * One declared constraint.
     *
     * @param kind           what is being constrained
     * @param table          the table it belongs to
     * @param columns        the columns it covers, in declaration order
     * @param expression     the check expression, for {@link ConstraintKind#CHECK}, or {@code null}
     * @param referenceTable the parent table of a foreign key, or {@code null}
     */
    public record Constraint(
            ConstraintKind kind,
            String table,
            List<String> columns,
            String expression,
            String referenceTable) {

        public Constraint {
            columns = List.copyOf(columns == null ? List.of() : columns);
        }

        /** @return how the constraint is named in a finding or a counterexample. */
        public String describe() {
            String target = columns.isEmpty() ? table : table + "(" + String.join(", ", columns) + ")";
            return switch (kind) {
                case NOT_NULL -> "NOT NULL on " + target;
                case PRIMARY_KEY -> "PRIMARY KEY on " + target;
                case UNIQUE -> "UNIQUE on " + target;
                case CHECK -> "CHECK (" + expression + ") on " + target;
                case FOREIGN_KEY -> "FOREIGN KEY on " + target
                        + (referenceTable == null ? "" : " referencing " + referenceTable);
            };
        }
    }

    public SqlSchemaModel {
        tables = List.copyOf(tables == null ? List.of() : tables);
    }

    /** @return every constraint in declaration order, table by table. */
    public List<Constraint> constraints() {
        List<Constraint> all = new ArrayList<>();
        for (Table table : tables) {
            all.addAll(table.constraints());
        }
        return all;
    }

    /** @return the table with that name, case-insensitively, or empty when there is none. */
    public Optional<Table> table(String name) {
        return tables.stream().filter(table -> table.name().equalsIgnoreCase(name)).findFirst();
    }

    /** @return {@code true} when the tree declares no table at all. */
    public boolean isEmpty() {
        return tables.isEmpty();
    }

    /** @return the model of the schema under a project root, or an empty model when none parses. */
    public static SqlSchemaModel of(Path projectRoot) {
        return parseScripts(SqlProjectDetector.schemaFiles(projectRoot));
    }

    /** @return the model of a set of scripts, in the order given. */
    public static SqlSchemaModel parseScripts(List<Path> scripts) {
        List<Table> tables = new ArrayList<>();
        for (Path script : scripts) {
            tables.addAll(parse(SqlProjectDetector.readString(script)));
        }
        return new SqlSchemaModel(tables);
    }

    /** @return the tables declared by one script. */
    public static List<Table> parse(String script) {
        if (script == null || script.isBlank()) {
            return List.of();
        }
        List<Table> tables = new ArrayList<>();
        for (String statement : dev.codewitness.support.database.SqlScript.statements(script)) {
            parseCreateTable(statement).ifPresent(tables::add);
        }
        return List.copyOf(tables);
    }

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*create\\s+(?:temp(?:orary)?\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?"
                    + "([\\w\"`\\[\\].]+)\\s*\\((.*)\\)\\s*$");

    private static final Set<String> TABLE_CONSTRAINT_KEYWORDS =
            Set.of("primary", "unique", "check", "foreign", "constraint");

    /** Matches the {@code CHECK} keyword and its opening parenthesis; the expression is read balanced. */
    private static final Pattern CHECK_EXPRESSION = Pattern.compile("(?is)\\bcheck\\s*\\(");
    private static final Pattern REFERENCES = Pattern.compile(
            "(?is)\\breferences\\s+([\\w\"`\\[\\].]+)\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern PRIMARY_KEY_LIST = Pattern.compile("(?is)\\bprimary\\s+key\\s*\\(([^)]*)\\)");
    private static final Pattern UNIQUE_LIST = Pattern.compile("(?is)\\bunique\\s*\\(([^)]*)\\)");

    private static Optional<Table> parseCreateTable(String statement) {
        // The name is read from the original statement because a quoted identifier is masked out in the
        // structural view, and the body is then read from the masked view at the same offsets: masking
        // preserves length, so a comma or a parenthesis inside a literal or a string cannot be mistaken
        // for structure, while a CHECK's expression is still reported as it was written.
        SqlSourceScan scan = SqlSourceScan.of(statement);
        Matcher matcher = CREATE_TABLE.matcher(statement);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String name = cleanIdentifier(matcher.group(1));
        int bodyOffset = matcher.start(2);
        int bodyEnd = matcher.end(2);
        String maskedBody = scan.masked().substring(bodyOffset, bodyEnd);
        List<String> parts = splitTopLevel(maskedBody);
        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        int cursor = 0;
        for (String raw : parts) {
            // The part is kept untrimmed so its offset and length can slice the original text: a quoted
            // identifier is blanked in the masked view, and the original slice is what names the column.
            int offset = maskedBody.indexOf(raw, cursor);
            if (offset < 0) {
                offset = cursor;
            }
            cursor = offset + raw.length();
            String part = raw.trim();
            int from = bodyOffset + offset;
            int to = Math.min(statement.length(), from + raw.length());
            String original = from >= 0 && to >= from ? statement.substring(from, to) : part;
            readPart(name, part, original).ifPresent(declaration -> {
                if (declaration.column() != null) {
                    columns.add(declaration.column());
                }
                constraints.addAll(declaration.constraints());
            });
        }
        Attributed attributed = attributeTableChecks(columns, constraints);
        return Optional.of(new Table(name, attributed.columns(), attributed.constraints()));
    }

    /** The columns and constraints after table-level checks have been attributed. */
    private record Attributed(List<Column> columns, List<Constraint> constraints) {
    }

    /**
     * Attributes a table-level {@code CHECK} that names exactly one column to that column.
     *
     * <p>A probe has to know which column to violate to isolate a constraint, and a
     * {@code CHECK (amount >= 0)} written as a table constraint names its column in the expression. The
     * expression is also added to that column's own checks, so a probe against a <em>different</em>
     * constraint on the same table still builds a row that satisfies this one. A check naming several
     * columns, or none this reader can see, stays unattributed and produces no probe - which is the
     * conservative direction, because a probe that cannot isolate its constraint could pass for the
     * wrong reason.
     */
    private static Attributed attributeTableChecks(List<Column> columns, List<Constraint> constraints) {
        List<Column> updated = new ArrayList<>(columns);
        List<Constraint> attributed = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (constraint.kind() != ConstraintKind.CHECK || !constraint.columns().isEmpty()) {
                attributed.add(constraint);
                continue;
            }
            Optional<String> named = singleColumnMentioned(constraint.expression(), updated);
            if (named.isEmpty()) {
                attributed.add(constraint);
                continue;
            }
            attributed.add(new Constraint(ConstraintKind.CHECK, constraint.table(), List.of(named.get()),
                    constraint.expression(), null));
            for (int index = 0; index < updated.size(); index++) {
                Column column = updated.get(index);
                if (!column.name().equalsIgnoreCase(named.get())) {
                    continue;
                }
                List<String> checks = new ArrayList<>(column.checks());
                checks.add(constraint.expression());
                updated.set(index, new Column(column.name(), column.type(), column.notNull(),
                        column.primaryKey(), column.unique(), checks));
            }
        }
        return new Attributed(List.copyOf(updated), List.copyOf(attributed));
    }

    /** @return the one column of the table whose name appears in the expression, or empty when not one. */
    private static Optional<String> singleColumnMentioned(String expression, List<Column> columns) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        String upper = expression.toUpperCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (Column column : columns) {
            Matcher word = Pattern.compile("\\b" + Pattern.quote(column.name().toUpperCase(Locale.ROOT)) + "\\b")
                    .matcher(upper);
            if (word.find()) {
                found.add(column.name());
            }
        }
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /** One top-level part of a table body: either a column declaration or a table constraint. */
    private record Declaration(Column column, List<Constraint> constraints) {
    }

    private static Optional<Declaration> readPart(String table, String masked, String original) {
        String trimmed = masked.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String keyword = firstWord(trimmed).toLowerCase(Locale.ROOT);
        if (TABLE_CONSTRAINT_KEYWORDS.contains(keyword)) {
            return Optional.of(new Declaration(null, tableConstraints(table, trimmed, original)));
        }
        return columnDeclaration(table, trimmed, original);
    }

    private static Optional<Declaration> columnDeclaration(String table, String masked, String original) {
        // The name comes from the original because a quoted identifier is blanked in the structural view;
        // masking preserves length, so the rest of the declaration can still be read from that view at the
        // same offsets, where a keyword inside a string cannot be mistaken for a declaration.
        String originalTrimmed = original.trim();
        Matcher name = Pattern.compile("(?is)^([\\w\"`\\[\\].]+)\\s*(.*)$").matcher(originalTrimmed);
        if (!name.find()) {
            return Optional.empty();
        }
        String column = cleanIdentifier(name.group(1));
        int nameEnd = Math.min(name.end(1), masked.trim().length());
        String rest = masked.trim().substring(nameEnd).trim();
        String type = readType(rest);
        String upper = rest.toUpperCase(Locale.ROOT);
        boolean notNull = upper.contains("NOT NULL");
        boolean primaryKey = upper.contains("PRIMARY KEY");
        boolean unique = upper.contains("UNIQUE");
        // Both views are trimmed, so their offsets coincide: masking preserves length, and the original
        // is what an expression is quoted from.
        List<String> checks = checkExpressions(masked.trim(), originalTrimmed);
        List<Constraint> constraints = new ArrayList<>();
        if (notNull || primaryKey) {
            // A primary key column is NOT NULL in SQLite even without the keyword, and a probe for the
            // key's uniqueness already covers the null case; recording NOT NULL as well would double it.
            if (notNull && !primaryKey) {
                constraints.add(new Constraint(ConstraintKind.NOT_NULL, table, List.of(column), null, null));
            }
        }
        if (primaryKey) {
            constraints.add(new Constraint(ConstraintKind.PRIMARY_KEY, table, List.of(column), null, null));
        } else if (unique) {
            constraints.add(new Constraint(ConstraintKind.UNIQUE, table, List.of(column), null, null));
        }
        for (String expression : checks) {
            constraints.add(new Constraint(ConstraintKind.CHECK, table, List.of(column), expression, null));
        }
        Matcher references = REFERENCES.matcher(originalTrimmed);
        if (references.find()) {
            constraints.add(new Constraint(ConstraintKind.FOREIGN_KEY, table, List.of(column), null,
                    cleanIdentifier(references.group(1))));
        }
        return Optional.of(new Declaration(
                new Column(column, type, notNull || primaryKey, primaryKey, unique, checks),
                List.copyOf(constraints)));
    }

    private static List<Constraint> tableConstraints(String table, String masked, String original) {
        List<Constraint> constraints = new ArrayList<>();
        // The masked and original views are trimmed and therefore the same length, and a leading
        // CONSTRAINT <name> is removed from both so the two stay aligned for expression quoting.
        String originalTrimmed = original.trim();
        String maskedTrimmed = masked.trim();
        Matcher named = Pattern.compile("(?is)^constraint\\s+[\\w\"`\\[\\].]+\\s+").matcher(originalTrimmed);
        int prefix = named.find() ? named.end() : 0;
        prefix = Math.min(prefix, maskedTrimmed.length());
        String withoutName = maskedTrimmed.substring(prefix);
        String originalBody = originalTrimmed.substring(prefix);
        String upper = withoutName.toUpperCase(Locale.ROOT);
        Matcher primary = PRIMARY_KEY_LIST.matcher(withoutName);
        if (upper.startsWith("PRIMARY KEY") && primary.find()) {
            constraints.add(new Constraint(ConstraintKind.PRIMARY_KEY, table,
                    identifiers(primary.group(1)), null, null));
            return constraints;
        }
        Matcher unique = UNIQUE_LIST.matcher(withoutName);
        if (upper.startsWith("UNIQUE") && unique.find()) {
            constraints.add(new Constraint(ConstraintKind.UNIQUE, table, identifiers(unique.group(1)), null, null));
            return constraints;
        }
        if (upper.startsWith("FOREIGN KEY")) {
            Matcher key = Pattern.compile("(?is)\\bforeign\\s+key\\s*\\(([^)]*)\\)").matcher(withoutName);
            Matcher references = REFERENCES.matcher(originalBody);
            if (key.find()) {
                constraints.add(new Constraint(ConstraintKind.FOREIGN_KEY, table, identifiers(key.group(1)), null,
                        references.find() ? cleanIdentifier(references.group(1)) : null));
            }
            return constraints;
        }
        for (String expression : checkExpressions(withoutName, originalBody)) {
            constraints.add(new Constraint(ConstraintKind.CHECK, table, List.of(), expression, null));
        }
        return constraints;
    }

    /** @return the CHECK expressions in a declaration, with their parentheses balanced and unquoted. */
    private static List<String> checkExpressions(String masked, String original) {
        List<String> expressions = new ArrayList<>();
        Matcher matcher = CHECK_EXPRESSION.matcher(masked);
        while (matcher.find()) {
            balancedExpression(masked, original, matcher.end() - 1).ifPresent(expressions::add);
        }
        return expressions;
    }

    /**
     * @param masked   the structural view, where quoted regions and their parentheses are blanked
     * @param original the text as written, sliced with the same offsets
     * @param open     index of the opening parenthesis
     * @return the expression between the parentheses, or empty when they do not balance
     */
    private static Optional<String> balancedExpression(String masked, String original, int open) {
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                int end = Math.min(i, original.length());
                return open + 1 <= end ? Optional.of(original.substring(open + 1, end).trim()) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** @return the type of a column, with a parenthesised length kept, or {@code ""} when none is stated. */
    private static String readType(String rest) {
        Matcher type = Pattern.compile("(?is)^([a-z][a-z0-9_]*(?:\\s*\\([^)]*\\))?)").matcher(rest);
        return type.find() ? type.group(1).replaceAll("\\s+", "") : "";
    }

    private static List<String> identifiers(String list) {
        List<String> names = new ArrayList<>();
        for (String part : list.split(",")) {
            String cleaned = cleanIdentifier(part.trim());
            if (!cleaned.isEmpty()) {
                names.add(cleaned);
            }
        }
        return List.copyOf(names);
    }

    private static String firstWord(String text) {
        Matcher word = Pattern.compile("^[A-Za-z_]+").matcher(text);
        return word.find() ? word.group() : "";
    }

    /** @return an identifier without its quoting or its schema prefix. */
    static String cleanIdentifier(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.contains(".")) {
            cleaned = cleaned.substring(cleaned.lastIndexOf('.') + 1);
        }
        if (cleaned.length() >= 2) {
            char first = cleaned.charAt(0);
            char last = cleaned.charAt(cleaned.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
        }
        return cleaned;
    }

    /**
     * @param masked a masked table body
     * @return its top-level comma-separated parts, where commas inside parentheses do not separate
     */
    static List<String> splitTopLevel(String masked) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(masked.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(masked.substring(start));
        // The parts keep their surrounding whitespace so a caller can map them back onto the original
        // text; an all-whitespace part is a trailing comma and is dropped.
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }

    /** @return a stable, sorted view of the table names, for a description. */
    public List<String> tableNames() {
        return tables.stream().map(Table::name).sorted().toList();
    }
}

package dev.codewitness.sql;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Detects SQL projects and separates the schema under test from the test scripts.
 *
 * <p>Only files are read here; nothing is executed. SQL has no manifest, so the tree is the
 * description: every {@code .sql} and {@code .ddl} file is a source, and the split that matters is
 * between the statements that <em>define</em> the database - schema and migrations, which are the
 * language's first failure - and the scripts that <em>check</em> it, which are the tests.
 *
 * <p>The split is by convention because there is no other signal. A file is a test when it sits under
 * a {@code test}, {@code tests} or {@code spec} directory, or its own name marks it as one
 * ({@code test_*.sql}, {@code *_test.sql}, {@code *.test.sql}). Everything else is schema. A tree with
 * sources but no test script is still claimed, built and analysed, and its requirements stay
 * UNVERIFIED with the reason that nothing was executed - which is the honest report rather than a
 * silent pass.
 *
 * <p>Migration directories are recognised by name ({@code migrations}, {@code db/migrate},
 * {@code db/migration}, {@code changesets}) for the report only. Nothing here needs to know which tool
 * produced them: files are applied in file-name order, which is the order Flyway's version prefixes,
 * Rails' timestamps and a hand-rolled numbering all produce.
 */
public final class SqlProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_SCHEMA_FILES = "schemaFiles";
    public static final String ATTR_TEST_FILES = "testFiles";
    public static final String ATTR_MIGRATION_DIRS = "migrationDirs";
    public static final String ATTR_HAS_TESTS = "hasSqlTests";
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_TEST_FRAMEWORK = "testFramework";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";

    /** Source extensions, without the dot, that this adapter treats as SQL. */
    public static final Set<String> SOURCE_EXTENSIONS = Set.of("sql", "ddl");

    /** Directory names whose contents are test scripts rather than schema. */
    private static final Set<String> TEST_DIRECTORIES = Set.of("test", "tests", "spec", "specs");

    /** Directory names recognised as migration trees, for the report. */
    private static final Set<String> MIGRATION_DIRECTORIES =
            Set.of("migration", "migrations", "migrate", "changesets", "changelog");

    private SqlProjectDetector() {
    }

    /** @return {@code true} when the directory holds at least one SQL file. */
    public static boolean looksLikeSqlProject(Path root) {
        return !sourceFiles(root).isEmpty();
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        List<Path> sources = sourceFiles(root);
        List<Path> tests = sources.stream().filter(SqlProjectDetector::isTestFile).toList();
        List<Path> schema = sources.stream().filter(file -> !isTestFile(file)).toList();

        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.SQL)
                .buildSystem(BuildSystemId.SQL)
                .sourceExtensions(SOURCE_EXTENSIONS)
                .attribute(ATTR_TEST_FRAMEWORK, "sql-assertion-script");

        List<String> schemaNames = relative(root, schema);
        List<String> testNames = relative(root, tests);
        builder.attribute(ATTR_SCHEMA_FILES, String.join(",", schemaNames));
        builder.attribute(ATTR_TEST_FILES, String.join(",", testNames));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!tests.isEmpty()));
        builder.attribute(ATTR_DESCRIPTOR,
                schemaNames.isEmpty() ? "sql sources" : String.join(",", schemaNames));
        schema.stream().findFirst().ifPresent(builder::addDetectedDescriptor);
        if (!tests.isEmpty()) {
            builder.addTestFramework("sql-assertion-script");
        }

        List<Path> migrationDirs = distinctParents(schema).stream()
                .filter(SqlProjectDetector::isMigrationDirectory)
                .toList();
        builder.attribute(ATTR_MIGRATION_DIRS,
                String.join(",", migrationDirs.stream().map(dir -> root.relativize(dir).toString()).toList()));

        if (sources.isEmpty()) {
            builder.attribute(ATTR_DETECTION_WARNING, "no SQL file was found under the project root");
        } else if (schema.isEmpty()) {
            builder.attribute(ATTR_DETECTION_WARNING,
                    "every SQL file under this root is a test script, so the tree declares no schema to "
                            + "load: the tests can only run if they create their own tables");
        }

        List<Path> sourceDirs = distinctParents(schema);
        List<Path> testDirs = distinctParents(tests);
        if (sourceDirs.isEmpty() && !testDirs.isEmpty()) {
            sourceDirs = testDirs;
        }
        if (sourceDirs.isEmpty()) {
            sourceDirs = List.of(root);
        }
        builder.addModule(new ModuleInfo(project.name(), root, "schema", sourceDirs, testDirs, null));
        return builder.build();
    }

    /** @return every SQL source file below a root, sorted, skipping build output and VCS metadata. */
    public static List<Path> sourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(SqlProjectDetector::isSourceFile)
                    .filter(path -> !isExcluded(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree yields no files, which the caller reports rather than guesses about
        }
        return List.copyOf(files);
    }

    /** @return the scripts that define the database, in the order they must be applied. */
    public static List<Path> schemaFiles(Path root) {
        return sourceFiles(root).stream().filter(file -> !isTestFile(file)).toList();
    }

    /** @return the scripts that check the database. */
    public static List<Path> testFiles(Path root) {
        return sourceFiles(root).stream().filter(SqlProjectDetector::isTestFile).toList();
    }

    /** @return {@code true} when the file name carries one of the SQL source extensions. */
    public static boolean isSourceFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SOURCE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** @return {@code true} when the file's directory or name marks it as a test script. */
    public static boolean isTestFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith("test_") || name.startsWith("test-")
                || name.endsWith("_test.sql") || name.endsWith(".test.sql")) {
            return true;
        }
        Path parent = file.getParent();
        while (parent != null) {
            // A filesystem root has no file name; walking up from a file under /tmp reaches it.
            Path element = parent.getFileName();
            if (element != null && TEST_DIRECTORIES.contains(element.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    /** @return the identifier a test runner reports a script under, matching the core's mapper. */
    public static String suiteName(Path testRoot, Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        if (testRoot == null || !file.startsWith(testRoot)) {
            return stem;
        }
        String relative = testRoot.relativize(file).toString().replace(java.io.File.separatorChar, '.')
                .replace('/', '.');
        int lastDot = relative.lastIndexOf('.');
        return lastDot < 0 ? relative : relative.substring(0, lastDot);
    }

    /** @return the file's text, or an empty string when it cannot be read. */
    public static String readString(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }

    private static List<String> relative(Path root, List<Path> files) {
        List<String> names = new ArrayList<>();
        for (Path file : files) {
            names.add(root.relativize(file).toString());
        }
        return names;
    }

    private static boolean isMigrationDirectory(Path dir) {
        for (Path element : dir) {
            if (MIGRATION_DIRECTORIES.contains(element.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> distinctParents(List<Path> files) {
        Set<Path> dirs = new LinkedHashSet<>();
        for (Path file : files) {
            Path parent = file.getParent();
            if (parent != null) {
                dirs.add(parent);
            }
        }
        return List.copyOf(dirs);
    }

    /**
     * @return {@code true} when a path element below the walked root is build output, a cache, a
     *     dependency tree or a VCS directory.
     *
     * <p>Relative to the root rather than absolute, so the run's isolated workspace - which lives under
     * the project's {@code .codewitness} directory - is not rejected wholesale.
     */
    static boolean isExcluded(Path root, Path path) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException _) {
            relative = path;
        }
        Set<String> excluded = Set.of(
                ".git", ".codewitness", "node_modules", "target", "build", "out", "dist", ".idea", ".vscode",
                "venv", ".venv", "vendor");
        for (Path element : relative) {
            if (excluded.contains(element.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

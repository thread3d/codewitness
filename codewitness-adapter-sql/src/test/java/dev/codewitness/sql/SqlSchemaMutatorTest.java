package dev.codewitness.sql;

import dev.codewitness.sql.SqlSchemaMutator.Site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutation sites in a schema.
 *
 * <p>The operator set is small on purpose, so the tests are as much about what is <em>not</em> mutated as
 * about what is: an ambiguous survivor would be worse than a smaller score.
 */
class SqlSchemaMutatorTest {

    private static String apply(String schema, Site site) {
        return schema.substring(0, site.start()) + site.replacement() + schema.substring(site.end());
    }

    @Test
    @DisplayName("a CHECK with a comparison yields a boundary mutant and a removal mutant")
    void sitesForABoundedCheck() {
        String schema = "CREATE TABLE ledger (\n  id INTEGER PRIMARY KEY,\n  amount NUMERIC NOT NULL CHECK (amount <= 1000)\n);\n";

        List<Site> sites = SqlSchemaMutator.sites(schema);

        assertEquals(2, sites.size(), sites.toString());
        Site boundary = sites.getFirst();
        assertEquals("SqlConditionalBoundaryMutator", boundary.mutator());
        assertEquals(3, boundary.line());
        assertTrue(apply(schema, boundary).contains("amount < 1000"), apply(schema, boundary));
        Site removal = sites.get(1);
        assertEquals("SqlCheckRemovalMutator", removal.mutator());
        assertTrue(apply(schema, removal).contains("CHECK (1)"), apply(schema, removal));
    }

    @Test
    @DisplayName("a CHECK inside a comment or a string is not a mutation site")
    void masksCommentsAndStrings() {
        assertTrue(SqlSchemaMutator.sites("-- CHECK (amount <= 1000) in a comment\nSELECT 1;\n").isEmpty());
        assertTrue(SqlSchemaMutator.sites("INSERT INTO t VALUES ('CHECK (amount <= 1000)');\n").isEmpty());
    }

    @Test
    @DisplayName("a schema with no CHECK has nothing to mutate")
    void noCheckMeansNoSites() {
        assertTrue(SqlSchemaMutator.sites("CREATE TABLE t (id INTEGER PRIMARY KEY, amount NUMERIC);\n").isEmpty());
        assertTrue(SqlSchemaMutator.sites("").isEmpty());
    }

    @Test
    @DisplayName("an equality check is not a boundary site, but its removal still is")
    void equalityIsNotABoundaryMutation() {
        List<Site> sites = SqlSchemaMutator.sites("CREATE TABLE t (id INTEGER, kind TEXT CHECK (kind <> 'x'));\n");

        assertEquals(1, sites.size(), sites.toString());
        assertEquals("SqlCheckRemovalMutator", sites.getFirst().mutator());
    }

    @Test
    @DisplayName("every CHECK in a schema is a site, and each boundary operator is mutated one at a time")
    void mutatesEveryCheckIndependently() {
        String schema = "CREATE TABLE t (id INTEGER, a NUMERIC CHECK (a >= 0), b NUMERIC CHECK (b < 10));\n";

        List<Site> sites = SqlSchemaMutator.sites(schema);

        assertEquals(4, sites.size(), sites.toString());
        assertTrue(apply(schema, sites.get(0)).contains("a > 0"));
        assertTrue(apply(schema, sites.get(1)).contains("CHECK (1)"));
        assertTrue(apply(schema, sites.get(2)).contains("b <= 10"));
        assertFalse(apply(schema, sites.get(2)).contains("a > 0"), "one mutation at a time");
    }
}

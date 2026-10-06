package dev.codewitness.typescript.dependency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inventorying of a package.json's declared dependencies. */
class TypeScriptDependencyAnalyzerTest {

    @Test
    @DisplayName("dependency tables are inventoried and unrelated keys are ignored")
    void parsesDependencyTables() {
        String manifest = """
                {
                  "name": "sample-app",
                  "version": "1.0.0",
                  "scripts": {
                    "test": "node --test",
                    "dependencies": "not a table"
                  },
                  "dependencies": {
                    "left-pad": "^1.3.0",
                    "big.js": "6.2.1"
                  },
                  "devDependencies": {
                    "typescript": "5.5.2"
                  },
                  "optionalDependencies": {
                    "fsevents": "2.3.3"
                  },
                  "engines": {
                    "node": ">=22.6"
                  }
                }
                """;

        List<String> dependencies = TypeScriptDependencyAnalyzer.parse(manifest);

        assertEquals(List.of("left-pad", "big.js", "typescript", "fsevents"), dependencies);
        assertFalse(dependencies.contains("name"));
        assertFalse(dependencies.contains("test"));
        assertFalse(dependencies.contains("node"));
    }

    @Test
    @DisplayName("a manifest with no dependency table declares nothing and is still parsed")
    void handlesEmptyManifest() {
        assertTrue(TypeScriptDependencyAnalyzer.parse("{\"name\": \"app\"}").isEmpty());
        assertTrue(TypeScriptDependencyAnalyzer.parse("").isEmpty());
        assertTrue(TypeScriptDependencyAnalyzer.parse(null).isEmpty());
    }
}

package dev.codewitness.rust.dependency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The dependency inventory reads every Cargo dependency table, in all of its spellings. */
class RustDependencyAnalyzerTest {

    @Test
    @DisplayName("inline, table-style, dev and target-specific dependencies are all inventoried")
    void readsEveryDependencyTable() {
        String manifest = """
                [package]
                name = "sample"
                version = "0.1.0"

                [dependencies]
                serde = { version = "1", features = ["derive"] }
                regex = "1"

                [dev-dependencies]
                tempfile = "3"

                [target.'cfg(unix)'.dependencies]
                libc = "0.2"

                [dependencies.tokio]
                version = "1"
                features = ["rt"]
                """;

        List<String> names = RustDependencyAnalyzer.parse(manifest);

        assertEquals(List.of("serde", "regex", "tempfile", "libc", "tokio"), names);
    }

    @Test
    @DisplayName("a manifest with no dependencies declares none, and that is not an error")
    void emptyDependencySet() {
        assertEquals(List.of(), RustDependencyAnalyzer.parse("[package]\nname = \"sample\"\n\n[dependencies]\n"));
    }
}

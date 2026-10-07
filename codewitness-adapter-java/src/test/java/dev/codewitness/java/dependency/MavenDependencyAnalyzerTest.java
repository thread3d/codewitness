package dev.codewitness.java.dependency;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading a dependency inventory out of {@code dependency:list} output.
 *
 * <p>The command runs once per module, so its output repeats every coordinate once per dependent
 * module and mixes in build lines. A count taken naively from those lines overstates the project's
 * dependencies, which is exactly what happened once the multi-module invocation started working.
 */
class MavenDependencyAnalyzerTest {

    @Test
    void countsEachCoordinateOnceEvenWhenSeveralModulesShareIt() {
        String output = """
                [INFO] --- dependency:list:3.6.1:list (default-cli) @ codewitness-core ---
                [INFO]
                [INFO] The following files have been resolved:
                [INFO]    org.yaml:snakeyaml:jar:2.2:compile -- module org.yaml.snakeyaml
                [INFO]    dev.codewitness:codewitness-core:jar:0.1.0:compile
                [INFO]
                [INFO] --- dependency:list:3.6.1:list (default-cli) @ codewitness-storage ---
                [INFO]
                [INFO] The following files have been resolved:
                [INFO]    org.yaml:snakeyaml:jar:2.2:compile -- module org.yaml.snakeyaml
                [INFO]    dev.codewitness:codewitness-core:jar:0.1.0:compile
                """;

        List<String> dependencies = MavenDependencyAnalyzer.parseDependencies(output);

        assertEquals(List.of("org.yaml:snakeyaml:jar:2.2:compile",
                "dev.codewitness:codewitness-core:jar:0.1.0:compile"), dependencies);
    }

    @Test
    void ignoresBuildOutputThatAlsoContainsColons() {
        String output = """
                [INFO] Building CodeWitness :: Core 0.1.0
                [INFO] --- compiler:3.16.0:compile (default-compile) @ codewitness-core ---
                [INFO] Compiling 42 source files with javac [debug release 25] to target/classes
                [INFO] Downloading from central: https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar
                [INFO]    info.picocli:picocli:jar:4.7.6:compile -- module info.picocli
                """;

        List<String> dependencies = MavenDependencyAnalyzer.parseDependencies(output);

        assertEquals(List.of("info.picocli:picocli:jar:4.7.6:compile"), dependencies);
    }

    @Test
    void readsAPomTypeDependencyToo() {
        List<String> dependencies = MavenDependencyAnalyzer.parseDependencies(
                "[INFO]    dev.codewitness:codewitness-parent:pom:0.1.0:compile\n");

        assertEquals(List.of("dev.codewitness:codewitness-parent:pom:0.1.0:compile"), dependencies);
    }

    @Test
    void attributesEachCoordinateToTheModuleThatResolvesIt() {
        String output = """
                [INFO] --- dependency:3.7.0:list (default-cli) @ codewitness-core ---
                [INFO]
                [INFO] The following files have been resolved:
                [INFO]    org.yaml:snakeyaml:jar:2.2:compile -- module org.yaml.snakeyaml
                [INFO]    none
                [INFO]
                [INFO] ------------------< dev.codewitness:codewitness-report >------------------
                [INFO] --- dependency:3.7.0:list (default-cli) @ codewitness-report ---
                [INFO]
                [INFO] The following files have been resolved:
                [INFO]    org.yaml:snakeyaml:jar:2.2:compile -- module org.yaml.snakeyaml
                [INFO]    com.fasterxml.jackson.core:jackson-databind:jar:2.17.1:compile
                """;

        var byModule = MavenDependencyAnalyzer.parseDependenciesByModule(output);

        assertEquals(List.of("org.yaml:snakeyaml:jar:2.2:compile"), byModule.get("codewitness-core"));
        // snakeyaml reaches both modules; only the report module resolves Jackson.
        assertEquals(List.of("org.yaml:snakeyaml:jar:2.2:compile",
                "com.fasterxml.jackson.core:jackson-databind:jar:2.17.1:compile"),
                byModule.get("codewitness-report"));
    }

    @Test
    void aModuleThatResolvesNothingIsStillRecorded() {
        String output = """
                [INFO] --- dependency:3.7.0:list (default-cli) @ codewitness-parent ---
                [INFO]
                [INFO] The following files have been resolved:
                [INFO]    none
                """;

        var byModule = MavenDependencyAnalyzer.parseDependenciesByModule(output);

        assertEquals(List.of(), byModule.get("codewitness-parent"));
    }
}

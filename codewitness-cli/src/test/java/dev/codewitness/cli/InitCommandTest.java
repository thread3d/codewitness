package dev.codewitness.cli;

import dev.codewitness.core.config.ConfigLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code codewitness init} writes starter files that must themselves be valid configuration. */
class InitCommandTest {

    @TempDir
    Path tmp;

    @Test
    void createsTheConfigurationAndRequirementsFiles() {
        CliTestSupport.Result result = CliTestSupport.run("init", tmp.toString());

        assertEquals(0, result.exitCode());
        assertTrue(Files.isRegularFile(tmp.resolve("codewitness.yaml")), "the configuration is the point of init");
        assertTrue(Files.isRegularFile(tmp.resolve("requirements.md")));
        assertTrue(result.stdout().contains("Wrote 2 file(s)"), result.stdout());
    }

    @Test
    void refusesToOverwriteWithoutForce() throws IOException {
        Path config = tmp.resolve("codewitness.yaml");
        Files.writeString(config, "project:\n  name: mine\n", StandardCharsets.UTF_8);

        CliTestSupport.Result result = CliTestSupport.run("init", tmp.toString());

        assertEquals(0, result.exitCode());
        assertEquals("project:\n  name: mine\n", Files.readString(config, StandardCharsets.UTF_8),
                "an existing configuration must never be silently replaced");
        assertTrue(result.stdout().contains("skipped existing"), result.stdout());
        assertTrue(Files.isRegularFile(tmp.resolve("requirements.md")),
                "the file that did not exist is still written");
    }

    @Test
    void forceOverwritesExistingFiles() throws IOException {
        Path config = tmp.resolve("codewitness.yaml");
        Files.writeString(config, "project:\n  name: mine\n", StandardCharsets.UTF_8);

        CliTestSupport.Result result = CliTestSupport.run("init", tmp.toString(), "--force");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Wrote 2 file(s)"), result.stdout());
        assertFalse(Files.readString(config, StandardCharsets.UTF_8).contains("name: mine"));
        assertTrue(Files.readString(config, StandardCharsets.UTF_8).contains("change-me"));
    }

    @Test
    void theGeneratedYamlLoadsThroughConfigLoader() {
        CliTestSupport.run("init", tmp.toString());

        ConfigLoader.Loaded loaded = ConfigLoader.load(tmp.resolve("codewitness.yaml"));

        assertTrue(loaded.fromFile(), "the generated template must be a real configuration file");
        assertTrue(loaded.warnings().isEmpty(), "the template must not produce warnings: " + loaded.warnings());
        assertEquals("change-me", loaded.config().project().name());
        assertEquals("auto", loaded.config().language().adapter());
        assertEquals(java.time.Duration.ofMinutes(15), loaded.config().limits().timeout());
        assertEquals("pitest", loaded.config().mutation().tool());
        assertEquals(50, loaded.config().propertyTesting().maxScenarios());
        assertFalse(loaded.config().llm().enabled(), "the language model is off unless asked for");
        assertFalse(loaded.config().sandbox().allowNetwork());
        assertTrue(loaded.config().scenarios().isEmpty(),
                "the commented-out scenario example must not be parsed as a scenario");
    }

    @Test
    void initOnANonDirectoryReportsFailure() {
        CliTestSupport.Result result =
                CliTestSupport.run("init", tmp.resolve("does-not-exist").toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("not a directory"), result.stderr());
    }
}

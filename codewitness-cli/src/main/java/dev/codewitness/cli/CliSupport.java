package dev.codewitness.cli;

import dev.codewitness.core.config.ConfigLoader;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.engine.VerificationEngine;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.ProgressListener;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.java.JavaAdapterBundle;
import dev.codewitness.python.PythonAdapterBundle;
import dev.codewitness.rust.RustAdapterBundle;
import dev.codewitness.typescript.TypeScriptAdapterBundle;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared wiring for the command line interface. */
final class CliSupport {

    private CliSupport() {
    }

    /**
     * Builds the verification engine with every registered language adapter and provider.
     *
     * <p>This is the composition root: everything else in the codebase depends on interfaces.
     */
    static VerificationEngine engine() {
        LanguageAdapterRegistry languages = new LanguageAdapterRegistry();
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        JavaAdapterBundle.register(languages, providers, true);
        PythonAdapterBundle.register(languages, providers, true);
        RustAdapterBundle.register(languages, providers, true);
        TypeScriptAdapterBundle.register(languages, providers, true);
        languages.discover();
        return new VerificationEngine(languages, providers);
    }

    /** Resolves the configuration file: explicit flag, then {@code <project>/codewitness.yaml}, then defaults. */
    static ConfigLoader.Loaded loadConfig(Path projectRoot, Path explicitConfig) {
        if (explicitConfig != null) {
            return ConfigLoader.load(explicitConfig);
        }
        return ConfigLoader.loadFromProject(projectRoot);
    }

    /** Resolves the project reference for a path. */
    static ProjectRef projectRef(String path) {
        Path root = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }
        return ProjectRef.of(root);
    }

    /** @return the run directory base for a project, honouring an explicit override. */
    static Path runBase(Path projectRoot, Path override, VerificationConfig config) {
        if (override != null) {
            return override;
        }
        if (config.report().outputDirectory() != null) {
            return config.report().outputDirectory();
        }
        return projectRoot.resolve(".codewitness");
    }

    /** Progress listener that prints indented phase lines to standard out. */
    static ProgressListener consoleProgress(boolean quiet) {
        return (phase, message) -> {
            if (!quiet) {
                System.out.printf("  [%-16s] %s%n", phase, message);
            }
        };
    }

    /** @return a human readable duration. */
    static String duration(java.time.Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }

    /** Prints the status marker used throughout the reports. */
    static String marker(dev.codewitness.core.requirements.RequirementStatus status) {
        return switch (status) {
            case VERIFIED -> "\u2713";
            case PARTIALLY_VERIFIED -> "\u26a0";
            case FAILED -> "\u2717";
            case UNVERIFIED -> "?";
            case BLOCKED -> "\u26d4";
        };
    }
}

package dev.codewitness.cli;

import dev.codewitness.core.config.ConfigLoader;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.engine.VerificationEngine;
import dev.codewitness.core.engine.strategy.DefaultStrategies;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.LanguageDetectorRegistry;
import dev.codewitness.core.spi.ProgressListener;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.cpp.CppAdapterBundle;
import dev.codewitness.csharp.CSharpAdapterBundle;
import dev.codewitness.go.GoAdapterBundle;
import dev.codewitness.java.JavaAdapterBundle;
import dev.codewitness.javascript.JavaScriptAdapterBundle;
import dev.codewitness.kotlin.KotlinAdapterBundle;
import dev.codewitness.php.PhpAdapterBundle;
import dev.codewitness.python.PythonAdapterBundle;
import dev.codewitness.ruby.RubyAdapterBundle;
import dev.codewitness.swift.SwiftAdapterBundle;
import dev.codewitness.verilog.VerilogAdapterBundle;
import dev.codewitness.rust.RustAdapterBundle;
import dev.codewitness.typescript.TypeScriptAdapterBundle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        Wiring wiring = wiring();
        // Recognition for the languages no adapter below can verify. Without this a repository is reported
        // as though the code in those languages were not there.
        LanguageDetectorRegistry detectors = new LanguageDetectorRegistry().discover();
        return new VerificationEngine(wiring.languages(), wiring.providers(), detectors,
                new dev.codewitness.core.exec.LocalProcessSandbox(List.of(Path.of("."))).runner());
    }

    /**
     * The registries the CLI wires: every bundled adapter and every provider it registers.
     *
     * <p>Exposed to the module's own tests so that what ships can be asserted directly - in particular
     * that every ecosystem an adapter inventories is one the advisory reproduction lookup can answer
     * for, which is the hook a new language plugs into.
     */
    static Wiring wiring() {
        LanguageAdapterRegistry languages = new LanguageAdapterRegistry();
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        JavaAdapterBundle.register(languages, providers, true);
        PythonAdapterBundle.register(languages, providers, true);
        RustAdapterBundle.register(languages, providers, true);
        GoAdapterBundle.register(languages, providers, true);
        CSharpAdapterBundle.register(languages, providers, true);
        KotlinAdapterBundle.register(languages, providers, true);
        CppAdapterBundle.register(languages, providers, true);
        PhpAdapterBundle.register(languages, providers, true);
        RubyAdapterBundle.register(languages, providers, true);
        SwiftAdapterBundle.register(languages, providers, true);
        VerilogAdapterBundle.register(languages, providers, true);
        // JavaScript and TypeScript are registered as a pair because they own disjoint file sets -
        // js/mjs/cjs against ts/tsx/mts/cts - and can therefore both run over one tree. A TypeScript
        // frontend next to plain-JavaScript tooling is verified in both languages rather than losing one.
        JavaScriptAdapterBundle.register(languages, providers, true);
        TypeScriptAdapterBundle.register(languages, providers, true);
        languages.discover();
        // The verification strategies are providers, exactly like the tools above: this composition root
        // registers the ones that ship, and a caller that wants a different set registers its own. The
        // engine falls back to this same set only when nothing at all is registered, so an embedder that
        // wires no strategies gets verification rather than an empty report.
        DefaultStrategies.create(providers).forEach(providers::register);
        return new Wiring(languages, providers);
    }

    /** The two registries the composition root fills, kept together so a test can assert what ships. */
    record Wiring(LanguageAdapterRegistry languages, VerificationProviderRegistry providers) {
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

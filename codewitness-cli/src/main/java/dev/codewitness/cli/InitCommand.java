package dev.codewitness.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code codewitness init <project>} - writes starter configuration and requirements files.
 *
 * <p>The generated files are heavily commented, because the configuration is where a user decides how
 * much verification actually happens: without scenarios and requirements, CodeWitness can analyse a
 * project but cannot verify anything about it, and it will say so.
 */
@Command(name = "init", description = "Create a starter codewitness.yaml and requirements.md.")
public final class InitCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project directory.")
    private String project = ".";

    @Option(names = "--force", description = "Overwrite existing files.")
    private boolean force;

    @Override
    public Integer call() {
        Path root = Path.of(project).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 2;
        }
        int written = 0;
        written += write(root.resolve("codewitness.yaml"), CONFIG_TEMPLATE, force);
        written += write(root.resolve("requirements.md"), REQUIREMENTS_TEMPLATE, force);
        System.out.println("Wrote " + written + " file(s) to " + root);
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  1. Describe your requirements in requirements.md (one heading per requirement,");
        System.out.println("     for example '# R-001 Withdrawal limit').");
        System.out.println("  2. Add a scenario to codewitness.yaml for each requirement you want executed.");
        System.out.println("     Without a scenario CodeWitness will report the requirement as UNVERIFIED,");
        System.out.println("     which is the honest answer, not a failure.");
        System.out.println("  3. Run: codewitness verify . --requirements requirements.md");
        return 0;
    }

    private static int write(Path file, String content, boolean force) {
        if (Files.exists(file) && !force) {
            System.out.println("skipped existing " + file + " (use --force to overwrite)");
            return 0;
        }
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return 1;
        } catch (Exception e) {
            System.err.println("cannot write " + file + ": " + e.getMessage());
            return 0;
        }
    }

    private static final String CONFIG_TEMPLATE = """
            # CodeWitness configuration.
            #
            # Everything that executes project code is bounded and off the network by default.
            # The language model section is disabled unless you enable it; model output is advisory
            # and can never mark a requirement verified.

            project:
              name: change-me

            language:
              adapter: auto          # auto | java | python | rust | go | javascript | typescript | csharp

            verification:
              timeout: 15m           # per build/test command
              max_duration: 15m      # whole run
              max_test_generation_iterations: 5
              max_generated_tests: 500
              max_mutation_time: 10m

            tests:
              enabled: true

            mutation:
              enabled: true
              tool: pitest

            property_testing:
              enabled: true
              max_scenarios: 50
              concurrent: true

            static_analysis:
              enabled: true
              analyzers: [javac, heuristics]

            security:
              enabled: true

            llm:
              enabled: false
              provider: none         # none | openai-compatible
              model: null
              api_key_env: CODEWITNESS_LLM_API_KEY
              max_calls: 0

            report:
              html: true
              json: true

            sandbox:
              mode: local-process    # local-process | docker
              image: eclipse-temurin:25-jdk
              allow_network: false

            # Scenarios are how CodeWitness executes a requirement against your code. The setup,
            # calls and invariant are Java fragments written by you; CodeWitness supplies boundary
            # enumeration, seeded random search, execution and reproducible counterexamples.
            #
            # scenarios:
            #   - id: SC-001
            #     requirement: R-001
            #     description: Withdrawals may not exceed 1000 in a rolling 24 hour window
            #     mode: sequence          # single | sequence | concurrent
            #     setup: "com.example.Ledger ledger = new com.example.Ledger();"
            #     steps:
            #       - call: "ledger.withdraw(java.time.Instant.ofEpochMilli(${t}), ${amount})"
            #         inputs:
            #           - name: t
            #             type: java.time.Instant
            #             domain: instantMillis
            #             values: ["0", "86399999", "86400000", "172800000"]
            #             range: {min: 0, max: 172800000}
            #           - name: amount
            #             type: java.math.BigDecimal
            #             domain: decimal
            #             values: ["999.99", "1000", "1000.01", "700"]
            #             range: {min: 100, max: 1500, scale: 2}
            #     observation: "ledger.totalWithinLast24Hours(java.time.Instant.ofEpochMilli(${t}))"
            #     invariant: "observation.compareTo(new java.math.BigDecimal(\\"1000\\")) <= 0"
            #     cases: 400
            #     seed: 42
            """;

    private static final String REQUIREMENTS_TEMPLATE = """
            # Requirements

            Each requirement has a stable identifier in its heading and one paragraph of natural
            language. The text is authoritative: CodeWitness may derive structured properties from it,
            but those derivations are labelled as interpretations in every report.

            # R-001 Withdrawal limit

            A customer may withdraw at most 1,000 during any rolling 24-hour period.
            A withdrawal that would exceed the limit must be rejected.
            The system must behave correctly when multiple withdrawal requests occur concurrently.

            # R-002 Negative amounts

            A withdrawal of a negative amount must be rejected.

            # R-003 Rolling window

            The limit applies to a rolling 24-hour window, not to a calendar day. A withdrawal at
            23:30 followed by a withdrawal at 00:30 the next day must respect the same limit.
            """;
}

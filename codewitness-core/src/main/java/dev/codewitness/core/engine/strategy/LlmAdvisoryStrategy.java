package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.llm.AdversarialPrompts;
import dev.codewitness.core.llm.LlmRequest;
import dev.codewitness.core.llm.LlmResponse;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationStrategy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks a language model to propose adversarial test ideas.
 *
 * <p>What this strategy deliberately does <em>not</em> do is decide anything. Its output is stored as
 * {@code LLM_ANALYSIS} evidence with {@code ADVISORY} confidence,
 * {@code generatedByLlm = true} and {@code executedIndependently = false}: a suggestion, not a
 * result. It creates no obligations and can therefore never move a requirement to
 * {@code VERIFIED} or {@code FAILED}; turning a suggestion into a counterexample requires an
 * executable test, which CodeWitness only counts once it has run it.
 */
public final class LlmAdvisoryStrategy implements VerificationStrategy {

    @Override
    public String id() {
        return "llm-advisory";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return true;
    }

    @Override
    public StrategyOutcome verify(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        List<Evidence> evidence = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (!context.config().llm().enabled()) {
            return StrategyOutcome.empty(id(), requirement.id());
        }
        if (!context.llm().available()) {
            notes.add("LLM assistance is enabled but no provider is available (" + context.llm().model() + ").");
            return new StrategyOutcome(id(), requirement.id(), evidence, List.of(), List.of(), notes);
        }
        if (!context.budget().canCallLlm()) {
            notes.add("The LLM call budget is exhausted; no adversarial suggestions were requested for "
                    + requirement.id() + ".");
            return new StrategyOutcome(id(), requirement.id(), evidence, List.of(), List.of(), notes);
        }

        String source = readSources(context, requirement);
        var prompt = AdversarialPrompts.adversarialTestIdeas(requirement, source,
                context.state().existingTests().failures(),
                context.state().mutation());
        context.budget().recordLlmCall();
        LlmResponse response = context.llm().complete(new LlmRequest(prompt, context.config().llm().model(),
                context.config().llm().temperature(), 2048, context.config().llm().timeout()));

        if (!response.available()) {
            notes.add("The LLM provider failed for " + requirement.id() + ": " + response.error());
            Evidence failure = context.evidence().newEvidence(EvidenceType.LLM_ANALYSIS)
                    .description("LLM assistance was attempted for " + requirement.id() + " and failed")
                    .source(id())
                    .confidence(EvidenceConfidence.ADVISORY)
                    .status(EvidenceStatus.INCONCLUSIVE)
                    .generatedByLlm(true)
                    .executedIndependently(false)
                    .result(String.valueOf(response.error()))
                    .requirement(requirement.id())
                    .build();
            context.evidence().add(failure);
            evidence.add(failure);
            return new StrategyOutcome(id(), requirement.id(), evidence, List.of(), List.of(), notes);
        }

        Evidence suggestion = context.evidence().newEvidence(EvidenceType.LLM_ANALYSIS)
                .description("Adversarial test suggestions for " + requirement.id()
                        + " produced by " + response.model() + " (SUGGESTION - not executed, not evidence)")
                .source(id())
                .confidence(EvidenceConfidence.ADVISORY)
                .status(EvidenceStatus.INCONCLUSIVE)
                .generatedByLlm(true)
                .executedIndependently(false)
                .output(response.text())
                .result("suggestion only")
                .requirement(requirement.id())
                .metadata("model", response.model())
                .metadata("advisory", true)
                .build();
        context.evidence().add(suggestion);
        evidence.add(suggestion);

        Path artifact = context.artifacts().write(
                "llm/" + requirement.id() + "-adversarial-suggestions.txt",
                response.text() + "\n\n--- prompt (untrusted sections fenced) ---\n" + prompt.render());
        notes.add("LLM suggestions for " + requirement.id() + " were recorded as UNEXECUTED suggestions ("
                + artifact.getFileName() + "). They are not evidence of correctness and are not counted "
                + "in any verdict.");
        return new StrategyOutcome(id(), requirement.id(), evidence, List.of(), List.of(), notes);
    }

    private static String readSources(VerificationContext context, Requirement requirement) {
        StringBuilder sb = new StringBuilder();
        List<Path> roots = new ArrayList<>(context.projectInfo().sourceDirs());
        int budget = 60_000;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(context.projectInfo()::isSourceFile).limit(200).toList()) {
                    if (budget <= 0) {
                        break;
                    }
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    String block = "// === " + root.relativize(file) + " ===\n" + content + "\n";
                    sb.append(block);
                    budget -= block.length();
                }
            } catch (Exception _) {
                // source collection is best effort; the prompt simply carries less context
            }
        }
        return sb.toString();
    }
}

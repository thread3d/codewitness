package dev.codewitness.core.result;

import java.time.Instant;

/**
 * Everything needed to reproduce a verification run later.
 *
 * <p>Recorded with every report: without this, a verification result is an unfalsifiable claim.
 */
public record EnvironmentSnapshot(
        String codewitnessVersion,
        String javaVersion,
        String javaVendor,
        String operatingSystem,
        String buildTool,
        String buildToolVersion,
        String sourceRevision,
        String requirementsHash,
        String configurationHash,
        String llmModel,
        String isolationLevel,
        String sandboxDescription,
        String projectPath,
        Instant startedAt,
        Instant finishedAt) {

    public EnvironmentSnapshot {
        codewitnessVersion = value(codewitnessVersion);
        javaVersion = value(javaVersion);
        javaVendor = value(javaVendor);
        operatingSystem = value(operatingSystem);
        buildTool = value(buildTool);
        buildToolVersion = value(buildToolVersion);
        sourceRevision = value(sourceRevision);
        requirementsHash = value(requirementsHash);
        configurationHash = value(configurationHash);
        llmModel = value(llmModel);
        isolationLevel = value(isolationLevel);
        sandboxDescription = value(sandboxDescription);
        projectPath = value(projectPath);
    }

    private static String value(String input) {
        return input == null || input.isBlank() ? "unknown" : input;
    }
}

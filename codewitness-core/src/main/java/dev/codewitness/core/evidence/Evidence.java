package dev.codewitness.core.evidence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single, reproducible observation produced by a verification run.
 *
 * <p>Evidence is the atom of the whole system: every conclusion in a report must point at evidence
 * ids, and every piece of evidence records where it came from, the exact command that produced it,
 * the inputs it used and what was observed.
 */
public final class Evidence {

    private final String id;
    private final String runId;
    private final EvidenceType type;
    private final EvidenceStatus status;
    private final EvidenceConfidence confidence;
    private final String description;
    private final String source;
    private final Instant timestamp;
    private final List<String> command;
    private final Map<String, Object> inputs;
    private final String output;
    private final String result;
    private final Map<String, Object> metadata;
    private final List<Path> artifacts;
    private final List<String> requirementIds;
    private final List<String> obligationIds;
    private final boolean generatedByLlm;
    private final boolean executedIndependently;
    private final boolean reproducible;
    private final String reproductionCommand;

    private Evidence(Builder builder) {
        this.id = builder.id;
        this.runId = builder.runId;
        this.type = builder.type;
        this.status = builder.status;
        this.confidence = builder.confidence;
        this.description = builder.description;
        this.source = builder.source;
        this.timestamp = builder.timestamp;
        this.command = List.copyOf(builder.command);
        this.inputs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(builder.inputs));
        this.output = builder.output;
        this.result = builder.result;
        this.metadata = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        this.artifacts = List.copyOf(builder.artifacts);
        this.requirementIds = List.copyOf(builder.requirementIds);
        this.obligationIds = List.copyOf(builder.obligationIds);
        this.generatedByLlm = builder.generatedByLlm;
        this.executedIndependently = builder.executedIndependently;
        this.reproducible = builder.reproducible;
        this.reproductionCommand = builder.reproductionCommand;
    }

    public static Builder builder(String id, EvidenceType type) {
        return new Builder(id, type);
    }

    public String id() {
        return id;
    }

    public String runId() {
        return runId;
    }

    public EvidenceType type() {
        return type;
    }

    public EvidenceStatus status() {
        return status;
    }

    public EvidenceConfidence confidence() {
        return confidence;
    }

    public String description() {
        return description;
    }

    public String source() {
        return source;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public List<String> command() {
        return command;
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    public String output() {
        return output;
    }

    public String result() {
        return result;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public List<Path> artifacts() {
        return artifacts;
    }

    public List<String> requirementIds() {
        return requirementIds;
    }

    public List<String> obligationIds() {
        return obligationIds;
    }

    public boolean generatedByLlm() {
        return generatedByLlm;
    }

    public boolean executedIndependently() {
        return executedIndependently;
    }

    public boolean reproducible() {
        return reproducible;
    }

    public String reproductionCommand() {
        return reproductionCommand;
    }

    /**
     * Evidence qualifies as proof only when it is deterministic and was actually produced by
     * execution rather than asserted by a model.
     */
    public boolean qualifiesAsDeterministicProof() {
        return confidence.isDeterministic() && (!generatedByLlm || executedIndependently);
    }

    public Evidence withRequirement(String requirementId) {
        Builder builder = new Builder(id, type);
        copyInto(builder);
        if (!builder.requirementIds.contains(requirementId)) {
            builder.requirementIds.add(requirementId);
        }
        return builder.build();
    }

    public Evidence withObligation(String obligationId) {
        Builder builder = new Builder(id, type);
        copyInto(builder);
        if (!builder.obligationIds.contains(obligationId)) {
            builder.obligationIds.add(obligationId);
        }
        return builder.build();
    }

    private void copyInto(Builder builder) {
        builder.id = id;
        builder.runId = runId;
        builder.type = type;
        builder.status = status;
        builder.confidence = confidence;
        builder.description = description;
        builder.source = source;
        builder.timestamp = timestamp;
        builder.command = new ArrayList<>(command);
        builder.inputs = new LinkedHashMap<>(inputs);
        builder.output = output;
        builder.result = result;
        builder.metadata = new LinkedHashMap<>(metadata);
        builder.artifacts = new ArrayList<>(artifacts);
        builder.requirementIds = new ArrayList<>(requirementIds);
        builder.obligationIds = new ArrayList<>(obligationIds);
        builder.generatedByLlm = generatedByLlm;
        builder.executedIndependently = executedIndependently;
        builder.reproducible = reproducible;
        builder.reproductionCommand = reproductionCommand;
    }

    @Override
    public String toString() {
        return id + " [" + type + "/" + status + "/" + confidence + "] " + description;
    }

    public static final class Builder {
        private String id;
        private String runId;
        private EvidenceType type;
        private EvidenceStatus status = EvidenceStatus.INCONCLUSIVE;
        private EvidenceConfidence confidence = EvidenceConfidence.MEDIUM;
        private String description = "";
        private String source = "codewitness";
        private Instant timestamp = Instant.now();
        private List<String> command = new ArrayList<>();
        private Map<String, Object> inputs = new LinkedHashMap<>();
        private String output = "";
        private String result = "";
        private Map<String, Object> metadata = new LinkedHashMap<>();
        private List<Path> artifacts = new ArrayList<>();
        private List<String> requirementIds = new ArrayList<>();
        private List<String> obligationIds = new ArrayList<>();
        private boolean generatedByLlm;
        private boolean executedIndependently = true;
        private boolean reproducible = true;
        private String reproductionCommand;

        private Builder(String id, EvidenceType type) {
            this.id = Objects.requireNonNull(id, "id");
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder status(EvidenceStatus value) {
            this.status = Objects.requireNonNull(value);
            return this;
        }

        public Builder confidence(EvidenceConfidence value) {
            this.confidence = Objects.requireNonNull(value);
            return this;
        }

        public Builder description(String value) {
            this.description = Objects.requireNonNull(value);
            return this;
        }

        public Builder source(String value) {
            this.source = value;
            return this;
        }

        public Builder timestamp(Instant value) {
            this.timestamp = value;
            return this;
        }

        public Builder command(List<String> value) {
            this.command = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder input(String key, Object value) {
            this.inputs.put(key, value);
            return this;
        }

        public Builder inputs(Map<String, Object> value) {
            this.inputs = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }

        public Builder output(String value) {
            this.output = value == null ? "" : value;
            return this;
        }

        public Builder result(String value) {
            this.result = value == null ? "" : value;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            this.metadata = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }

        public Builder artifact(Path value) {
            this.artifacts.add(value);
            return this;
        }

        public Builder artifacts(List<Path> value) {
            this.artifacts = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder requirement(String requirementId) {
            this.requirementIds.add(requirementId);
            return this;
        }

        public Builder requirements(List<String> value) {
            this.requirementIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder obligation(String obligationId) {
            this.obligationIds.add(obligationId);
            return this;
        }

        public Builder obligations(List<String> value) {
            this.obligationIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder generatedByLlm(boolean value) {
            this.generatedByLlm = value;
            return this;
        }

        public Builder executedIndependently(boolean value) {
            this.executedIndependently = value;
            return this;
        }

        public Builder reproducible(boolean value) {
            this.reproducible = value;
            return this;
        }

        public Builder reproductionCommand(String value) {
            this.reproductionCommand = value;
            return this;
        }

        public Evidence build() {
            return new Evidence(this);
        }
    }
}

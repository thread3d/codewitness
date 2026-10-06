package dev.codewitness.core.requirements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A requirement under verification.
 *
 * <p>The natural language text in {@link #description()} is authoritative. {@link #properties()} and
 * {@link #obligations()} are interpretations produced by CodeWitness (or by a language model) and
 * are reported as such.
 */
public final class Requirement {

    private final String id;
    private final String title;
    private final String description;
    private final String sourceFile;
    private final int sourceLine;
    private final List<RequirementProperty> properties;
    private final List<VerificationObligation> obligations;
    private final Map<String, String> attributes;

    private Requirement(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.description = builder.description;
        this.sourceFile = builder.sourceFile;
        this.sourceLine = builder.sourceLine;
        this.properties = List.copyOf(builder.properties);
        this.obligations = List.copyOf(builder.obligations);
        this.attributes = Map.copyOf(new LinkedHashMap<>(builder.attributes));
    }

    public static Builder builder(String id, String description) {
        return new Builder(id, description);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title == null ? id : title;
    }

    /** The authoritative natural language statement of the requirement. */
    public String description() {
        return description;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public int sourceLine() {
        return sourceLine;
    }

    /** Structured interpretation of the requirement. Always presented as an interpretation. */
    public List<RequirementProperty> properties() {
        return properties;
    }

    public List<VerificationObligation> obligations() {
        return obligations;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public Requirement withObligations(List<VerificationObligation> newObligations) {
        Builder builder = copy();
        builder.obligations = new ArrayList<>(newObligations);
        return builder.build();
    }

    public Requirement withProperty(RequirementProperty property) {
        Builder builder = copy();
        builder.properties.add(property);
        return builder.build();
    }

    private Builder copy() {
        Builder builder = new Builder(id, description);
        builder.title = title;
        builder.sourceFile = sourceFile;
        builder.sourceLine = sourceLine;
        builder.properties = new ArrayList<>(properties);
        builder.obligations = new ArrayList<>(obligations);
        builder.attributes = new LinkedHashMap<>(attributes);
        return builder;
    }

    /** SHA-256 hash of the authoritative requirement text, used for reproducibility records. */
    public String contentHash() {
        return dev.codewitness.core.util.Hashing.sha256(id + "\n" + description);
    }

    @Override
    public String toString() {
        return id + ": " + title;
    }

    public static final class Builder {
        private final String id;
        private final String description;
        private String title;
        private String sourceFile;
        private int sourceLine = -1;
        private List<RequirementProperty> properties = new ArrayList<>();
        private List<VerificationObligation> obligations = new ArrayList<>();
        private Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(String id, String description) {
            this.id = Objects.requireNonNull(id, "id");
            this.description = Objects.requireNonNull(description, "description");
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder source(String file, int line) {
            this.sourceFile = file;
            this.sourceLine = line;
            return this;
        }

        public Builder properties(List<RequirementProperty> value) {
            this.properties = new ArrayList<>(value);
            return this;
        }

        public Builder property(RequirementProperty value) {
            this.properties.add(value);
            return this;
        }

        public Builder obligations(List<VerificationObligation> value) {
            this.obligations = new ArrayList<>(value);
            return this;
        }

        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public Requirement build() {
            return new Requirement(this);
        }
    }
}

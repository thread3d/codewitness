package dev.codewitness.core.requirements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** An ordered, immutable collection of requirements with id lookup. */
public final class RequirementSet {

    private final List<Requirement> requirements;
    private final Map<String, Requirement> byId;

    public RequirementSet(List<Requirement> requirements) {
        this.requirements = List.copyOf(requirements);
        Map<String, Requirement> map = new LinkedHashMap<>();
        for (Requirement requirement : this.requirements) {
            map.put(requirement.id(), requirement);
        }
        this.byId = Map.copyOf(map);
    }

    public static RequirementSet empty() {
        return new RequirementSet(List.of());
    }

    public List<Requirement> requirements() {
        return requirements;
    }

    public boolean isEmpty() {
        return requirements.isEmpty();
    }

    public int size() {
        return requirements.size();
    }

    public Optional<Requirement> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Requirement require(String id) {
        Requirement requirement = byId.get(id);
        if (requirement == null) {
            throw new IllegalArgumentException("unknown requirement: " + id);
        }
        return requirement;
    }

    public List<String> ids() {
        return requirements.stream().map(Requirement::id).collect(Collectors.toList());
    }

    public List<VerificationObligation> obligations() {
        List<VerificationObligation> all = new ArrayList<>();
        for (Requirement requirement : requirements) {
            all.addAll(requirement.obligations());
        }
        return List.copyOf(all);
    }

    /** Combined hash of every requirement's content, recorded with each run for reproducibility. */
    public String contentHash() {
        StringBuilder sb = new StringBuilder();
        for (Requirement requirement : requirements) {
            sb.append(requirement.contentHash()).append('\n');
        }
        return dev.codewitness.core.util.Hashing.sha256(sb.toString());
    }

    public RequirementSet with(Requirement requirement) {
        List<Requirement> updated = new ArrayList<>(requirements);
        updated.removeIf(r -> r.id().equals(requirement.id()));
        updated.add(requirement);
        return new RequirementSet(updated);
    }
}

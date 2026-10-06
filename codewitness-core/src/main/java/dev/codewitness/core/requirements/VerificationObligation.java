package dev.codewitness.core.requirements;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A verification obligation: a concrete thing that must be demonstrated in order to say something
 * about a requirement.
 *
 * <p>Obligations are the step between a claim and an experiment. They are deliberately
 * value-objects; the outcome of running them is recorded separately as evidence so that an
 * obligation can be attempted several times with different strategies.
 *
 * @param id               obligation identifier, for example {@code OB-003}
 * @param requirementId    owning requirement
 * @param description      what must be demonstrated
 * @param kind             kind of experiment that discharges it
 * @param origin           where the obligation came from
 * @param target           optional target of the experiment (class, method or entrypoint id)
 * @param parameters       parameters or data used by the generators
 * @param advisoryOnly     {@code true} when the obligation came from a language model and therefore
 *                         cannot by itself justify a {@code VERIFIED} verdict
 */
public record VerificationObligation(
        String id,
        String requirementId,
        String description,
        ObligationKind kind,
        ObligationOrigin origin,
        String target,
        Map<String, Object> parameters,
        boolean advisoryOnly) {

    public VerificationObligation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(origin, "origin");
        parameters = Map.copyOf(new LinkedHashMap<>(parameters == null ? Map.of() : parameters));
    }

    public static VerificationObligation of(
            String id, String requirementId, String description, ObligationKind kind, ObligationOrigin origin) {
        return new VerificationObligation(id, requirementId, description, kind, origin, null, Map.of(), false);
    }

    public VerificationObligation withTarget(String newTarget) {
        return new VerificationObligation(id, requirementId, description, kind, origin, newTarget,
                parameters, advisoryOnly);
    }

    public VerificationObligation withParameters(Map<String, Object> newParameters) {
        return new VerificationObligation(id, requirementId, description, kind, origin, target,
                newParameters, advisoryOnly);
    }

    public String parameter(String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

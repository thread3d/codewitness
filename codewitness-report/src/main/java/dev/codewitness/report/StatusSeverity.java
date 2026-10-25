package dev.codewitness.report;

import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.RequirementVerdict;

/**
 * Orders requirement verdicts by how bad their outcome is, so a report can collapse duplicates safely.
 *
 * <p>A requirements document may name the same id twice - a repeated heading is the usual way - and the
 * engine then produces one verdict per occurrence. Both writers key verdicts by requirement id, and a
 * plain {@code put} keeps whichever verdict happened to come last. That made it possible for a report to
 * count a {@code FAILED} verdict in its headline while the requirement card showed a later
 * {@code VERIFIED} one, which is the worst failure mode an evidence report has: disagreeing with itself
 * about the least favourable fact it knows.
 *
 * <p>Collapsing to the most severe verdict fixes the disagreement in the conservative direction. The
 * ordering below is fixed rather than derived from {@link RequirementStatus} declaration order, because
 * declaration order is an implementation detail of the enum and severity is a product decision:
 * {@code FAILED} (an observed violation) outranks {@code BLOCKED} (verification did not happen), which
 * outranks {@code UNVERIFIED} (nothing established), which outranks the two passing states.
 */
final class StatusSeverity {

    private StatusSeverity() {
    }

    /**
     * @param status status to rank, may be {@code null}
     * @return rank where a larger number is a worse outcome
     */
    static int rank(RequirementStatus status) {
        RequirementStatus effective = status != null ? status : RequirementStatus.UNVERIFIED;
        return switch (effective) {
            case VERIFIED -> 0;
            case PARTIALLY_VERIFIED -> 1;
            case UNVERIFIED -> 2;
            case BLOCKED -> 3;
            case FAILED -> 4;
        };
    }

    /**
     * @param left  first verdict, never {@code null}
     * @param right second verdict, never {@code null}
     * @return whichever verdict reports the worse status, preferring {@code right} on a tie so the most
     *     recently recorded verdict wins when neither is worse
     */
    static RequirementVerdict moreSevere(RequirementVerdict left, RequirementVerdict right) {
        return rank(right.status()) >= rank(left.status()) ? right : left;
    }
}

package dev.codewitness.support.generate;

import dev.codewitness.core.config.Scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The boundary-case enumeration every adapter's generated tests share.
 *
 * <p>A scenario declares, for each input, the boundary values worth trying. The case set is the cartesian
 * product of those declarations, truncated so that a scenario with many inputs does not generate hundreds of
 * test cases. This was written out fourteen times - once per adapter, plus once inside this module - and the
 * copies had drifted into three different truncation strategies:
 *
 * <ul>
 *   <li>this one caps the working set while it expands and again at the end;</li>
 *   <li>one copy capped only at the end, which is the same result for ordinary scenarios and an unbounded
 *       intermediate list for a pathological one;</li>
 *   <li>two copies capped at every stage, which starves the inputs after the first: with four inputs and four
 *       values each, the cap is already exhausted while expanding the second input, so the third and fourth
 *       never vary at all. A scenario written to exercise a combination was silently only exercising its
 *       first two inputs.</li>
 * </ul>
 *
 * <p>That last difference is why this is one implementation rather than a shared helper of convenience: the
 * enumeration decides <em>which experiments run</em>, so two answers to it are two different verification
 * results for the same scenario.
 *
 * <p>The enumeration is deliberately conservative in two ways. An input that declares no boundary values
 * suppresses the whole set, because a case that omits one of the scenario's inputs would not be the scenario.
 * And the case order follows the declared input order, so a report's case numbers stay stable between runs.
 *
 * <p><strong>Known limitation, stated rather than hidden.</strong> Truncating a breadth-first expansion keeps
 * its head, and the head is dominated by the earliest-declared inputs held at their first value: with four
 * inputs of six values each, the twelve kept cases vary the third and fourth inputs while the first two never
 * move. Every case is a complete, valid case - nothing is mis-generated - but a scenario that expects all of
 * its inputs to be exercised does not get that once the product exceeds the cap. Sampling the product so that
 * every input varies (one-at-a-time variation plus pairwise combinations, which is the usual reading of
 * "boundary values") is a change to which experiments run, so it belongs in its own release with the fixtures
 * re-baselined rather than smuggled in with an extraction. It is recorded in the backlog for that reason.
 */
public final class ScenarioCases {

    /** Most boundary cases one scenario may generate. */
    public static final int MAX_BOUNDARY_CASES = 12;

    private ScenarioCases() {
    }

    /**
     * Enumerates the boundary cases of a scenario's inputs.
     *
     * @param inputs declared inputs, in scenario order
     * @return one map per case, from input name to the value to use; empty when there is nothing to enumerate
     */
    public static List<Map<String, String>> boundaryCases(List<Scenario.Input> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        for (Scenario.Input input : inputs) {
            if (input.boundaryValues() == null || input.boundaryValues().isEmpty()) {
                return List.of();
            }
        }
        List<Map<String, String>> accumulation = new ArrayList<>();
        accumulation.add(new LinkedHashMap<>());
        for (Scenario.Input input : inputs) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : accumulation) {
                for (String value : input.boundaryValues()) {
                    Map<String, String> copy = new LinkedHashMap<>(partial);
                    copy.put(input.name(), value);
                    next.add(copy);
                    // The working set is bounded as it grows, not only at the end: a scenario with eight inputs
                    // and six values each would otherwise build 1.6 million maps to return twelve of them.
                    if (next.size() >= MAX_BOUNDARY_CASES * 4) {
                        break;
                    }
                }
            }
            accumulation = next;
        }
        List<Map<String, String>> cases = new ArrayList<>();
        for (Map<String, String> values : accumulation) {
            cases.add(values);
            if (cases.size() >= MAX_BOUNDARY_CASES) {
                break;
            }
        }
        return List.copyOf(cases);
    }
}

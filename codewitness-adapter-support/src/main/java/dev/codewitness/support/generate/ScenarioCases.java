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
        List<Map<String, String>> cases = new ArrayList<>();

        // 1. The baseline: every input at its first declared value. A case that omits an input would not be the
        // scenario, so this also guarantees that every case built afterwards is complete.
        cases.add(atFirstValue(inputs, Map.of()));

        // 2. Variation across the inputs in rounds: each round gives every input its next declared value, with the
        // others at their first. Round-robin by value rather than by input is the whole point — an enumeration that
        // gives one input all of its values first spends the budget before the later inputs move at all, so a
        // scenario written to exercise a combination only ever exercised its first input.
        int deepest = inputs.stream().mapToInt(input -> input.boundaryValues().size()).max().orElse(1);
        for (int valueIndex = 1; valueIndex < deepest && cases.size() < MAX_BOUNDARY_CASES; valueIndex++) {
            for (Scenario.Input input : inputs) {
                if (valueIndex < input.boundaryValues().size()) {
                    cases.add(atFirstValue(inputs, Map.of(input.name(), input.boundaryValues().get(valueIndex))));
                    if (cases.size() >= MAX_BOUNDARY_CASES) {
                        break;
                    }
                }
            }
        }

        // 3. If budget remains — few inputs, few values — spend it on pairs, because those are the combinations a
        // scenario is usually written to catch: two inputs varying together is where an interaction shows up.
        for (int i = 0; i < inputs.size() && cases.size() < MAX_BOUNDARY_CASES; i++) {
            for (int j = i + 1; j < inputs.size() && cases.size() < MAX_BOUNDARY_CASES; j++) {
                List<String> left = inputs.get(i).boundaryValues();
                List<String> right = inputs.get(j).boundaryValues();
                for (int a = 1; a < left.size() && cases.size() < MAX_BOUNDARY_CASES; a++) {
                    for (int b = 1; b < right.size() && cases.size() < MAX_BOUNDARY_CASES; b++) {
                        cases.add(atFirstValue(inputs, Map.of(
                                inputs.get(i).name(), left.get(a),
                                inputs.get(j).name(), right.get(b))));
                    }
                }
            }
        }
        return List.copyOf(cases);
    }

    /**
     * Builds one complete case: every input at its first declared value, except those named in {@code overrides}.
     *
     * @param inputs    declared inputs, in scenario order
     * @param overrides values to use instead of the first
     * @return one case, with its inputs in declaration order
     */
    private static Map<String, String> atFirstValue(List<Scenario.Input> inputs, Map<String, String> overrides) {
        Map<String, String> aCase = new LinkedHashMap<>();
        for (Scenario.Input input : inputs) {
            aCase.put(input.name(), overrides.getOrDefault(input.name(), input.boundaryValues().getFirst()));
        }
        return aCase;
    }
}

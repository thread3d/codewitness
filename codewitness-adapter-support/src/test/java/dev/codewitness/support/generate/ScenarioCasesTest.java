package dev.codewitness.support.generate;

import dev.codewitness.core.config.Scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundary enumeration decides which experiments run, so these tests pin what a scenario with several
 * inputs actually executes - including the property that two of the fourteen copies this replaced got wrong.
 */
class ScenarioCasesTest {

    private static Scenario.Input input(String name, String... values) {
        return new Scenario.Input(name, "int", Scenario.Domain.INTEGER, List.of(values), null, null, 0);
    }

    @Test
    @DisplayName("no inputs, or an input with no boundary values, produces no cases")
    void nothingToEnumerate() {
        assertTrue(ScenarioCases.boundaryCases(List.of()).isEmpty());
        assertTrue(ScenarioCases.boundaryCases(List.of(input("a"))).isEmpty(),
                "a case that omits one of the scenario's inputs is not the scenario");
    }

    @Test
    @DisplayName("one input produces one case per declared value")
    void oneInput() {
        List<Map<String, String>> cases = ScenarioCases.boundaryCases(List.of(input("a", "1", "2", "3")));

        assertEquals(List.of(Map.of("a", "1"), Map.of("a", "2"), Map.of("a", "3")), cases);
    }

    @Test
    @DisplayName("the product is capped, and every case carries every input")
    void theProductIsCappedAndComplete() {
        List<Map<String, String>> cases = ScenarioCases.boundaryCases(
                List.of(input("a", "1", "2", "3", "4"), input("b", "5", "6", "7", "8")));

        assertEquals(ScenarioCases.MAX_BOUNDARY_CASES, cases.size());
        for (Map<String, String> aCase : cases) {
            assertTrue(aCase.containsKey("a") && aCase.containsKey("b"), aCase.toString());
        }
    }

    @Test
    @DisplayName("every input varies when the budget allows, however many inputs there are")
    void everyInputVaries() {
        // This is the property the enumeration exists for. A breadth-first product truncated at its head pins the
        // earliest-declared inputs at their first value, so a scenario written to exercise four inputs only ever
        // moved two of them - and nothing in the report said so.
        for (int inputCount = 1; inputCount <= 8; inputCount++) {
            List<Scenario.Input> inputs = new ArrayList<>();
            for (int i = 0; i < inputCount; i++) {
                inputs.add(input("i" + i, "1", "2", "3", "4", "5", "6"));
            }
            List<Map<String, String>> cases = ScenarioCases.boundaryCases(inputs);

            // Not necessarily the full budget: one input with six values needs six cases and no more. What matters
            // is that every input varies, which is what the loop below asserts.
            assertTrue(cases.size() <= ScenarioCases.MAX_BOUNDARY_CASES, "inputCount=" + inputCount);
            assertTrue(cases.size() >= inputCount + 1,
                    "a baseline plus at least one variation per input: " + cases.size());
            for (int i = 0; i < inputCount; i++) {
                String name = "i" + i;
                long distinct = cases.stream().map(aCase -> aCase.get(name)).distinct().count();
                assertTrue(distinct > 1, "input " + name + " never varied with " + inputCount
                        + " inputs: " + cases);
                for (Map<String, String> aCase : cases) {
                    assertEquals(inputCount, aCase.size(), "every case names every input: " + aCase);
                }
            }
        }
    }

    @Test
    @DisplayName("the first case is the baseline: every input at its first declared value")
    void theFirstCaseIsTheBaseline() {
        List<Scenario.Input> inputs = List.of(input("a", "10", "20"), input("b", "30", "40"));

        Map<String, String> baseline = ScenarioCases.boundaryCases(inputs).getFirst();

        assertEquals(Map.of("a", "10", "b", "30"), baseline,
                "a reader can tell what the run varied from what without reading every case");
    }

    @Test
    @DisplayName("one input's variation does not disturb the others")
    void oneAtATimeVariation() {
        List<Scenario.Input> inputs = List.of(input("a", "1", "2"), input("b", "3", "4"));

        List<Map<String, String>> cases = ScenarioCases.boundaryCases(inputs);

        assertTrue(cases.contains(Map.of("a", "2", "b", "3")), "a varies alone: " + cases);
        assertTrue(cases.contains(Map.of("a", "1", "b", "4")), "and so does b: " + cases);
        assertTrue(cases.contains(Map.of("a", "2", "b", "4")), "with the pair last, once the budget allows: " + cases);
    }

    @Test
    @DisplayName("a pathological scenario does not build the whole product")
    void workIsBoundedWhileExpanding() {
        // Eight inputs of ten values each is a hundred million combinations; the enumeration never materialises them.
        List<Scenario.Input> inputs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            inputs.add(input("i" + i, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        }

        List<Map<String, String>> cases = ScenarioCases.boundaryCases(inputs);

        assertEquals(ScenarioCases.MAX_BOUNDARY_CASES, cases.size(),
                "eight inputs of ten values each is a hundred million combinations, of which twelve are kept");
    }

    @Test
    @DisplayName("case order is stable, and a case lists its inputs in declared order")
    void orderIsStable() {
        List<Map<String, String>> first = ScenarioCases.boundaryCases(
                List.of(input("x", "1", "2"), input("y", "3", "4")));
        List<Map<String, String>> second = ScenarioCases.boundaryCases(
                List.of(input("x", "1", "2"), input("y", "3", "4")));

        assertEquals(first, second);
        assertEquals(List.of("x", "y"), new ArrayList<>(first.get(0).keySet()),
                "a case lists its inputs in the order the scenario declared them");
    }
}

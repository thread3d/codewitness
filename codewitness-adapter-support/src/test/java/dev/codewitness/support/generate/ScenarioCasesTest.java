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
    @DisplayName("when the product is truncated, every case is still complete and the tail inputs vary")
    void truncationKeepsCompleteCases() {
        // This pins the truncation rule as it is, including its limitation: keeping the head of a
        // breadth-first expansion holds the earliest-declared inputs at their first value and varies the later
        // ones. Every case is nonetheless complete and valid, which is the property generation depends on; the
        // sampling that would exercise every input is a separate, larger change (see the class javadoc).
        List<Scenario.Input> inputs = List.of(
                input("a", "1", "2", "3", "4", "5", "6"),
                input("b", "1", "2", "3", "4", "5", "6"),
                input("c", "1", "2", "3", "4", "5", "6"),
                input("d", "1", "2", "3", "4", "5", "6"));

        List<Map<String, String>> cases = ScenarioCases.boundaryCases(inputs);

        assertEquals(ScenarioCases.MAX_BOUNDARY_CASES, cases.size());
        for (Map<String, String> aCase : cases) {
            assertEquals(4, aCase.size(), "every case must name every input: " + aCase);
        }
        long distinctLast = cases.stream().map(aCase -> aCase.get("d")).distinct().count();
        assertTrue(distinctLast > 1, "the last-declared input does vary: " + cases);
    }

    @Test
    @DisplayName("a pathological scenario does not build the whole product")
    void workIsBoundedWhileExpanding() {
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

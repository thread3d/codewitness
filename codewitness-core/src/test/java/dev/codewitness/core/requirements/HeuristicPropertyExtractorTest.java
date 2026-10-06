package dev.codewitness.core.requirements;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heuristics are deliberately small and deterministic. Everything they produce is an
 * interpretation: the sentence stays authoritative and the derived property says so.
 */
class HeuristicPropertyExtractorTest {

    private static Requirement requirement(String text) {
        return Requirement.builder("R-001", text).build();
    }

    private static Requirement enriched(String text) {
        return HeuristicPropertyExtractor.enrich(requirement(text));
    }

    private static List<String> types(Requirement requirement) {
        return requirement.properties().stream().map(RequirementProperty::type).toList();
    }

    @Test
    @DisplayName("'at most EUR 1,000' extracts upper_bound with value 1000")
    void upperBoundIsExtractedWithItsNumericValue() {
        Requirement requirement = enriched(
                "A customer may withdraw at most \u20ac1,000 during any rolling 24-hour period.");

        RequirementProperty property = HeuristicPropertyExtractor.property(requirement, "upper_bound").orElseThrow();
        assertEquals(0, property.decimalValue("value").compareTo(new BigDecimal("1000")));
        assertNotNull(property.rawText(), "the interpretation must say where it came from");
        assertTrue(property.rawText().contains("derived by CodeWitness"), property.rawText());
    }

    @Test
    @DisplayName("the currency symbol is not part of the extracted value")
    void currencySymbolIsIgnored() {
        Requirement requirement = enriched("The transfer limit is a maximum of $2,500.50 per day.");

        BigDecimal value = HeuristicPropertyExtractor.property(requirement, "upper_bound")
                .orElseThrow().decimalValue("value");
        assertEquals(0, value.compareTo(new BigDecimal("2500.50")));
    }

    @Test
    @DisplayName("an upper bound without a number still records the property")
    void upperBoundWithoutNumberIsStillRecorded() {
        Requirement requirement = enriched("Spending must not exceed the configured daily cap.");

        RequirementProperty property = HeuristicPropertyExtractor.property(requirement, "upper_bound").orElseThrow();
        assertNull(property.value("value"));
        assertFalse(ObligationPlanner.hasUsableBound(property));
    }

    @Test
    @DisplayName("'at least' extracts lower_bound")
    void lowerBoundIsExtracted() {
        Requirement requirement = enriched("The balance must be at least 100 at all times.");

        RequirementProperty property = HeuristicPropertyExtractor.property(requirement, "lower_bound").orElseThrow();
        assertEquals(0, property.decimalValue("value").compareTo(new BigDecimal("100")));
    }

    @Test
    @DisplayName("when both bounds appear the upper bound wins, as the code prefers it")
    void upperBoundTakesPrecedenceOverLowerBound() {
        // The extractor uses if/else-if, so a sentence mentioning both yields only upper_bound. Pinned
        // here so a future change to emit both is a conscious decision rather than an accident.
        Requirement requirement = enriched("The amount must be at least 10 and at most 20.");

        assertTrue(types(requirement).contains("upper_bound"), types(requirement).toString());
        assertFalse(types(requirement).contains("lower_bound"), types(requirement).toString());
    }

    @Test
    @DisplayName("'rejected' extracts a rejection property")
    void rejectionIsExtracted() {
        Requirement requirement = enriched("The withdrawal must be rejected when the limit is exceeded.");

        RequirementProperty property = HeuristicPropertyExtractor.property(requirement, "rejection").orElseThrow();
        assertEquals("operation exceeds a stated limit", property.stringValue("condition"));
    }

    @Test
    @DisplayName("concurrency is added when the text mentions concurrent execution")
    void concurrencyIsAddedForConcurrentExecution() {
        Requirement requirement = enriched("Operations may be issued concurrently from several threads.");

        assertTrue(types(requirement).contains("concurrency"), types(requirement).toString());
    }

    @Test
    @DisplayName("thread-safe and parallel wording also extract concurrency")
    void concurrencyIsAddedForThreadSafeWording() {
        Requirement requirement = enriched("The service must be thread-safe under parallel requests.");

        assertTrue(types(requirement).contains("concurrency"), types(requirement).toString());
    }

    @Test
    @DisplayName("null mentions extract null_handling")
    void nullHandlingIsExtracted() {
        Requirement requirement = enriched("A null or missing amount must be handled gracefully.");

        assertTrue(types(requirement).contains("null_handling"), types(requirement).toString());
    }

    @Test
    @DisplayName("idempotent wording extracts determinism")
    void determinismIsExtracted() {
        Requirement requirement = enriched("Repeating the operation must be idempotent.");

        assertTrue(types(requirement).contains("determinism"), types(requirement).toString());
    }

    @Test
    @DisplayName("a rolling window extracts its length and basis")
    void rollingWindowIsExtracted() {
        Requirement requirement = enriched(
                "At most 1000 may leave the account during any rolling 24-hour period.");

        RequirementProperty property = HeuristicPropertyExtractor.property(requirement, "rolling_window").orElseThrow();
        assertEquals("24 hours", property.stringValue("window"));
        assertEquals("rolling", property.stringValue("basis"));
    }

    @Test
    @DisplayName("the rolling window is folded into upper_bound as well")
    void rollingWindowIsFoldedIntoUpperBound() {
        Requirement requirement = enriched(
                "At most 1000 may leave the account during any rolling 24-hour period.");

        RequirementProperty upper = HeuristicPropertyExtractor.property(requirement, "upper_bound").orElseThrow();
        assertEquals("24 hours", upper.stringValue("window"));
    }

    @Test
    @DisplayName("plain prose produces no properties")
    void plainProseProducesNoProperties() {
        Requirement requirement = enriched("The system stores the customer's chosen display name.");

        assertTrue(requirement.properties().isEmpty(), types(requirement).toString());
    }

    @Test
    @DisplayName("rejection and concurrency can both be extracted from one sentence")
    void rejectionAndConcurrencyCanCoexist() {
        Requirement requirement = enriched(
                "Concurrent withdrawals must be rejected when the limit is exceeded.");

        assertTrue(types(requirement).contains("rejection"), types(requirement).toString());
        assertTrue(types(requirement).contains("concurrency"), types(requirement).toString());
    }

    @Test
    @DisplayName("enrich never rewrites the authoritative description")
    void enrichKeepsTheAuthoritativeDescription() {
        String text = "A customer may withdraw at most \u20ac1,000 during any rolling 24-hour period.";
        Requirement requirement = requirement(text);

        assertEquals(text, HeuristicPropertyExtractor.enrich(requirement).description());
    }

    @Test
    @DisplayName("a blank description is returned unchanged")
    void blankDescriptionIsReturnedUnchanged() {
        Requirement requirement = requirement("");

        assertSame(requirement, HeuristicPropertyExtractor.enrich(requirement));
    }

    @Test
    @DisplayName("firstAmount returns the first amount-like number")
    void firstAmountReturnsTheFirstAmount() {
        assertEquals(0, HeuristicPropertyExtractor.firstAmount("between 10 and 20")
                .compareTo(new BigDecimal("10")));
    }

    @Test
    @DisplayName("firstAmount handles a space used as a thousands separator")
    void firstAmountHandlesSpaceSeparator() {
        assertEquals(0, HeuristicPropertyExtractor.firstAmount("up to 1 000 EUR")
                .compareTo(new BigDecimal("1000")));
    }

    @Test
    @DisplayName("firstAmount handles a comma thousands separator")
    void firstAmountHandlesCommaSeparator() {
        assertEquals(0, HeuristicPropertyExtractor.firstAmount("no more than 12,345.67")
                .compareTo(new BigDecimal("12345.67")));
    }

    @Test
    @DisplayName("firstAmount returns null when there is no number")
    void firstAmountReturnsNullWithoutNumbers() {
        assertNull(HeuristicPropertyExtractor.firstAmount("no numbers here"));
    }

    @Test
    @DisplayName("property lookup is case insensitive")
    void propertyLookupIsCaseInsensitive() {
        Requirement requirement = Requirement.builder("R-001", "text")
                .property(RequirementProperty.of("upper_bound", Map.of("value", 5)))
                .build();

        Optional<RequirementProperty> found = HeuristicPropertyExtractor.property(requirement, "UPPER_BOUND");
        assertTrue(found.isPresent());
        assertEquals(0, found.orElseThrow().decimalValue("value").compareTo(new BigDecimal("5")));
    }

    @Test
    @DisplayName("mentions() is case insensitive and null safe")
    void mentionsIsCaseInsensitiveAndNullSafe() {
        assertTrue(HeuristicPropertyExtractor.mentions("This covers r-001 exactly.", "R-001"));
        assertTrue(HeuristicPropertyExtractor.mentions("R-001", "R-001"));
        assertFalse(HeuristicPropertyExtractor.mentions("R-002", "R-001"));
        assertFalse(HeuristicPropertyExtractor.mentions(null, "R-001"));
    }

    @Test
    @DisplayName("describe() flattens properties into a map keyed by type")
    void describeFlattensProperties() {
        Requirement requirement = enriched(
                "A customer may withdraw at most 1000 concurrently from several accounts.");

        Map<String, Object> described = HeuristicPropertyExtractor.describe(requirement.properties());
        assertTrue(described.containsKey("upper_bound"), described.toString());
        assertTrue(described.containsKey("concurrency"), described.toString());
    }

    @Test
    @DisplayName("RequirementProperty decimalValue tolerates strings and null")
    void requirementPropertyDecimalValueToleratesStrings() {
        RequirementProperty fromString = new RequirementProperty("upper_bound", Map.of("value", "1,000"), null);
        assertEquals(0, fromString.decimalValue("value").compareTo(new BigDecimal("1000")));
        assertNull(fromString.decimalValue("missing"));
        assertNull(new RequirementProperty("upper_bound", Map.of("value", "abc"), null).decimalValue("value"));
    }
}

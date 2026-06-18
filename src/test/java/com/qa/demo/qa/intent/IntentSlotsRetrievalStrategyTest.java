package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.RetrievalStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentSlotsRetrievalStrategyTest {

    @Test
    void normalizesLlmAggregateCountStrategy() {
        IntentDecision raw = new IntentDecision(
                "sql", 0.9, "llm", "aggregate", "", java.util.List.of(), "any", null, "aggregate_count");
        IntentDecision normalized = IntentSlots.normalize(raw);
        assertEquals("aggregate_count", normalized.retrievalStrategy());
        assertEquals(RetrievalStrategy.AGGREGATE_COUNT, normalized.resolvedRetrievalStrategy());
    }

    @Test
    void derivesIntentAndQueryTypeFromAggregateCountStrategy() {
        IntentDecision raw = new IntentDecision(
                "", 0.9, "llm", "", "", java.util.List.of(), "any", null, "aggregate_count");
        IntentDecision normalized = IntentSlots.normalize(raw);
        assertEquals("aggregate_count", normalized.retrievalStrategy());
        assertEquals("sql", normalized.intent());
        assertEquals("aggregate", normalized.queryType());
    }

    @Test
    void infersAggregateStrategyFromQueryTypeWhenLlmOmitsField() {
        IntentDecision raw = new IntentDecision(
                "sql", 0.8, "rule", "aggregate", "", java.util.List.of(), "any", null, "");
        IntentDecision normalized = IntentSlots.normalize(raw);
        assertEquals("aggregate_count", normalized.retrievalStrategy());
    }

    @Test
    void mapsAggregateStrategyToInformationNeed() {
        InformationNeed need = InformationNeed.fromRetrievalStrategy(
                RetrievalStrategy.AGGREGATE_COUNT, 0.9, "test");
        assertTrue(need.isAggregate());
        assertEquals("aggregate", need.granularity());
    }
}

package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentSlotsRoleFocusTest {

    @Test
    void preservesRuleInferredRoleFocusToken() {
        IntentDecision raw = new IntentDecision(
                "hybrid", 0.9, "rule", "张三", java.util.List.of(), "legal_rep", null, "graph_relational");
        IntentDecision normalized = IntentSlots.normalize(raw);
        assertEquals("legal_rep", normalized.roleFocus());
    }

    @Test
    void rejectsInvalidRoleFocusAndFallsBackToAny() {
        IntentDecision raw = new IntentDecision(
                "hybrid", 0.9, "llm", "", java.util.List.of(), "法定代表人", null, "graph_relational");
        IntentDecision normalized = IntentSlots.normalize(raw);
        assertEquals("any", normalized.roleFocus());
    }

    @Test
    void normalizeRoleFocus_blankIsAny() {
        assertEquals("any", IntentSlots.normalizeRoleFocus(""));
        assertEquals("any", IntentSlots.normalizeRoleFocus("any"));
        assertEquals("shareholder", IntentSlots.normalizeRoleFocus("Shareholder"));
    }
}

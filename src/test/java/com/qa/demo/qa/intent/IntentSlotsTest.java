package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentSlotsTest {

    @Test
    void sanitizesPersonNameSuffix() {
        assertEquals("戴科彬", IntentSlots.sanitizePersonName("戴科彬是"));
    }

    @Test
    void retrievalReadyForPersonRoleList() {
        IntentDecision d = new IntentDecision(
                "graph", 0.9, "ok", "person_role_list", "戴科彬", List.of(), "legal_rep"
        );
        assertTrue(IntentSlots.isRetrievalReady(d));
    }

    @Test
    void notReadyWhenRoleFocusAnyOnPersonRoleList() {
        IntentDecision d = new IntentDecision(
                "graph", 0.9, "ok", "person_role_list", "戴科彬", List.of(), "any"
        );
        assertFalse(IntentSlots.isRetrievalReady(d));
    }
}

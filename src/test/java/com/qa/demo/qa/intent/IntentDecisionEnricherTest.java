package com.qa.demo.qa.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.EnterpriseLexicon;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentDecisionEnricherTest {

    private IntentDecisionEnricher enricher;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        EnterpriseLexicon lexicon = EnterpriseLexicon.loadDefault(mapper);
        QuestionEntityExtractor extractor = new QuestionEntityExtractor(lexicon);
        QaAssistantProperties props = new QaAssistantProperties();
        props.setIntentLlmEnrichMinConfidence(0.72);
        enricher = new IntentDecisionEnricher(props, extractor);
    }

    @Test
    void skipsRuleEnrichWhenLlmSlotsCompleteAndConfident() {
        IntentDecision llm = new IntentDecision(
                "graph",
                0.9,
                "person_role_list",
                "person_role_list",
                "戴科彬",
                List.of(),
                "legal_rep"
        );
        IntentDecision out = enricher.enrich(llm, "戴科彬是哪些主体的法人", false, "llm");
        assertEquals("戴科彬", out.personName());
        assertEquals("legal_rep", out.roleFocus());
        assertEquals("person_role_list", out.queryType());
        assertTrue(out.reason().startsWith("llm_"));
    }

    @Test
    void fillsMissingPersonWhenLlmIncomplete() {
        IntentDecision llm = new IntentDecision(
                "graph",
                0.9,
                "partial",
                "person_role_list",
                "",
                List.of(),
                "legal_rep"
        );
        IntentDecision out = enricher.enrich(llm, "戴科彬是哪些主体的法人", false, "llm");
        assertEquals("戴科彬", out.personName());
    }

    @Test
    void ruleSourceAlwaysFillsMissingSlots() {
        IntentDecision rule = new IntentDecision(
                "graph",
                0.7,
                "rule_graph",
                "",
                "",
                List.of(),
                "any"
        );
        IntentDecision out = enricher.enrich(rule, "戴科彬是哪些主体的法人", false, "rule");
        assertEquals("戴科彬", out.personName());
        assertEquals("person_role_list", out.queryType());
        assertEquals("legal_rep", out.roleFocus());
    }
}

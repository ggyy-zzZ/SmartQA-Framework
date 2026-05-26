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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentDecisionEnricherTest {

    private IntentDecisionEnricher enricher;
    private PersonNameResolver personNameResolver;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        EnterpriseLexicon lexicon = EnterpriseLexicon.loadDefault(mapper);
        QuestionEntityExtractor extractor = new QuestionEntityExtractor(lexicon);
        QaAssistantProperties props = new QaAssistantProperties();
        props.setIntentLlmEnrichMinConfidence(0.72);
        personNameResolver = mock(PersonNameResolver.class);
        when(personNameResolver.resolve(eq("戴科彬"), any(), any()))
                .thenReturn(PersonNameResolution.resolved("戴科彬"));
        when(personNameResolver.resolve(eq("戴先生"), any(), any()))
                .thenReturn(PersonNameResolution.resolved("戴科彬"));
        when(personNameResolver.resolve(eq(""), any(), any()))
                .thenReturn(PersonNameResolution.resolved(""));
        enricher = new IntentDecisionEnricher(props, extractor, personNameResolver);
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
        IntentDecision out = enricher.enrich(llm, "戴科彬是哪些主体的法人", false, "llm").decision();
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
        IntentDecision out = enricher.enrich(llm, "戴科彬是哪些主体的法人", false, "llm").decision();
        assertEquals("戴科彬", out.personName());
    }

    @Test
    void fillsHonorificPersonWhenLlmMarkedVectorReady() {
        IntentDecision llm = new IntentDecision(
                "vector",
                0.88,
                "semantic",
                "semantic",
                "",
                List.of(),
                "any"
        );
        IntentDecision out = enricher.enrich(llm, "戴先生是哪些公司的法人", false, "llm").decision();
        assertEquals("戴科彬", out.personName());
        assertEquals("person_role_list", out.queryType());
        assertEquals("legal_rep", out.roleFocus());
        assertTrue(out.reason().contains("person_resolved"));
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
        IntentDecision out = enricher.enrich(rule, "戴科彬是哪些主体的法人", false, "rule").decision();
        assertEquals("戴科彬", out.personName());
        assertEquals("person_role_list", out.queryType());
        assertEquals("legal_rep", out.roleFocus());
    }
}

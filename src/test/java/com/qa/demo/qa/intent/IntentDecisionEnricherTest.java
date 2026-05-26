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
        when(personNameResolver.resolve(any(), any(), any()))
                .thenAnswer(invocation -> {
                    String raw = invocation.getArgument(0);
                    if (raw == null || raw.isBlank()) {
                        return PersonNameResolution.resolved("");
                    }
                    if ("戴先生".equals(raw)) {
                        return PersonNameResolution.resolved("戴科彬");
                    }
                    return PersonNameResolution.resolved(raw, "张雁雯".equals(raw) ? 110008506 : null);
                });
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
    void forcesPersonCertificateListWithoutCouplingToMysqlIntent() {
        IntentDecision rule = new IntentDecision(
                "hybrid",
                0.7,
                "rule_hybrid",
                "",
                "",
                List.of(),
                "any"
        );
        IntentDecision out = enricher.enrich(rule, "张雁雯负责哪些证照", false, "rule").decision();
        assertEquals("张雁雯", out.personName());
        assertEquals(110008506, out.personEmployeeId());
        assertEquals("person_certificate_list", out.queryType());
        assertEquals("hybrid", out.intent());
    }

    @Test
    void forcesPersonCertificateForResignationStewardshipQuestion() {
        IntentDecision rule = new IntentDecision(
                "hybrid",
                0.7,
                "rule_hybrid",
                "",
                "",
                List.of(),
                "any"
        );
        String question = "张雁雯离职了，我想知道她在负责了哪些东西";
        IntentDecision out = enricher.enrich(rule, question, false, "rule").decision();
        assertEquals("张雁雯", out.personName());
        assertEquals(110008506, out.personEmployeeId());
        assertEquals("person_certificate_list", out.queryType());
        assertEquals("hybrid", out.intent());
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

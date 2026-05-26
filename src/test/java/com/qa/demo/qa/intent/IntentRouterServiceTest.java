package com.qa.demo.qa.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.EnterpriseLexicon;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IntentRouterServiceTest {

    private static IntentRouterService router;

    @BeforeAll
    static void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        EnterpriseLexicon lexicon = EnterpriseLexicon.loadDefault(mapper);
        QuestionEntityExtractor extractor = new QuestionEntityExtractor(lexicon);
        QaAssistantProperties props = new QaAssistantProperties();
        props.setIntentLlmEnabled(false);
        props.setApiKey("");
        router = new IntentRouterService(
                props,
                mock(IntentLlmClassifier.class),
                new IntentRuleEngine(lexicon, extractor),
                new IntentDecisionEnricher(props, extractor)
        );
    }

    @Test
    void decideUsesRuleFallbackWhenLlmDisabled() {
        IntentDecision d = router.decide("戴科彬是哪些主体的法人", false);
        assertEquals("graph", d.intent());
        assertTrue(d.hasPersonFocus());
        assertEquals("戴科彬", d.personName());
        assertEquals("person_role_list", d.queryType());
        assertEquals("legal_rep", d.roleFocus());
    }
}

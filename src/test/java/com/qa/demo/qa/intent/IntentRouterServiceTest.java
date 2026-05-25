package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.retrieval.GraphContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IntentRouterServiceTest {

    @Test
    void decideUsesRuleFallbackWhenLlmDisabled() {
        QaAssistantProperties props = new QaAssistantProperties();
        props.setIntentLlmEnabled(false);
        props.setApiKey("");
        IntentRouterService router = new IntentRouterService(
                new ObjectMapper(),
                props,
                mock(com.qa.demo.qa.answer.MiniMaxClient.class),
                new GraphContextService(mock(Driver.class))
        );

        IntentDecision d = router.decide("戴科彬是哪些主体的法人", false);
        assertEquals("graph", d.intent());
        assertTrue(d.hasPersonFocus());
        assertEquals("戴科彬", d.personName());
        assertEquals("person_role_list", d.queryType());
        assertEquals("legal_rep", d.roleFocus());
    }
}

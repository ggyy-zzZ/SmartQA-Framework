package com.qa.demo.qa.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.knowledge.EvidenceSchemaRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaAnswerGateServiceTest {

    @Test
    void rejectsEmptyEvidence() {
        QaAssistantProperties props = baseProps();
        QaAnswerGateService gate = new QaAnswerGateService(props, schemas());
        var decision = gate.evaluate(new IntentDecision("vector", 0.9, "test"), List.of());
        assertFalse(decision.allowGenerate());
        assertFalse(decision.canAnswer());
    }

    @Test
    void allowsWhenScoreAboveThreshold() {
        QaAssistantProperties props = baseProps();
        QaAnswerGateService gate = new QaAnswerGateService(props, schemas());
        var evidence = List.of(ContextChunk.ofCompany("1", "A公司", "字段", "片段", 5.0, "test"));
        var decision = gate.evaluate(new IntentDecision("vector", 0.9, "test"), evidence);
        assertTrue(decision.allowGenerate());
        assertTrue(decision.canAnswer());
    }

    @Test
    void rejectsLowTopScore() {
        QaAssistantProperties props = baseProps();
        props.setAnswerGateMinTopScore(10.0);
        QaAnswerGateService gate = new QaAnswerGateService(props, schemas());
        var evidence = List.of(ContextChunk.ofCompany("1", "A公司", "字段", "片段", 2.0, "test"));
        var decision = gate.evaluate(new IntentDecision("vector", 0.9, "test"), evidence);
        assertFalse(decision.allowGenerate());
    }

    private static EvidenceSchemaRegistry schemas() {
        return new EvidenceSchemaRegistry(new ObjectMapper());
    }

    private static QaAssistantProperties baseProps() {
        QaAssistantProperties props = new QaAssistantProperties();
        props.setDocsDir("data");
        props.setModel("test");
        props.setApiUrl("http://localhost");
        props.setApiKey("k");
        props.setAnswerGateEnabled(true);
        props.setAnswerGateMinEvidenceCount(1);
        props.setAnswerGateMinTopScore(3.0);
        return props;
    }
}

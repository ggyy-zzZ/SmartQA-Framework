package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidencePresentationPolicyTest {

    private QaAssistantProperties properties;
    private EvidencePresentationPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new QaAssistantProperties();
        properties.setEvidencePresentationDefaultFull(true);
        properties.setEvidenceFullTopK(500);
        properties.setEvidenceEmphasisTopK(500);
        properties.setRetrievalTopK(100);
        properties.setSqlQueryMaxRows(500);
        properties.setMysqlTopK(6);
        policy = new EvidencePresentationPolicy(properties);
    }

    @Test
    void defaultIsFullModeWithHighTopK() {
        EvidencePresentationContext ctx = policy.resolve("张三担任法人的公司有哪些", null, List.of());
        assertEquals(EvidencePresentationMode.FULL, ctx.mode());
        assertEquals(500, ctx.evidenceTopK());
        assertEquals(500, ctx.sqlMaxRows());
    }

    @Test
    void compactModeUsesRetrievalTopK() {
        EvidencePresentationContext ctx = policy.resolve("张三担任法人的公司有哪些", "compact", List.of());
        assertEquals(EvidencePresentationMode.COMPACT, ctx.mode());
        assertEquals(100, ctx.evidenceTopK());
    }

    @Test
    void userEmphasisBoostsFullMode() {
        EvidencePresentationContext ctx = policy.resolve("请完整列出所有法人公司", "compact", List.of());
        assertEquals(EvidencePresentationMode.FULL, ctx.mode());
        assertTrue(ctx.userEmphasizedComplete());
        assertEquals(500, ctx.evidenceTopK());
    }
}

package com.qa.demo.qa.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TextEmbeddingServiceTest {

    @Test
    void hashProviderProducesNormalizedVector() {
        QaAssistantProperties props = new QaAssistantProperties();
        props.setDocsDir("data");
        props.setModel("test");
        props.setApiUrl("http://localhost");
        props.setApiKey("test-key");
        props.setEmbeddingProvider("hash");
        props.setVectorEmbeddingDim(128);

        TextEmbeddingService service = new TextEmbeddingService(props, new ObjectMapper());
        List<Double> vec = service.embed("测试向量");

        assertEquals(128, vec.size());
        double norm = 0;
        for (double v : vec) {
            norm += v * v;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-6);
    }

    @Test
    void dashscopeWithoutKeyFallsBackToHash() {
        QaAssistantProperties props = new QaAssistantProperties();
        props.setDocsDir("data");
        props.setModel("test");
        props.setApiUrl("http://localhost");
        props.setApiKey("test-key");
        props.setEmbeddingProvider("dashscope");
        props.setDashscopeApiKey("");
        props.setVectorEmbeddingDim(64);

        TextEmbeddingService service = new TextEmbeddingService(props, new ObjectMapper());
        assertEquals("hash", service.activeProvider());
        assertFalse(service.embed("fallback").isEmpty());
    }
}

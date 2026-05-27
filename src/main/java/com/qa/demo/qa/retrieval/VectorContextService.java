package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.embedding.TextEmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class VectorContextService {

    private static final Logger log = LoggerFactory.getLogger(VectorContextService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;
    private final TextEmbeddingService textEmbeddingService;

    public VectorContextService(
            ObjectMapper objectMapper,
            QaAssistantProperties properties,
            TextEmbeddingService textEmbeddingService
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.textEmbeddingService = textEmbeddingService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getQdrantTimeoutMs());
        factory.setReadTimeout(properties.getQdrantTimeoutMs());
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    public List<ContextChunk> retrieveTopChunks(String question) {
        return retrieveTopChunks(question, properties.getVectorTopK());
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK) {
        if (!properties.isVectorEnabled()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(topK, 30));
        try {
            List<Double> vector = textEmbeddingService.embed(question);
            Map<String, Object> body = Map.of(
                    "vector", vector,
                    "limit", limit,
                    "with_payload", true,
                    "with_vector", false
            );

            String response = restClient.post()
                    .uri(properties.getQdrantUrl()
                            + "/collections/" + properties.getQdrantCollection() + "/points/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode points = objectMapper.readTree(response).path("result");
            if (!points.isArray() || points.isEmpty()) {
                return List.of();
            }
            List<ContextChunk> chunks = new ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                String companyId = payload.path("company_id").asText("");
                String companyName = payload.path("company_name").asText("");
                String status = payload.path("status").asText("");
                String text = payload.path("text").asText("");
                double score = point.path("score").asDouble(0.0) * 20.0;
                chunks.add(ContextChunk.ofCompany(
                        companyId,
                        companyName,
                        "向量检索",
                        "状态=" + status + "; 摘要=" + truncate(text, 220),
                        score,
                        "qdrant-vector"
                ));
            }
            return chunks;
        } catch (Exception e) {
            log.warn("vector retrieve failed (provider={}): {}", textEmbeddingService.activeProvider(), e.getMessage());
            return List.of();
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}

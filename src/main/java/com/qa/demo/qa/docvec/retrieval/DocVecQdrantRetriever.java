package com.qa.demo.qa.docvec.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.docvec.config.DocVecProperties;
import com.qa.demo.qa.embedding.TextEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 Doc-RAG 专用 Qdrant collection 召回公司档案全文。
 */
@Service
public class DocVecQdrantRetriever {

    private static final Logger log = LoggerFactory.getLogger(DocVecQdrantRetriever.class);
    private static final String EVIDENCE_SCHEMA = "doc_profile_v1";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DocVecProperties properties;
    private final TextEmbeddingService textEmbeddingService;

    public DocVecQdrantRetriever(
            ObjectMapper objectMapper,
            DocVecProperties properties,
            TextEmbeddingService textEmbeddingService
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.textEmbeddingService = textEmbeddingService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getQdrantTimeoutMs());
        factory.setReadTimeout(properties.getQdrantTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public List<ContextChunk> retrieve(String question, int topK) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        int limit = Math.max(1, topK);
        try {
            List<Double> vector = textEmbeddingService.embed(question);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", vector);
            body.put("limit", limit);
            body.put("with_payload", true);
            body.put("with_vector", false);

            String response = restClient.post()
                    .uri(properties.getQdrantUrl()
                            + "/collections/" + properties.getCollection() + "/points/search")
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
                String docId = payload.path("entity_id").asText(payload.path("company_id").asText(""));
                String companyName = payload.path("company_name").asText("");
                String profileText = payload.path("raw_text").asText("");
                if (profileText.isBlank()) {
                    profileText = payload.path("text").asText("");
                }
                double score = point.path("score").asDouble(0.0) * 20.0;
                if (profileText.isBlank()) {
                    continue;
                }
                chunks.add(new ContextChunk(
                        docId,
                        companyName,
                        ContextChunk.KIND_DOCUMENT,
                        "公司档案",
                        profileText,
                        score,
                        "docvec-qdrant",
                        EVIDENCE_SCHEMA
                ));
            }
            return chunks;
        } catch (Exception e) {
            log.warn("docvec retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }
}

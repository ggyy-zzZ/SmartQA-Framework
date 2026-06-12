package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.constraint.ConstraintSet;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        return retrieveTopChunks(question, topK, null);
    }

    /**
     * 约束感知召回：传入 {@link ConstraintSet} 时，构造 Qdrant {@code Filter must}，
     * 强制只返回满足 region/office 约束的点（D3："在北京" = 注册地 OR 办公地）。
     * <p>
     * Qdrant payload 字段：{@code registeredAreaCode} / {@code officeAreaCode}（string 数组）。
     * 老 collection 若字段缺失，Qdrant 端 matchAny 会过滤掉所有点（保守行为，符合 R1 缓解）。
     */
    public List<ContextChunk> retrieveTopChunks(String question, int topK, ConstraintSet constraint) {
        if (!properties.isVectorEnabled()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(topK, 30));
        try {
            List<Double> vector = textEmbeddingService.embed(question);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", vector);
            body.put("limit", limit);
            body.put("with_payload", true);
            body.put("with_vector", false);

            Map<String, Object> filter = buildRegionFilter(constraint);
            if (filter != null) {
                body.put("filter", filter);
            }

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

    /**
     * 构造 Qdrant payload 过滤：must=[MatchAny("registeredAreaCode", codes),
     * MatchAny("officeAreaCode", codes)]。两个条件取并集（满足任一即命中）。
     */
    private Map<String, Object> buildRegionFilter(ConstraintSet c) {
        if (c == null || !c.hasRegion()) {
            return null;
        }
        List<String> allCodes = new ArrayList<>();
        if (c.hasRegion()) {
            allCodes.addAll(c.regionCodes());
            allCodes.addAll(c.officeRegionCodes());
        }
        if (allCodes.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> must = new ArrayList<>();
        if (!c.regionCodes().isEmpty()) {
            must.add(matchAnyField("registeredAreaCode", c.regionCodes()));
        }
        if (!c.officeRegionCodes().isEmpty()) {
            must.add(matchAnyField("officeAreaCode", c.officeRegionCodes()));
        }
        if (must.isEmpty()) {
            return null;
        }
        // 多个 must 子句之间是 AND；每个 must 内"field matchAny"取并集；
        // 用户语义"在北京"= 注册地 OR 办公地 → 我们用 should(OR) 包裹两个 must。
        if (must.size() == 1) {
            Map<String, Object> f = new HashMap<>();
            f.put("must", must);
            return f;
        }
        Map<String, Object> f = new HashMap<>();
        f.put("should", must);
        return f;
    }

    private Map<String, Object> matchAnyField(String field, List<String> codes) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", field);
        m.put("match", Map.of("any", codes));
        return m;
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

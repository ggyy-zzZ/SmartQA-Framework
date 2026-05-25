package com.qa.demo.qa.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一文本向量化：默认百炼 DashScope {@code text-embedding-v4}，无密钥时降级为 hash。
 */
@Service
public class TextEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(TextEmbeddingService.class);

    private final QaAssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TextEmbeddingService(QaAssistantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getEmbeddingTimeoutMs());
        factory.setReadTimeout(properties.getEmbeddingTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * @return 与 {@link QaAssistantProperties#getVectorEmbeddingDim()} 等长的向量；失败时抛出异常
     */
    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) {
            return embedHash("");
        }
        if (useDashScope()) {
            return embedDashScopeBatch(List.of(text)).getFirst();
        }
        return embedHash(text);
    }

    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (useDashScope()) {
            return embedDashScopeBatch(texts);
        }
        List<List<Double>> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embedHash(text == null ? "" : text));
        }
        return out;
    }

    public String activeProvider() {
        return useDashScope() ? "dashscope" : "hash";
    }

    private boolean useDashScope() {
        return "dashscope".equalsIgnoreCase(properties.getEmbeddingProvider())
                && properties.getDashscopeApiKey() != null
                && !properties.getDashscopeApiKey().isBlank();
    }

    private List<Double> embedHash(String text) {
        try {
            int dim = Math.max(64, properties.getVectorEmbeddingDim());
            return HashTextEmbedding.embed(text, dim);
        } catch (Exception e) {
            throw new IllegalStateException("hash embedding failed: " + e.getMessage(), e);
        }
    }

    private List<List<Double>> embedDashScopeBatch(List<String> texts) {
        int dim = properties.getVectorEmbeddingDim();
        int batchSize = Math.max(1, Math.min(properties.getEmbeddingBatchSize(), 10));
        List<List<Double>> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            all.addAll(callDashScope(batch, dim));
        }
        return all;
    }

    private List<List<Double>> callDashScope(List<String> texts, int dimension) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getEmbeddingModel());
        body.put("input", Map.of("texts", texts));
        body.put("parameters", Map.of("dimension", dimension, "output_type", "dense"));

        String raw;
        try {
            raw = restClient.post()
                    .uri(properties.getEmbeddingApiUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscopeApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IllegalStateException("DashScope embedding request failed: " + e.getMessage(), e);
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("DashScope embedding returned empty body");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode code = root.path("code");
            if (!code.isMissingNode() && !code.isNull() && !"".equals(code.asText())) {
                String msg = root.path("message").asText("unknown error");
                throw new IllegalStateException("DashScope error code=" + code.asText() + ": " + msg);
            }
            JsonNode embeddings = root.path("output").path("embeddings");
            if (!embeddings.isArray() || embeddings.isEmpty()) {
                throw new IllegalStateException("DashScope response missing output.embeddings: " + truncate(raw, 500));
            }
            Map<Integer, List<Double>> byIndex = new HashMap<>();
            for (JsonNode item : embeddings) {
                int idx = item.path("text_index").asInt(byIndex.size());
                List<Double> vec = new ArrayList<>();
                for (JsonNode v : item.path("embedding")) {
                    vec.add(v.asDouble());
                }
                if (vec.size() != dimension) {
                    log.warn("DashScope embedding dim {} != configured {}", vec.size(), dimension);
                }
                byIndex.put(idx, vec);
            }
            List<List<Double>> ordered = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                List<Double> vec = byIndex.get(i);
                if (vec == null || vec.isEmpty()) {
                    throw new IllegalStateException("DashScope missing embedding for text_index=" + i);
                }
                ordered.add(vec);
            }
            return ordered;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse DashScope embedding response: " + e.getMessage(), e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}

package com.qa.demo.qa.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对多路召回候选做交叉编码重排（百炼 gte-rerank）；不可用时按原始 score 截断。
 */
@Service
public class EvidenceRerankService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRerankService.class);
    private static final int MAX_DOC_CHARS = 1200;

    private final QaAssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public EvidenceRerankService(QaAssistantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getEmbeddingTimeoutMs());
        factory.setReadTimeout(properties.getEmbeddingTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public String activeProvider() {
        if (!properties.isRerankEnabled()) {
            return "disabled";
        }
        return useDashScopeRerank() ? "dashscope" : "score_fallback";
    }

    /**
     * @return 重排后 TopK 证据（score 已按重排结果更新）
     */
    public List<ContextChunk> rerank(String question, List<ContextChunk> candidates) {
        return rerank(question, candidates, Math.max(1, properties.getRetrievalTopK()));
    }

    /**
     * @param finalTopK 送入生成模型的证据条数；列表型问题（如法人任职）应使用更大 topK
     */
    public List<ContextChunk> rerank(String question, List<ContextChunk> candidates, int finalTopK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        finalTopK = Math.max(1, finalTopK);
        int candidateCap = Math.max(finalTopK, properties.getRerankCandidateMax());
        List<ContextChunk> capped = candidates.size() > candidateCap
                ? new ArrayList<>(candidates.subList(0, candidateCap))
                : new ArrayList<>(candidates);

        if (!properties.isRerankEnabled()) {
            return sortByScoreAndLimit(capped, finalTopK);
        }
        if (!useDashScopeRerank()) {
            return sortByScoreAndLimit(capped, finalTopK);
        }
        try {
            return rerankDashScope(question, capped, finalTopK);
        } catch (Exception e) {
            log.warn("rerank failed, fallback to score sort: {}", e.getMessage());
            return sortByScoreAndLimit(capped, finalTopK);
        }
    }

    private boolean useDashScopeRerank() {
        return properties.getDashscopeApiKey() != null && !properties.getDashscopeApiKey().isBlank();
    }

    private List<ContextChunk> rerankDashScope(String question, List<ContextChunk> candidates, int finalTopK) throws Exception {
        List<String> documents = new ArrayList<>(candidates.size());
        for (ContextChunk chunk : candidates) {
            documents.add(buildDocumentText(chunk));
        }
        int topN = Math.min(finalTopK, documents.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getRerankModel());
        body.put("input", Map.of(
                "query", question == null ? "" : question,
                "documents", documents
        ));
        body.put("parameters", Map.of("top_n", topN, "return_documents", false));

        String raw = restClient.post()
                .uri(properties.getRerankApiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscopeApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("DashScope rerank empty body");
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode code = root.path("code");
        if (!code.isMissingNode() && !code.isNull() && !"".equals(code.asText())) {
            throw new IllegalStateException("DashScope rerank error: " + root.path("message").asText(""));
        }
        JsonNode results = root.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            return sortByScoreAndLimit(candidates, finalTopK);
        }
        List<ContextChunk> reranked = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            double relevance = item.path("relevance_score").asDouble(0.0);
            ContextChunk original = candidates.get(index);
            double score = Math.max(relevance * 25.0, original.score());
            reranked.add(new ContextChunk(
                    original.anchorId(),
                    original.displayLabel(),
                    original.entityKind(),
                    original.field(),
                    original.snippet(),
                    score,
                    original.source() + "+rerank",
                    original.evidenceSchema()
            ));
        }
        if (reranked.isEmpty()) {
            return sortByScoreAndLimit(candidates, finalTopK);
        }
        return reranked;
    }

    private static String buildDocumentText(ContextChunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (chunk.displayLabel() != null && !chunk.displayLabel().isBlank()) {
            sb.append("公司:").append(chunk.displayLabel()).append("\n");
        }
        if (chunk.field() != null && !chunk.field().isBlank()) {
            sb.append("字段:").append(chunk.field()).append("\n");
        }
        if (chunk.snippet() != null) {
            sb.append(chunk.snippet());
        }
        String text = sb.toString().trim();
        if (text.length() > MAX_DOC_CHARS) {
            return text.substring(0, MAX_DOC_CHARS);
        }
        return text.isBlank() ? "无内容" : text;
    }

    private static List<ContextChunk> sortByScoreAndLimit(List<ContextChunk> candidates, int topK) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(ContextChunk::score).reversed())
                .limit(topK)
                .toList();
    }
}

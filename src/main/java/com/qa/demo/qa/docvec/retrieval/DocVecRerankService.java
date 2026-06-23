package com.qa.demo.qa.docvec.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.docvec.config.DocVecProperties;
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
 * Doc-RAG 专用重排：允许更长的档案文本参与 cross-encoder。
 */
@Service
public class DocVecRerankService {

    private static final Logger log = LoggerFactory.getLogger(DocVecRerankService.class);
    private static final int MAX_DOC_CHARS = 6000;

    private final DocVecProperties docVecProperties;
    private final QaAssistantProperties assistantProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DocVecRerankService(
            DocVecProperties docVecProperties,
            QaAssistantProperties assistantProperties,
            ObjectMapper objectMapper
    ) {
        this.docVecProperties = docVecProperties;
        this.assistantProperties = assistantProperties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(assistantProperties.getEmbeddingTimeoutMs());
        factory.setReadTimeout(assistantProperties.getEmbeddingTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public List<ContextChunk> rerank(String question, List<ContextChunk> candidates, int finalTopK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        finalTopK = Math.max(1, finalTopK);
        if (!docVecProperties.isRerankEnabled() || !useDashScopeRerank()) {
            return sortByScoreAndLimit(candidates, finalTopK);
        }
        try {
            return rerankDashScope(question, candidates, finalTopK);
        } catch (Exception e) {
            log.warn("docvec rerank failed, fallback to score: {}", e.getMessage());
            return sortByScoreAndLimit(candidates, finalTopK);
        }
    }

    private boolean useDashScopeRerank() {
        String key = assistantProperties.getDashscopeApiKey();
        return key != null && !key.isBlank();
    }

    private List<ContextChunk> rerankDashScope(String question, List<ContextChunk> candidates, int finalTopK)
            throws Exception {
        List<String> documents = new ArrayList<>(candidates.size());
        for (ContextChunk chunk : candidates) {
            documents.add(buildDocumentText(chunk));
        }
        int topN = Math.min(finalTopK, documents.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", assistantProperties.getRerankModel());
        body.put("input", Map.of(
                "query", question == null ? "" : question,
                "documents", documents
        ));
        body.put("parameters", Map.of("top_n", topN, "return_documents", false));

        String raw = restClient.post()
                .uri(assistantProperties.getRerankApiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + assistantProperties.getDashscopeApiKey())
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
            reranked.add(new ContextChunk(
                    original.anchorId(),
                    original.displayLabel(),
                    original.entityKind(),
                    original.field(),
                    original.snippet(),
                    Math.max(relevance * 25.0, original.score()),
                    original.source() + "+rerank",
                    original.evidenceSchema()
            ));
        }
        return reranked.isEmpty() ? sortByScoreAndLimit(candidates, finalTopK) : reranked;
    }

    private static String buildDocumentText(ContextChunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (chunk.displayLabel() != null && !chunk.displayLabel().isBlank()) {
            sb.append("名称:").append(chunk.displayLabel()).append("\n");
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

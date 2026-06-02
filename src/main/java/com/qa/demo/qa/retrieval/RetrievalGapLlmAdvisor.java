package com.qa.demo.qa.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检索缺口判定（LLM 兜底）：在规则难以覆盖的口语问法下，判断是否需要补充状态/有效期等公司维度。
 */
@Component
public class RetrievalGapLlmAdvisor {

    private final MiniMaxClient miniMaxClient;
    private final QaAssistantProperties properties;
    private final ObjectMapper objectMapper;

    public RetrievalGapLlmAdvisor(
            MiniMaxClient miniMaxClient,
            QaAssistantProperties properties,
            ObjectMapper objectMapper
    ) {
        this.miniMaxClient = miniMaxClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public GapDecision decideCompanyFacetGap(String question, List<ContextChunk> evidence) {
        if (!properties.isIntentLlmEnabled() || !hasApiKey()) {
            return GapDecision.none("llm_disabled_or_no_key");
        }
        if (question == null || question.isBlank()) {
            return GapDecision.none("empty_question");
        }
        try {
            String system = """
                    你是“检索缺口判定器”。
                    任务：判断用户问题是否需要公司维度中的“经营状态”或“有效期/截止时间”才能回答。
                    仅输出单行 JSON，不要输出其它文字。
                    JSON 格式：
                    {"needStatus":true|false,"needValidity":true|false,"confidence":0.0-1.0,"reason":"<=40字"}
                    判定原则：
                    1) needStatus=true：用户问存续/注销/吊销/状态筛选/是否有效经营等。
                    2) needValidity=true：用户问有效期/截止时间/到期/起止时间等。
                    3) 如果当前证据已能直接回答，不要误判为需要补检索。
                    """;
            String user = "问题:\n" + question + "\n\n现有证据摘要:\n" + summarizeEvidence(evidence);
            String raw = miniMaxClient.completeChat(system, user);
            return parseDecision(raw);
        } catch (Exception ignored) {
            return GapDecision.none("llm_error");
        }
    }

    private GapDecision parseDecision(String raw) {
        try {
            String json = extractJson(raw);
            JsonNode root = objectMapper.readTree(json);
            boolean needStatus = root.path("needStatus").asBoolean(false);
            boolean needValidity = root.path("needValidity").asBoolean(false);
            double confidence = clamp(root.path("confidence").asDouble(0.5));
            String reason = root.path("reason").asText("llm_judgement");
            return new GapDecision(needStatus, needValidity, confidence, reason);
        } catch (Exception ignored) {
            return GapDecision.none("parse_failed");
        }
    }

    private static String summarizeEvidence(List<ContextChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "无证据";
        }
        StringBuilder sb = new StringBuilder();
        int max = Math.min(evidence.size(), 8);
        for (int i = 0; i < max; i++) {
            ContextChunk chunk = evidence.get(i);
            if (chunk == null) {
                continue;
            }
            String source = chunk.source() == null ? "" : chunk.source();
            String snippet = chunk.snippet() == null ? "" : chunk.snippet();
            if (snippet.length() > 140) {
                snippet = snippet.substring(0, 140) + "...";
            }
            sb.append("- source=").append(source).append("; snippet=").append(snippet).append('\n');
        }
        return sb.toString().trim();
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int l = raw.indexOf('{');
        int r = raw.lastIndexOf('}');
        if (l >= 0 && r > l) {
            return raw.substring(l, r + 1);
        }
        return "{}";
    }

    private boolean hasApiKey() {
        String key = properties.getApiKey();
        return key != null && !key.isBlank();
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record GapDecision(
            boolean needStatus,
            boolean needValidity,
            double confidence,
            String reason
    ) {
        public static GapDecision none(String reason) {
            return new GapDecision(false, false, 0.0, reason);
        }
    }
}

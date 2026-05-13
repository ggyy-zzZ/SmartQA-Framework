package com.qa.demo.qa.intent;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class IntentRouterService {

    private static final List<String> VALID_INTENTS = List.of("graph", "document", "vector", "mysql", "sql", "hybrid", "unknown");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;

    public IntentRouterService(ObjectMapper objectMapper, QaAssistantProperties properties) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public IntentDecision decide(String question, boolean explicitCompanyHint) {
        try {
            IntentDecision llmDecision = classifyByLlm(question, explicitCompanyHint);
            if (VALID_INTENTS.contains(llmDecision.intent())) {
                return llmDecision;
            }
        } catch (Exception ignored) {
            // fallback to rule route
        }
        return classifyByRule(question, explicitCompanyHint, "llm_failed_use_rule");
    }

    private IntentDecision classifyByLlm(String question, boolean explicitCompanyHint) throws Exception {
        String systemPrompt = KnowledgeAssistantPrompts.intentRouterLlmSystemPrompt();
        String userPrompt = "question=" + question + "\nexplicit_company_hint=" + explicitCompanyHint;
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "name", "MiniMax AI", "content", systemPrompt),
                        Map.of("role", "user", "name", "User", "content", userPrompt)
                )
        );
        String response = restClient.post()
                .uri(properties.getApiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        String content = extractContent(response);
        JsonNode node = objectMapper.readTree(content);
        String intent = node.path("intent").asText("").toLowerCase(Locale.ROOT);
        double confidence = clamp(node.path("confidence").asDouble(0.60));
        String reason = node.path("reason").asText("llm_route");
        return new IntentDecision(intent, confidence, reason);
    }

    private String extractContent(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode statusMsgNode = root.path("base_resp").path("status_msg");
        if (statusMsgNode.isTextual() && !statusMsgNode.asText().isBlank()
                && !"success".equalsIgnoreCase(statusMsgNode.asText())) {
            throw new IllegalStateException("MiniMax API error: " + statusMsgNode.asText());
        }
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isTextual() && !contentNode.asText().isBlank()) {
            return contentNode.asText().trim();
        }
        JsonNode replyNode = root.path("reply");
        if (replyNode.isTextual() && !replyNode.asText().isBlank()) {
            return replyNode.asText().trim();
        }
        JsonNode outputTextNode = root.path("output_text");
        if (outputTextNode.isTextual() && !outputTextNode.asText().isBlank()) {
            return outputTextNode.asText().trim();
        }
        throw new IllegalStateException("Model returned empty route content");
    }

    private IntentDecision classifyByRule(String question, boolean explicitCompanyHint, String reasonPrefix) {
        String q = question.toLowerCase(Locale.ROOT);
        boolean relationIntent = q.contains("股东")
                || q.contains("股权")
                || q.contains("穿透")
                || q.contains("关系")
                || q.contains("关联")
                || q.contains("总公司")
                || q.contains("分公司");
        boolean docIntent = q.contains("流程")
                || q.contains("规定")
                || q.contains("制度")
                || q.contains("政策")
                || q.contains("步骤")
                || q.contains("怎么办");
        boolean semanticIntent = q.contains("类似")
                || q.contains("大概")
                || q.contains("相关")
                || q.contains("介绍")
                || q.contains("说明");
        boolean mysqlIntent = q.contains("mysql")
                || q.contains("数据库")
                || q.contains("表")
                || q.contains("字段")
                || q.contains("结构化")
                || q.contains("明细")
                || q.contains("记录")
                || q.contains("sql")
                || q.contains("schema");
        boolean sqlIntent = q.contains("多少")
                || q.contains("总数")
                || q.contains("数量")
                || q.contains("统计")
                || q.contains("占比")
                || q.contains("按")
                || q.contains("分组")
                || q.contains("top")
                || q.contains("排行")
                || q.contains("最近")
                || q.contains("最大")
                || q.contains("最小");
        if (relationIntent && semanticIntent) {
            return new IntentDecision("hybrid", 0.72, reasonPrefix + "_hybrid_relation_semantic");
        }
        if (sqlIntent && (mysqlIntent || relationIntent || explicitCompanyHint)) {
            return new IntentDecision("sql", 0.76, reasonPrefix + "_sql_analytic_or_filtered");
        }
        if (mysqlIntent && !relationIntent) {
            return new IntentDecision("mysql", 0.72, reasonPrefix + "_mysql_structured_raw");
        }
        if (relationIntent || explicitCompanyHint) {
            return new IntentDecision("graph", 0.70, reasonPrefix + "_graph_relation_or_entity");
        }
        if (docIntent) {
            return new IntentDecision("document", 0.68, reasonPrefix + "_document_policy_flow");
        }
        if (semanticIntent) {
            return new IntentDecision("vector", 0.66, reasonPrefix + "_vector_semantic");
        }
        return new IntentDecision("unknown", 0.45, reasonPrefix + "_unknown_out_of_scope");
    }

    private double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}

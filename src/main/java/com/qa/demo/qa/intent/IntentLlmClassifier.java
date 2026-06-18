package com.qa.demo.qa.intent;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalStrategy;
import com.qa.demo.qa.domain.EntityRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntentLlmClassifier {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");
    private static final Set<String> VALID_RETRIEVAL_STRATEGIES = IntentSlots.VALID_RETRIEVAL_STRATEGIES;

    private final MiniMaxClient miniMaxClient;
    private final ObjectMapper objectMapper;

    public IntentLlmClassifier(MiniMaxClient miniMaxClient, ObjectMapper objectMapper) {
        this.miniMaxClient = miniMaxClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 追问场景的意图解析：带会话上下文，让 LLM 判断本轮 queryType。
     */
    public IntentDecision classifyWithContext(String currentQuestion, String priorQuestion,
            String priorRetrievalStrategy, String priorAnswer, String personName,
            List<String> companyHints) throws Exception {
        String userPrompt = buildFollowUpUserPrompt(currentQuestion, priorQuestion, priorRetrievalStrategy,
                priorAnswer, personName, companyHints);
        String content = miniMaxClient.completeChat(
                KnowledgeAssistantPrompts.followUpIntentRouterSystemPrompt(),
                userPrompt
        );
        JsonNode node = parseJsonNode(content);
        double confidence = clamp(node.path("confidence").asDouble(0.7));
        String reason = node.path("reason").asText("followup_llm");
        String retrievalStrategy = node.path("retrievalStrategy").asText("").trim().toLowerCase(Locale.ROOT);
        String extractedPerson = node.path("personName").asText("").trim();
        String roleFocus = node.path("roleFocus").asText("any").trim().toLowerCase(Locale.ROOT);
        List<String> extractedCompanyHints = parseCompanyHints(node.path("companyHints"));

        if (extractedPerson.isBlank() && personName != null && !personName.isBlank()) {
            extractedPerson = personName;
        }
        List<String> mergedHints = new ArrayList<>(extractedCompanyHints);
        if (companyHints != null) {
            for (String h : companyHints) {
                if (!mergedHints.contains(h)) {
                    mergedHints.add(h);
                }
            }
        }

        IntentDecision raw = new IntentDecision("", confidence, reason, "", extractedPerson,
                mergedHints, roleFocus, null, retrievalStrategy);
        IntentDecision normalized = IntentSlots.normalize(raw);
        validateLlmRetrievalStrategy(normalized);
        return normalized;
    }

    /**
     * 追问场景的意图解析：带会话上下文和结构化实体（含状态元数据）。
     * <p>
     * 注意：本方法不传递 status 字段给 LLM，而是通过 companyHints 传递公司列表。
     * LLM 根据当前问题的语义自行判断使用哪些公司（如"非存续"由 LLM 理解）。
     */
    public IntentDecision classifyWithEntities(String currentQuestion, String priorQuestion,
            String priorRetrievalStrategy, String priorAnswer, String personName,
            List<EntityRef> companyEntities) throws Exception {
        List<String> companyHints = new ArrayList<>();
        if (companyEntities != null) {
            for (EntityRef ref : companyEntities) {
                if (ref.name() != null && !ref.name().isBlank() && !companyHints.contains(ref.name())) {
                    companyHints.add(ref.name());
                }
            }
        }
        return classifyWithContext(currentQuestion, priorQuestion, priorRetrievalStrategy, priorAnswer, personName,
                companyHints);
    }

    private String buildFollowUpUserPrompt(String currentQuestion, String priorQuestion,
            String priorRetrievalStrategy, String priorAnswer, String personName, List<String> companyHints) {
        StringBuilder sb = new StringBuilder();
        sb.append("【会话历史】\n");
        sb.append("上一轮问题：").append(priorQuestion != null ? priorQuestion : "").append("\n");
        sb.append("上一轮 retrievalStrategy：")
                .append(priorRetrievalStrategy != null ? priorRetrievalStrategy : "").append("\n");
        if (priorAnswer != null && !priorAnswer.isBlank()) {
            sb.append("上一轮答案摘要：").append(priorAnswer).append("\n");
        }
        sb.append("【当前问题】\n");
        sb.append(currentQuestion).append("\n");
        sb.append("【已知信息】\n");
        if (personName != null && !personName.isBlank()) {
            sb.append("当前问题涉及人物：").append(personName).append("\n");
        }
        if (companyHints != null && !companyHints.isEmpty()) {
            sb.append("当前问题涉及公司：").append(String.join("、", companyHints)).append("\n");
        }
        return sb.toString();
    }

    public IntentDecision classify(String question, boolean explicitCompanyHint) throws Exception {
        String userPrompt = "question=" + question + "\nexplicit_company_hint=" + explicitCompanyHint;
        String content = miniMaxClient.completeChat(
                KnowledgeAssistantPrompts.intentRouterLlmSystemPrompt(),
                userPrompt
        );
        JsonNode node = parseJsonNode(content);
        double confidence = clamp(node.path("confidence").asDouble(0.65));
        String reason = node.path("reason").asText("llm_route");
        String retrievalStrategy = node.path("retrievalStrategy").asText("").trim().toLowerCase(Locale.ROOT);
        String personName = node.path("personName").asText("").trim();
        String roleFocus = node.path("roleFocus").asText("any").trim().toLowerCase(Locale.ROOT);
        List<String> companyHints = parseCompanyHints(node.path("companyHints"));
        IntentDecision raw = new IntentDecision("", confidence, reason, "", personName, companyHints,
                roleFocus, null, retrievalStrategy);
        IntentDecision normalized = IntentSlots.normalize(raw);
        validateLlmRetrievalStrategy(normalized);
        return normalized;
    }

    private void validateLlmRetrievalStrategy(IntentDecision normalized) {
        String token = normalized.retrievalStrategy() == null ? "" : normalized.retrievalStrategy().trim();
        if (VALID_RETRIEVAL_STRATEGIES.contains(token.toLowerCase(Locale.ROOT))
                && normalized.resolvedRetrievalStrategy() != RetrievalStrategy.UNKNOWN) {
            return;
        }
        throw new IllegalStateException("Invalid or unknown retrievalStrategy from LLM: " + token);
    }

    private List<String> parseCompanyHints(JsonNode hintsNode) {
        List<String> hints = new ArrayList<>();
        if (hintsNode != null && hintsNode.isArray()) {
            for (JsonNode item : hintsNode) {
                String text = item.asText("").trim();
                if (!text.isBlank()) {
                    hints.add(text);
                }
            }
        }
        return hints;
    }

    private JsonNode parseJsonNode(String content) throws Exception {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("{")) {
            return objectMapper.readTree(trimmed);
        }
        Matcher matcher = JSON_OBJECT.matcher(trimmed);
        if (matcher.find()) {
            return objectMapper.readTree(matcher.group());
        }
        throw new IllegalStateException("No JSON object in model route content");
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}

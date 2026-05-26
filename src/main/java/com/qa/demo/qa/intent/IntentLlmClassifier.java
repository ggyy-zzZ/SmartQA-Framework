package com.qa.demo.qa.intent;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.core.IntentDecision;
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
    private static final Set<String> VALID_INTENTS = IntentSlots.VALID_INTENTS;

    private final MiniMaxClient miniMaxClient;
    private final ObjectMapper objectMapper;

    public IntentLlmClassifier(MiniMaxClient miniMaxClient, ObjectMapper objectMapper) {
        this.miniMaxClient = miniMaxClient;
        this.objectMapper = objectMapper;
    }

    public IntentDecision classify(String question, boolean explicitCompanyHint) throws Exception {
        String userPrompt = "question=" + question + "\nexplicit_company_hint=" + explicitCompanyHint;
        String content = miniMaxClient.completeChat(
                KnowledgeAssistantPrompts.intentRouterLlmSystemPrompt(),
                userPrompt
        );
        JsonNode node = parseJsonNode(content);
        String intent = node.path("intent").asText("").toLowerCase(Locale.ROOT);
        double confidence = clamp(node.path("confidence").asDouble(0.65));
        String reason = node.path("reason").asText("llm_route");
        String queryType = node.path("queryType").asText("").toLowerCase(Locale.ROOT);
        String personName = node.path("personName").asText("").trim();
        String roleFocus = node.path("roleFocus").asText("any").trim().toLowerCase(Locale.ROOT);
        List<String> companyHints = parseCompanyHints(node.path("companyHints"));
        IntentDecision raw = new IntentDecision(intent, confidence, reason, queryType, personName, companyHints, roleFocus);
        IntentDecision normalized = IntentSlots.normalize(raw);
        if (!VALID_INTENTS.contains(normalized.intent()) || "unknown".equals(normalized.intent())) {
            throw new IllegalStateException("Invalid or unknown intent from LLM: " + normalized.intent());
        }
        return normalized;
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

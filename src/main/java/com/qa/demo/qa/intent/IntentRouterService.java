package com.qa.demo.qa.intent;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.retrieval.GraphContextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IntentRouterService {

    private static final List<String> VALID_INTENTS = List.of("graph", "document", "vector", "mysql", "sql", "hybrid", "unknown");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;
    private final MiniMaxClient miniMaxClient;
    private final GraphContextService graphContextService;

    public IntentRouterService(
            ObjectMapper objectMapper,
            QaAssistantProperties properties,
            MiniMaxClient miniMaxClient,
            GraphContextService graphContextService
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.miniMaxClient = miniMaxClient;
        this.graphContextService = graphContextService;
    }

    public IntentDecision decide(String question, boolean explicitCompanyHint) {
        if (properties.isIntentLlmEnabled() && hasMinimaxKey()) {
            try {
                IntentDecision llmDecision = classifyByLlm(question, explicitCompanyHint);
                if (VALID_INTENTS.contains(llmDecision.intent())) {
                    return enrichWithRuleFallback(llmDecision, question, explicitCompanyHint, "llm");
                }
            } catch (Exception ignored) {
                // fallback to rule route
            }
        }
        return enrichWithRuleFallback(
                classifyByRule(question, explicitCompanyHint, llmRouteReasonPrefix()),
                question,
                explicitCompanyHint,
                "rule"
        );
    }

    private String llmRouteReasonPrefix() {
        if (!properties.isIntentLlmEnabled()) {
            return "intent_llm_disabled";
        }
        if (!hasMinimaxKey()) {
            return "no_minimax_key";
        }
        return "llm_failed";
    }

    private boolean hasMinimaxKey() {
        String key = properties.getApiKey();
        return key != null && !key.isBlank();
    }

    private IntentDecision classifyByLlm(String question, boolean explicitCompanyHint) throws Exception {
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
        return new IntentDecision(intent, confidence, reason, queryType, personName, companyHints, roleFocus);
    }

    private IntentDecision enrichWithRuleFallback(
            IntentDecision base,
            String question,
            boolean explicitCompanyHint,
            String source
    ) {
        String queryType = base.queryType();
        String personName = base.personName();
        List<String> companyHints = base.companyHints() == null ? List.of() : new ArrayList<>(base.companyHints());
        String roleFocus = base.roleFocus() == null || base.roleFocus().isBlank() ? "any" : base.roleFocus();

        if (personName.isBlank()) {
            String rulePerson = graphContextService.extractPersonHintForRoleQuery(question);
            if (rulePerson != null) {
                personName = rulePerson;
            }
        }
        if (companyHints.isEmpty()) {
            companyHints = graphContextService.extractCompanyHints(question);
        }
        if (explicitCompanyHint && companyHints.isEmpty()) {
            companyHints = graphContextService.extractCompanyHints(question);
        }
        if (queryType.isBlank()) {
            queryType = inferQueryType(question, personName, roleFocus);
        }
        if ("any".equals(roleFocus)) {
            roleFocus = inferRoleFocus(question);
        }

        String reason = base.reason();
        if (!reason.contains(source)) {
            reason = source + "_" + reason;
        }
        return new IntentDecision(
                base.intent(),
                base.confidence(),
                reason,
                queryType,
                personName,
                List.copyOf(companyHints),
                roleFocus
        );
    }

    private static String inferQueryType(String question, String personName, String roleFocus) {
        String q = question.toLowerCase(Locale.ROOT);
        if (!personName.isBlank()
                && (q.contains("法人") || q.contains("董事") || q.contains("监事") || q.contains("任职") || q.contains("担任"))) {
            if (q.contains("哪些") || q.contains("多少") || q.contains("列出") || q.contains("名单")) {
                return "person_role_list";
            }
        }
        if (q.contains("股东") || q.contains("股权") || q.contains("持股")) {
            return "shareholder";
        }
        if (q.contains("多少") || q.contains("统计") || q.contains("数量") || q.contains("总数")) {
            return "aggregate";
        }
        if (q.contains("流程") || q.contains("制度") || q.contains("规定")) {
            return "policy";
        }
        return "";
    }

    private static String inferRoleFocus(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("法定代表人") || q.contains("法人")) {
            return "legal_rep";
        }
        if (q.contains("董事")) {
            return "director";
        }
        if (q.contains("监事")) {
            return "supervisor";
        }
        if (q.contains("股东")) {
            return "shareholder";
        }
        return "any";
    }

    private IntentDecision classifyByRule(String question, boolean explicitCompanyHint, String reasonPrefix) {
        String q = question.toLowerCase(Locale.ROOT);
        boolean relationIntent = q.contains("股东")
                || q.contains("股权")
                || q.contains("穿透")
                || q.contains("关系")
                || q.contains("关联")
                || q.contains("总公司")
                || q.contains("分公司")
                || q.contains("法人")
                || q.contains("董事")
                || q.contains("监事")
                || q.contains("任职")
                || q.contains("担任");
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

        String rulePerson = graphContextService.extractPersonHintForRoleQuery(question);
        String personName = rulePerson != null ? rulePerson : "";
        String queryType = inferQueryType(question, personName, inferRoleFocus(question));
        List<String> companyHints = graphContextService.extractCompanyHints(question);
        String roleFocus = inferRoleFocus(question);

        if (relationIntent && semanticIntent) {
            return new IntentDecision("hybrid", 0.72, reasonPrefix + "_hybrid_relation_semantic",
                    queryType, personName, companyHints, roleFocus);
        }
        if (sqlIntent && (mysqlIntent || relationIntent || explicitCompanyHint)) {
            return new IntentDecision("sql", 0.76, reasonPrefix + "_sql_analytic_or_filtered",
                    queryType, personName, companyHints, roleFocus);
        }
        if (mysqlIntent && !relationIntent) {
            return new IntentDecision("mysql", 0.72, reasonPrefix + "_mysql_structured_raw",
                    queryType, personName, companyHints, roleFocus);
        }
        if (relationIntent || explicitCompanyHint) {
            return new IntentDecision("graph", 0.70, reasonPrefix + "_graph_relation_or_entity",
                    queryType, personName, companyHints, roleFocus);
        }
        if (docIntent) {
            return new IntentDecision("document", 0.68, reasonPrefix + "_document_policy_flow",
                    queryType, personName, companyHints, roleFocus);
        }
        if (semanticIntent) {
            return new IntentDecision("vector", 0.66, reasonPrefix + "_vector_semantic",
                    queryType, personName, companyHints, roleFocus);
        }
        return new IntentDecision("unknown", 0.45, reasonPrefix + "_unknown_out_of_scope",
                queryType, personName, companyHints, roleFocus);
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

package com.qa.demo.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.ScenarioRuleEngine;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按 queryType / 证据形态加载「输出契约」附录，与通用 system prompt 解耦。
 * 业务字段名与问法模板维护在 classpath:qa/answer-output-contracts.json。
 */
@Component
public class AnswerOutputContractRegistry {

    private final Map<String, String> contractsByKey;
    private final ScenarioRuleEngine ruleEngine;

    public AnswerOutputContractRegistry(ObjectMapper objectMapper, ScenarioRuleEngine ruleEngine) {
        this.contractsByKey = load(objectMapper);
        this.ruleEngine = ruleEngine;
    }

    public String contractForQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return contractsByKey.getOrDefault("default", "");
        }
        String key = queryType.trim().toLowerCase(Locale.ROOT);
        return contractsByKey.getOrDefault(key, contractsByKey.getOrDefault("default", ""));
    }

    /**
     * 解析本轮应答应附带的输出契约：优先 queryType，其次按证据来源兜底。
     */
    public String resolveContract(IntentDecision intent, List<ContextChunk> evidence) {
        return resolveContract(intent, evidence, "");
    }

    public String resolveContract(IntentDecision intent, List<ContextChunk> evidence, String question) {
        if (ruleEngine.isCorrectionQuestion(question)) {
            String correction = contractsByKey.getOrDefault("entity_correction", "");
            if (!correction.isBlank()) {
                return correction;
            }
        }
        String fromType = "";
        if (intent != null && intent.queryType() != null && !intent.queryType().isBlank()) {
            fromType = contractForQueryType(intent.queryType());
        }
        if (!fromType.isBlank() && !fromType.equals(contractsByKey.getOrDefault("default", ""))) {
            return fromType;
        }
        if (evidence != null && evidence.stream().anyMatch(this::isEmployeeIdentityEvidence)) {
            String identity = contractsByKey.getOrDefault("employee_identity", "");
            if (!identity.isBlank()) {
                return identity;
            }
        }
        if (evidence != null && evidence.stream().anyMatch(this::isEnterpriseCanonicalEvidence)) {
            String canonical = contractsByKey.getOrDefault("enterprise_canonical", "");
            if (!canonical.isBlank()) {
                return canonical;
            }
        }
        return fromType.isBlank() ? contractsByKey.getOrDefault("default", "") : fromType;
    }

    public String composeSystemPrompt(String basePrompt, IntentDecision intent, List<ContextChunk> evidence) {
        return composeSystemPrompt(basePrompt, intent, evidence, "");
    }

    public String composeSystemPrompt(String basePrompt, IntentDecision intent, List<ContextChunk> evidence, String question) {
        String contract = resolveContract(intent, evidence, question);
        if (contract == null || contract.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n\n【输出契约】\n" + contract.trim();
    }

    private boolean isEmployeeIdentityEvidence(ContextChunk chunk) {
        return chunk != null
                && ("employee_identity".equals(chunk.source()) || "employee_base".equals(chunk.source()))
                && chunk.snippet() != null
                && !chunk.snippet().isBlank();
    }

    private boolean isEnterpriseCanonicalEvidence(ContextChunk chunk) {
        return chunk != null
                && EnterpriseCanonicalFactsRegistry.SOURCE.equals(chunk.source())
                && chunk.snippet() != null
                && !chunk.snippet().isBlank();
    }

    private static Map<String, String> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("qa/answer-output-contracts.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            Map<String, String> map = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry ->
                    map.put(entry.getKey(), entry.getValue().asText("").trim())
            );
            return Map.copyOf(map);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load qa/answer-output-contracts.json", ex);
        }
    }
}

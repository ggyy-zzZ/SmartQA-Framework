package com.qa.demo.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalStrategy;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.domain.ScenarioRuleEngine;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按检索策略 / 信息需求 / 证据形态加载「输出契约」附录，与通用 system prompt 解耦。
 */
@Component
public class AnswerOutputContractRegistry {

    private final Map<String, String> contractsByKey;
    private final ScenarioRuleEngine ruleEngine;

    public AnswerOutputContractRegistry(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader, ScenarioRuleEngine ruleEngine) {
        this.contractsByKey = load(objectMapper, configLoader);
        this.ruleEngine = ruleEngine;
    }

    public String contractForKey(String contractKey) {
        if (contractKey == null || contractKey.isBlank()) {
            return contractsByKey.getOrDefault("default", "");
        }
        String key = contractKey.trim().toLowerCase(Locale.ROOT);
        return contractsByKey.getOrDefault(key, contractsByKey.getOrDefault("default", ""));
    }

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
        String fromKey = resolveContractKey(intent);
        String fromType = contractForKey(fromKey);
        if (!fromType.isBlank() && !fromType.equals(contractsByKey.getOrDefault("default", ""))) {
            return fromType;
        }
        if (evidence != null && evidence.stream().anyMatch(this::isEmployeeIdentityEvidence)) {
            String identity = contractsByKey.getOrDefault("identity_resolution", "");
            if (!identity.isBlank()) {
                return identity;
            }
        }
        if (evidence != null && evidence.stream().anyMatch(this::isEnterpriseCanonicalEvidence)) {
            String canonical = contractsByKey.getOrDefault("canonical_fact", "");
            if (!canonical.isBlank()) {
                return canonical;
            }
        }
        return fromType.isBlank() ? contractsByKey.getOrDefault("default", "") : fromType;
    }

    public String resolveContractKey(IntentDecision intent) {
        return resolveContractKey(intent, null);
    }

    public String resolveContractKey(IntentDecision intent, InformationNeed need) {
        if (need != null && need.hasFacet()) {
            if (need.isTypeCatalog()) {
                return "type_catalog";
            }
            if (need.isAggregate()) {
                return "aggregate";
            }
            String facet = need.facet().trim().toLowerCase(Locale.ROOT);
            String granularity = need.granularity() == null ? "" : need.granularity().trim().toLowerCase(Locale.ROOT);
            if ("role".equals(facet) && "list".equals(granularity)) {
                return "role_list";
            }
            if ("certificate".equals(facet) && InformationNeed.GRANULARITY_INSTANCE.equals(granularity)) {
                if (intent != null && intent.hasPersonFocus()) {
                    return "certificate_instance";
                }
                return "certificate_instance_company";
            }
            if ("profile".equals(facet)) {
                return "profile_instance";
            }
        }
        if (intent == null) {
            return "default";
        }
        RetrievalStrategy strategy = intent.resolvedRetrievalStrategy();
        return switch (strategy) {
            case GRAPH_RELATIONAL -> "role_list";
            case STRUCTURED_LIST -> intent.hasPersonFocus() ? "certificate_instance" : "certificate_instance_company";
            case INSTANCE_FACT -> "profile_instance";
            case AGGREGATE_COUNT -> "aggregate";
            case TYPE_CATALOG -> "type_catalog";
            case SEMANTIC_RAG, CLARIFY, UNKNOWN -> "default";
        };
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

    private static Map<String, String> load(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) {
        try {
            JsonNode root = configLoader.readTree("answer-output-contracts");
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

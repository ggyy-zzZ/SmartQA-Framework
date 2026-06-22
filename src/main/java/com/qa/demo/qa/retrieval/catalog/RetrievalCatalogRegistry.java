package com.qa.demo.qa.retrieval.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalExecutionProfile;
import com.qa.demo.qa.core.RetrievalStrategy;

/**
 * 加载检索证据维度目录，提供维度匹配与闸门 schema 查询。
 */
@Component
public class RetrievalCatalogRegistry {

    /** type_catalog 证据中 catalog schema 条数占比下限（避免 1 条 catalog + 大量公司行过闸）。 */
    private static final double TYPE_CATALOG_GATE_MIN_RATIO = 0.5;

    private final RetrievalCatalogConfig config;

    @Autowired
    public RetrievalCatalogRegistry(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) {
        try {
            this.config = objectMapper.treeToValue(
                    configLoader.readTree("retrieval-catalog"), RetrievalCatalogConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load retrieval-catalog", e);
        }
    }

    public RetrievalCatalogConfig config() {
        return config;
    }

    public List<RetrievalCatalogConfig.DimensionDef> matchDimensions(InformationNeed need) {
        if (need == null || !need.hasFacet()) {
            return List.of();
        }
        List<RetrievalCatalogConfig.DimensionDef> matched = new ArrayList<>();
        String facet = need.facet().trim().toLowerCase(Locale.ROOT);
        String granularity = need.granularity() == null ? "" : need.granularity().trim().toLowerCase(Locale.ROOT);
        for (RetrievalCatalogConfig.DimensionDef dim : config.getDimensions()) {
            if (dim == null || dim.getDimensionId() == null || dim.getDimensionId().isBlank()) {
                continue;
            }
            RetrievalCatalogConfig.DimensionMatch match = dim.getMatch();
            if (match == null) {
                continue;
            }
            if (!containsIgnoreCase(match.getFacets(), facet)) {
                continue;
            }
            if (!granularity.isBlank() && !containsIgnoreCase(match.getGranularities(), granularity)) {
                continue;
            }
            matched.add(dim);
        }
        return matched;
    }

    public Set<String> requiredSchemasForNeed(InformationNeed need) {
        RetrievalCatalogConfig.GateRule rule = matchingGateRule(need);
        if (rule == null || rule.getRequiredSchemas().isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(rule.getRequiredSchemas());
    }

    public boolean hasGateRule(InformationNeed need) {
        return matchingGateRule(need) != null;
    }

    public boolean satisfiesGate(InformationNeed need, List<ContextChunk> evidence) {
        RetrievalCatalogConfig.GateRule rule = matchingGateRule(need);
        if (rule == null) {
            return true;
        }
        if (need != null && need.isTypeCatalog()) {
            return satisfiesTypeCatalogCoverage(evidence, rule);
        }
        if (!rule.getRequiredSchemas().isEmpty() && hasEvidenceForSchemas(evidence, rule.getRequiredSchemas())) {
            return true;
        }
        return !rule.getAcceptableSources().isEmpty()
                && hasEvidenceForSources(evidence, rule.getAcceptableSources());
    }

    private boolean satisfiesTypeCatalogCoverage(List<ContextChunk> evidence, RetrievalCatalogConfig.GateRule rule) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        if (!rule.getRequiredSchemas().isEmpty()) {
            long catalogHits = evidence.stream()
                    .filter(c -> c != null && c.snippet() != null && !c.snippet().isBlank())
                    .filter(c -> matchesAnySchema(c, rule.getRequiredSchemas()))
                    .count();
            if (catalogHits == 0) {
                return false;
            }
            int total = evidence.size();
            if (total <= 3) {
                return catalogHits * 2 >= total;
            }
            return (double) catalogHits / (double) total >= TYPE_CATALOG_GATE_MIN_RATIO;
        }
        return true;
    }

    private static boolean matchesAnySchema(ContextChunk chunk, List<String> schemaIds) {
        if (chunk == null || schemaIds == null) {
            return false;
        }
        String schema = chunk.evidenceSchema();
        if (schema == null || schema.isBlank()) {
            return false;
        }
        for (String schemaId : schemaIds) {
            if (schemaId != null && schemaId.equalsIgnoreCase(schema.trim())) {
                return true;
            }
        }
        return false;
    }

    private RetrievalCatalogConfig.GateRule matchingGateRule(InformationNeed need) {
        if (need == null) {
            return null;
        }
        String facet = need.facet() == null ? "" : need.facet().trim().toLowerCase(Locale.ROOT);
        String granularity = need.granularity() == null ? "" : need.granularity().trim().toLowerCase(Locale.ROOT);
        for (RetrievalCatalogConfig.GateRule rule : config.getGateRules()) {
            if (rule == null) {
                continue;
            }
            if (!granularity.isBlank() && !containsIgnoreCase(rule.getGranularities(), granularity)) {
                continue;
            }
            if (!rule.getFacets().isEmpty() && !containsIgnoreCase(rule.getFacets(), facet)) {
                continue;
            }
            if (!rule.getRequiredSchemas().isEmpty() || !rule.getAcceptableSources().isEmpty()) {
                return rule;
            }
        }
        return null;
    }

    private static boolean hasEvidenceForSchemas(List<ContextChunk> evidence, List<String> schemaIds) {
        if (evidence == null || evidence.isEmpty() || schemaIds == null || schemaIds.isEmpty()) {
            return false;
        }
        for (ContextChunk chunk : evidence) {
            if (chunk == null || chunk.snippet() == null || chunk.snippet().isBlank()) {
                continue;
            }
            String schema = chunk.evidenceSchema();
            if (schema == null || schema.isBlank()) {
                continue;
            }
            for (String schemaId : schemaIds) {
                if (schemaId != null && schemaId.equalsIgnoreCase(schema.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasEvidenceForSources(List<ContextChunk> evidence, List<String> sources) {
        if (evidence == null || evidence.isEmpty() || sources == null || sources.isEmpty()) {
            return false;
        }
        for (ContextChunk chunk : evidence) {
            if (chunk == null || chunk.snippet() == null || chunk.snippet().isBlank()) {
                continue;
            }
            String source = chunk.source();
            if (source == null || source.isBlank()) {
                continue;
            }
            for (String allowed : sources) {
                if (allowed != null && allowed.equalsIgnoreCase(source.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    public RetrievalExecutionProfile executionFor(InformationNeed need, IntentDecision intent) {
        RetrievalCatalogConfig.NeedExecutionProfile profile = matchedProfile(need, intent);
        if (profile != null && profile.getExecution() != null) {
            RetrievalExecutionProfile fromCatalog = RetrievalExecutionProfile.fromTemplate(profile.getExecution());
            if (StructuredExecutionProfiles.isDedicated(fromCatalog)) {
                return fromCatalog;
            }
        }
        RetrievalExecutionProfile fallback = StructuredExecutionProfiles.forNeed(need, intent);
        if (StructuredExecutionProfiles.isDedicated(fallback)) {
            return fallback;
        }
        if (profile != null && profile.getExecution() != null) {
            return RetrievalExecutionProfile.fromTemplate(profile.getExecution());
        }
        return RetrievalExecutionProfile.DEFAULT;
    }

    public RetrievalCatalogConfig.NeedExecutionProfile matchedProfile(InformationNeed need, IntentDecision intent) {
        if (need == null || !need.hasFacet()) {
            return null;
        }
        for (RetrievalCatalogConfig.NeedExecutionProfile profile : config.getNeedExecutionProfiles()) {
            if (profile == null) {
                continue;
            }
            if (matchesExecutionProfile(profile.getMatch(), need, intent)) {
                return profile;
            }
        }
        return null;
    }

    public String thinkingMessageFor(InformationNeed need, IntentDecision intent) {
        RetrievalCatalogConfig.NeedExecutionProfile profile = matchedProfile(need, intent);
        if (profile != null && profile.getBehaviors() != null) {
            String message = profile.getBehaviors().getThinkingMessage();
            if (message != null && !message.isBlank()) {
                return message.strip();
            }
        }
        if (need != null && need.granularity() != null && !need.granularity().isBlank()) {
            String byGranularity = config.getThinkingMessagesByGranularity().get(need.granularity().trim());
            if (byGranularity != null && !byGranularity.isBlank()) {
                return byGranularity.strip();
            }
        }
        return null;
    }

    public boolean requiresPersonClarification(InformationNeed need, IntentDecision intent) {
        RetrievalCatalogConfig.NeedExecutionProfile profile = matchedProfile(need, intent);
        return profile != null
                && profile.getBehaviors() != null
                && profile.getBehaviors().isPersonClarification();
    }

    public boolean preferCompiledDocsForNeed(InformationNeed need, IntentDecision intent) {
        RetrievalCatalogConfig.NeedExecutionProfile profile = matchedProfile(need, intent);
        return profile != null
                && profile.getBehaviors() != null
                && profile.getBehaviors().isPreferCompiledDocs();
    }

    public boolean shouldPreserveAgainstLlm(
            RetrievalStrategy strategy,
            InformationNeed need,
            IntentDecision intent
    ) {
        if (need == null || strategy == null || !isLlmStrategyConflict(strategy)) {
            return false;
        }
        if (matchesPreserveReasonPrefix(need.reason())) {
            return true;
        }
        RetrievalCatalogConfig.NeedExecutionProfile profile = matchedProfile(need, intent);
        return profile != null
                && profile.getBehaviors() != null
                && profile.getBehaviors().isPreserveAgainstLlmSemantic()
                && hasStructuredInferenceReason(need.reason());
    }

    private boolean matchesPreserveReasonPrefix(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String trimmed = reason.strip();
        for (String prefix : config.getLlmMergePreserveReasonPrefixes()) {
            if (prefix != null && !prefix.isBlank() && trimmed.startsWith(prefix.strip())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStructuredInferenceReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String trimmed = reason.strip();
        return trimmed.startsWith("inference_rule:")
                || trimmed.startsWith("intent_slots:");
    }

    private static boolean isLlmStrategyConflict(RetrievalStrategy strategy) {
        return strategy == RetrievalStrategy.SEMANTIC_RAG
                || strategy == RetrievalStrategy.INSTANCE_FACT
                || strategy == RetrievalStrategy.STRUCTURED_LIST;
    }

    private static boolean matchesExecutionProfile(
            RetrievalCatalogConfig.NeedExecutionMatch match,
            InformationNeed need,
            IntentDecision intent
    ) {
        if (match == null || need == null) {
            return false;
        }
        String facet = need.facet() == null ? "" : need.facet().trim().toLowerCase(Locale.ROOT);
        String granularity = need.granularity() == null ? "" : need.granularity().trim().toLowerCase(Locale.ROOT);
        if (!match.getFacets().isEmpty() && !containsIgnoreCase(match.getFacets(), facet)) {
            return false;
        }
        if (!match.getGranularities().isEmpty() && !containsIgnoreCase(match.getGranularities(), granularity)) {
            return false;
        }
        if (match.getListExpected() != null && match.getListExpected() != need.listExpected()) {
            return false;
        }
        if (match.getRequiresPerson() != null) {
            boolean hasPerson = intent != null && intent.hasPersonFocus();
            if (match.getRequiresPerson() && !hasPerson) {
                return false;
            }
            if (!match.getRequiresPerson() && hasPerson) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsIgnoreCase(List<String> values, String token) {
        if (values == null || values.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        for (String v : values) {
            if (v != null && v.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }
}

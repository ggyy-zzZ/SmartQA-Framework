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
import com.qa.demo.qa.core.RetrievalExecutionProfile;

/**
 * 加载检索证据维度目录，提供维度匹配与闸门 schema 查询。
 */
@Component
public class RetrievalCatalogRegistry {

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
        if (!rule.getRequiredSchemas().isEmpty() && hasEvidenceForSchemas(evidence, rule.getRequiredSchemas())) {
            return true;
        }
        return !rule.getAcceptableSources().isEmpty()
                && hasEvidenceForSources(evidence, rule.getAcceptableSources());
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

    public RetrievalCatalogConfig.NeedTemplate mapQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return null;
        }
        return config.getQueryTypeMapping().get(queryType.trim());
    }

    public RetrievalExecutionProfile executionFor(String queryType) {
        RetrievalCatalogConfig.NeedTemplate template = mapQueryType(queryType);
        if (template == null || template.getExecution() == null) {
            return RetrievalExecutionProfile.DEFAULT;
        }
        return RetrievalExecutionProfile.fromTemplate(template.getExecution());
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

package com.qa.demo.qa.retrieval.catalog;

import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalStrategy;
import com.qa.demo.qa.retrieval.filter.FilterFieldQuestionSupport;
import org.springframework.stereotype.Component;

/**
 * 合并规则/配置推断的 {@link InformationNeed} 与 LLM {@link RetrievalStrategy}；
 * 保留策略由 retrieval-catalog.json 的 behaviors 与 llmMergePreserveReasonPrefixes 驱动。
 */
@Component
public class InformationNeedMerger {

    private final RetrievalCatalogRegistry catalogRegistry;

    public InformationNeedMerger(RetrievalCatalogRegistry catalogRegistry) {
        this.catalogRegistry = catalogRegistry;
    }

    public InformationNeed merge(
            RetrievalStrategy strategy,
            double confidence,
            InformationNeed inferred,
            IntentDecision intent
    ) {
        InformationNeed base = inferred != null ? inferred : InformationNeed.defaultSemantic();
        if (strategy == null || strategy == RetrievalStrategy.UNKNOWN) {
            return base;
        }
        String reason = "llm_retrieval_strategy:" + strategy.token();
        if (strategy == RetrievalStrategy.TYPE_CATALOG) {
            if (base.isTypeCatalog() && base.hasFacet() && !isGenericCatalogFacet(base.facet())) {
                return new InformationNeed(
                        base.facet(),
                        InformationNeed.GRANULARITY_TYPE_CATALOG,
                        true,
                        Math.max(confidence, base.confidence()),
                        reason + ";facet_from:" + nullToEmpty(base.reason())
                );
            }
            if (base.hasFacet()
                    && !isGenericCatalogFacet(base.facet())
                    && InformationNeed.GRANULARITY_INSTANCE.equalsIgnoreCase(base.granularity())) {
                return base;
            }
        }
        if (base.isTypeCatalog()
                && (strategy == RetrievalStrategy.SEMANTIC_RAG || strategy == RetrievalStrategy.UNKNOWN)) {
            return base;
        }
        if (FilterFieldQuestionSupport.isFilterThresholdNeed(base)) {
            return base;
        }
        if (shouldPreservePronounNeed(base)) {
            return base;
        }
        if (isRoleListNeed(base) && (strategy == RetrievalStrategy.STRUCTURED_LIST
                || strategy == RetrievalStrategy.GRAPH_RELATIONAL)) {
            return base;
        }
        if (catalogRegistry.shouldPreserveAgainstLlm(strategy, base, intent)) {
            return base;
        }
        if (base.isTypeCatalog() && hasStructuredCatalogReason(base.reason())) {
            return base;
        }
        return InformationNeed.fromRetrievalStrategy(strategy, confidence, reason);
    }

    private static boolean hasStructuredCatalogReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return reason.startsWith("inference_forced:")
                || reason.startsWith("inference_rule:");
    }

    private static boolean shouldPreservePronounNeed(InformationNeed need) {
        if (need == null || need.reason() == null) {
            return false;
        }
        String reason = need.reason();
        return reason.contains("pronoun_certificate") || reason.contains("pronoun_role");
    }

    private static boolean isRoleListNeed(InformationNeed need) {
        return need != null
                && need.hasFacet()
                && "role".equalsIgnoreCase(need.facet().trim())
                && "list".equalsIgnoreCase(need.granularity() == null ? "" : need.granularity().trim());
    }

    private static boolean isGenericCatalogFacet(String facet) {
        if (facet == null || facet.isBlank()) {
            return true;
        }
        String normalized = facet.trim().toLowerCase();
        return "catalog".equals(normalized) || "list".equals(normalized);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

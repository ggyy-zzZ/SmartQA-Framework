package com.qa.demo.qa.retrieval.catalog;

import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 从问句与意图决策推断信息需求（规则来自 retrieval-catalog.json，无业务硬编码）。
 */
@Component
public class NeedInferenceService {

    private final RetrievalCatalogRegistry catalogRegistry;

    public NeedInferenceService(RetrievalCatalogRegistry catalogRegistry) {
        this.catalogRegistry = catalogRegistry;
    }

    public InformationNeed infer(String question, IntentDecision intent) {
        String q = question == null ? "" : question.strip();
        if (!q.isBlank()) {
            InformationNeed fromRules = matchRules(q);
            if (fromRules != null) {
                return fromRules;
            }
            if (looksLikeTypeCatalogQuestion(q)) {
                return new InformationNeed(
                        "certificate",
                        InformationNeed.GRANULARITY_TYPE_CATALOG,
                        true,
                        0.88,
                        "inference_heuristic:type_catalog"
                );
            }
        }
        if (intent != null && intent.queryType() != null && !intent.queryType().isBlank()) {
            RetrievalCatalogConfig.NeedTemplate mapped = catalogRegistry.mapQueryType(intent.queryType());
            if (mapped != null && mapped.getFacet() != null && !mapped.getFacet().isBlank()) {
                return new InformationNeed(
                        mapped.getFacet(),
                        mapped.getGranularity(),
                        mapped.isListExpected(),
                        intent.confidence(),
                        "query_type_mapping:" + intent.queryType()
                );
            }
        }
        return InformationNeed.defaultSemantic();
    }

    private InformationNeed matchRules(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        for (RetrievalCatalogConfig.NeedInferenceRule rule : catalogRegistry.config().getNeedInferenceRules()) {
            if (rule == null || rule.getNeed() == null) {
                continue;
            }
            if (keywordsMatch(lower, rule.getExcludeKeywords(), true)) {
                continue;
            }
            if (!rule.getAllKeywords().isEmpty() && !keywordsMatch(lower, rule.getAllKeywords(), false)) {
                continue;
            }
            boolean anyOk = rule.getAnyKeywords().isEmpty() || keywordsMatch(lower, rule.getAnyKeywords(), true);
            if (!anyOk) {
                continue;
            }
            if (!rule.getContextKeywords().isEmpty() && !keywordsMatch(lower, rule.getContextKeywords(), true)) {
                continue;
            }
            RetrievalCatalogConfig.NeedTemplate need = rule.getNeed();
            String reason = rule.getId() == null ? "inference_rule" : "inference_rule:" + rule.getId();
            return new InformationNeed(
                    need.getFacet(),
                    need.getGranularity(),
                    need.isListExpected(),
                    0.82,
                    reason
            );
        }
        return null;
    }

    /**
     * @param anyMatch true=任一命中即可；false=全部须命中
     */
    private static boolean keywordsMatch(String text, List<String> keywords, boolean anyMatch) {
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }
        int hits = 0;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            if (text.contains(kw.toLowerCase(Locale.ROOT))) {
                if (anyMatch) {
                    return true;
                }
                hits++;
            }
        }
        return !anyMatch && hits == keywords.size();
    }

    private static boolean looksLikeTypeCatalogQuestion(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        boolean typeIntent = lower.contains("类型")
                || lower.contains("种类")
                || lower.contains("什么证")
                || lower.contains("哪些证")
                || lower.contains("什么类型")
                || lower.contains("有哪些类型");
        boolean certContext = lower.contains("证照")
                || lower.contains("资质")
                || lower.contains("许可证")
                || lower.contains("执照")
                || lower.contains("备案");
        return typeIntent && certContext;
    }
}

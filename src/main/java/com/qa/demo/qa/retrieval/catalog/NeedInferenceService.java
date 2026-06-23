package com.qa.demo.qa.retrieval.catalog;

import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalStrategy;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import com.qa.demo.qa.retrieval.certificate.CertificateListQuestionSupport;
import com.qa.demo.qa.retrieval.filter.FilterFieldQuestionSupport;
import com.qa.demo.qa.retrieval.parent.ParentScopedCompanyListSupport;
import com.qa.demo.qa.retrieval.region.RegionListQuestionSupport;
import com.qa.demo.qa.retrieval.structured.RegionResolverService;

/**
 * 从问句与意图决策推断信息需求（规则来自 retrieval-catalog.json，无业务硬编码）。
 */
@Component
public class NeedInferenceService {

    private final RetrievalCatalogRegistry catalogRegistry;
    private final ConversationScopeSupport scopeSupport;
    private final BusinessRulesConfig businessRulesConfig;
    private final RegionResolverService regionResolver;
    private final EnterpriseCanonicalFactsRegistry canonicalFactsRegistry;

    public NeedInferenceService(
            RetrievalCatalogRegistry catalogRegistry,
            ConversationScopeSupport scopeSupport,
            BusinessRulesConfig businessRulesConfig,
            RegionResolverService regionResolver,
            EnterpriseCanonicalFactsRegistry canonicalFactsRegistry
    ) {
        this.catalogRegistry = catalogRegistry;
        this.scopeSupport = scopeSupport;
        this.businessRulesConfig = businessRulesConfig;
        this.regionResolver = regionResolver;
        this.canonicalFactsRegistry = canonicalFactsRegistry;
    }

    public InformationNeed infer(String question, IntentDecision intent) {
        String q = question == null ? "" : question.strip();
        if (!q.isBlank()) {
            InformationNeed filterNeed = inferFilterThresholdNeed(q);
            if (filterNeed != null) {
                return filterNeed;
            }
            if (CertificateListQuestionSupport.isUnscopedGlobalCertificateList(q)) {
                return CertificateListQuestionSupport.globalCertificateNeed();
            }
            InformationNeed pronounCert = inferPronounCertificateList(q);
            if (pronounCert != null) {
                return pronounCert;
            }
            InformationNeed pronounRole = inferPronounRoleList(q);
            if (pronounRole != null) {
                return pronounRole;
            }
            if (RegionListQuestionSupport.isRegionCompanyList(q, regionResolver)) {
                return RegionListQuestionSupport.regionCompanyNeed();
            }
            if (ParentScopedCompanyListSupport.isParentScopedCompanyList(q, canonicalFactsRegistry)) {
                return ParentScopedCompanyListSupport.parentCompanyListNeed();
            }
            InformationNeed forcedCatalog = inferForcedCatalogNeed(q);
            if (forcedCatalog != null) {
                return forcedCatalog;
            }
            InformationNeed fromRules = matchRules(q);
            if (fromRules != null) {
                // 规则已命中 type_catalog 时不再让 queryType/companyHints 覆盖（避免 MySQL 旧配置缺 catalog markers）
                if (fromRules.isTypeCatalog()) {
                    return fromRules;
                }
                if (!shouldPreferIntentSlotsNeed(q, intent, fromRules)) {
                    return fromRules;
                }
            }
            if (looksLikeTypeCatalogQuestion(q)) {
                InformationNeed heuristic = new InformationNeed(
                        "certificate",
                        InformationNeed.GRANULARITY_TYPE_CATALOG,
                        true,
                        0.88,
                        "inference_heuristic:type_catalog"
                );
                if (!shouldPreferIntentSlotsNeed(q, intent, heuristic)) {
                    return heuristic;
                }
            }
        }
        InformationNeed mapped = inferFromIntentSlots(q, intent);
        if (mapped != null) {
            return mapped;
        }
        return InformationNeed.defaultSemantic();
    }

    /**
     * 由检索策略与槽位推断 need，不再依赖 queryType 映射表。
     */
    private InformationNeed inferFromIntentSlots(String question, IntentDecision intent) {
        if (intent == null) {
            return null;
        }
        RetrievalStrategy strategy = intent.resolvedRetrievalStrategy();
        double confidence = intent.confidence();
        if (strategy == RetrievalStrategy.AGGREGATE_COUNT) {
            return new InformationNeed(
                    "aggregate",
                    InformationNeed.GRANULARITY_AGGREGATE,
                    false,
                    confidence,
                    "intent_slots:aggregate_count"
            );
        }
        if (strategy == RetrievalStrategy.TYPE_CATALOG) {
            return new InformationNeed(
                    "catalog",
                    InformationNeed.GRANULARITY_TYPE_CATALOG,
                    true,
                    confidence,
                    "intent_slots:type_catalog"
            );
        }
        if (intent.hasPersonFocus()) {
            if (isRoleFocus(intent.roleFocus())
                    || strategy == RetrievalStrategy.GRAPH_RELATIONAL
                    || looksLikeRoleRelationQuestion(question)) {
                return new InformationNeed(
                        "role",
                        "list",
                        true,
                        confidence,
                        "inference_rule:role_list"
                );
            }
            if (strategy == RetrievalStrategy.STRUCTURED_LIST && looksLikeCertificateListQuestion(question)) {
                return new InformationNeed(
                        "certificate",
                        InformationNeed.GRANULARITY_INSTANCE,
                        true,
                        confidence,
                        "inference_rule:certificate_instance"
                );
            }
        }
        if (intent.hasCompanyHints() && !intent.hasPersonFocus()) {
            if (strategy == RetrievalStrategy.INSTANCE_FACT
                    || strategy == RetrievalStrategy.STRUCTURED_LIST) {
                return new InformationNeed(
                        "profile",
                        InformationNeed.GRANULARITY_INSTANCE,
                        false,
                        confidence,
                        "intent_slots:company_profile"
                );
            }
        }
        if (strategy == RetrievalStrategy.SEMANTIC_RAG) {
            return InformationNeed.defaultSemantic();
        }
        return null;
    }

    private static boolean isRoleFocus(String roleFocus) {
        if (roleFocus == null || roleFocus.isBlank() || "any".equalsIgnoreCase(roleFocus)) {
            return false;
        }
        return true;
    }

    private static boolean looksLikeRoleRelationQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        return q.contains("法人") || q.contains("法定代表人")
                || q.contains("董事") || q.contains("监事")
                || q.contains("任职") || q.contains("担任")
                || q.contains("股东") || q.contains("股权");
    }

    private static InformationNeed inferPronounCertificateList(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (!containsAny(lower, "他有哪些", "她有哪些", "它有哪些", "其有哪些")) {
            return null;
        }
        if (!looksLikeCertificateListQuestion(question)) {
            return null;
        }
        return new InformationNeed(
                "certificate",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.86,
                "inference_rule:pronoun_certificate_list"
        );
    }

    private static InformationNeed inferPronounRoleList(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (!containsAny(lower, "他", "她", "其")) {
            return null;
        }
        if (!looksLikeRoleRelationQuestion(question)) {
            return null;
        }
        return new InformationNeed(
                "role",
                "list",
                true,
                0.86,
                "inference_rule:pronoun_role_list"
        );
    }

    private static boolean looksLikeCertificateListQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        boolean cert = q.contains("证照") || q.contains("资质") || q.contains("许可证")
                || q.contains("执照") || q.contains("备案");
        boolean list = q.contains("哪些") || q.contains("列出") || q.contains("多少");
        return cert && list;
    }

    /** catalog 问法优先于 queryType 映射，避免「公司经营状态有哪些种类」被 semantic 覆盖。 */
    private InformationNeed inferForcedCatalogNeed(String question) {
        if (question.contains("经营状态")
                && (question.contains("种类") || question.contains("类型") || question.contains("哪些"))) {
            return new InformationNeed(
                    "profile",
                    InformationNeed.GRANULARITY_TYPE_CATALOG,
                    true,
                    0.92,
                    "inference_heuristic:type_catalog"
            );
        }
        if (!scopeSupport.isCatalogQuestion(question)) {
            return null;
        }
        if (question.contains("经营状态")) {
            return new InformationNeed(
                    "profile",
                    InformationNeed.GRANULARITY_TYPE_CATALOG,
                    true,
                    0.92,
                    "inference_heuristic:type_catalog"
            );
        }
        if (looksLikeTypeCatalogQuestion(question)) {
            return new InformationNeed(
                    "certificate",
                    InformationNeed.GRANULARITY_TYPE_CATALOG,
                    true,
                    0.88,
                    "inference_heuristic:type_catalog"
            );
        }
        return null;
    }

    private InformationNeed inferFilterThresholdNeed(String question) {
        List<BusinessRulesConfig.FilterFieldCoverageRule> rules = businessRulesConfig.getFilterFieldCoverageRules();
        if (rules == null || rules.isEmpty()) {
            rules = FilterFieldQuestionSupport.defaultRules();
        }
        return FilterFieldQuestionSupport.matchThresholdRule(question, rules)
                .map(FilterFieldQuestionSupport::aggregateNeedForRule)
                .orElse(null);
    }

    private InformationNeed matchRules(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        for (RetrievalCatalogConfig.NeedInferenceRule rule : catalogRegistry.config().getNeedInferenceRules()) {
            if (rule == null || rule.getNeed() == null) {
                continue;
            }
            if (!rule.getExcludeKeywords().isEmpty() && keywordsMatch(lower, rule.getExcludeKeywords(), true)) {
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
        // 「他有哪些资质证照」= 实例列表，不是类型目录
        if (containsAny(lower, "他有哪些", "她有哪些", "它有哪些", "其有哪些")
                && containsAny(lower, "证照", "资质", "许可证", "执照")
                && !containsAny(lower, "类型", "种类", "什么类型", "哪些类型")) {
            return false;
        }
        boolean typeIntent = lower.contains("类型")
                || lower.contains("种类")
                || lower.contains("什么证")
                || (lower.contains("哪些证") && !lower.contains("哪些资质") && !lower.contains("哪些许可"))
                || lower.contains("什么类型")
                || lower.contains("有哪些类型");
        boolean certContext = lower.contains("证照")
                || lower.contains("资质")
                || lower.contains("许可证")
                || lower.contains("执照")
                || lower.contains("备案");
        return typeIntent && certContext;
    }

    private boolean shouldPreferIntentSlotsNeed(String question, IntentDecision intent, InformationNeed candidate) {
        if (candidate == null || !candidate.isTypeCatalog() || intent == null) {
            return false;
        }
        if (scopeSupport.isCatalogQuestion(question)) {
            return false;
        }
        InformationNeed mapped = inferFromIntentSlots(question, intent);
        if (mapped == null || mapped.granularity() == null
                || InformationNeed.GRANULARITY_TYPE_CATALOG.equalsIgnoreCase(mapped.granularity())) {
            return false;
        }
        String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (intent.hasCompanyHints() || intent.hasPersonFocus()) {
            return true;
        }
        boolean hasContextReference = containsAny(lower,
                "这些", "那些", "这批", "上一轮", "上轮", "上文", "刚才", "其中", "里面", "里", "主体", "它们", "他们");
        boolean asksConditionFilter = containsAny(lower,
                "吊销", "注销", "存续", "在业", "有效", "失效", "作废", "生效", "到期", "未作废");
        return hasContextReference && asksConditionFilter;
    }

    private static boolean containsAny(String text, String... markers) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && !marker.isBlank() && text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}

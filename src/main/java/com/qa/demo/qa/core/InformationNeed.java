package com.qa.demo.qa.core;

import com.qa.demo.qa.retrieval.certificate.CertificateListQuestionSupport;
import com.qa.demo.qa.retrieval.region.RegionListQuestionSupport;

/**
 * 由配置化规则或 LLM 推断，驱动检索目录匹配与闸门。
 */
public record InformationNeed(
        String facet,
        String granularity,
        boolean listExpected,
        double confidence,
        String reason
) {
    public static final String FACET_SEMANTIC = "semantic";
    public static final String GRANULARITY_INSTANCE = "instance";
    public static final String GRANULARITY_TYPE_CATALOG = "type_catalog";
    public static final String GRANULARITY_AGGREGATE = "aggregate";
    public static final String GRANULARITY_NARRATIVE = "narrative";

    public static InformationNeed defaultSemantic() {
        return new InformationNeed(FACET_SEMANTIC, GRANULARITY_NARRATIVE, false, 0.5, "default");
    }

    public boolean isTypeCatalog() {
        return GRANULARITY_TYPE_CATALOG.equalsIgnoreCase(granularity);
    }

    public boolean isAggregate() {
        return GRANULARITY_AGGREGATE.equalsIgnoreCase(granularity);
    }

    public boolean hasFacet() {
        return facet != null && !facet.isBlank();
    }

    /** 由 LLM {@link RetrievalStrategy} 映射信息需求，不走配置关键词推断。 */
    public static InformationNeed fromRetrievalStrategy(RetrievalStrategy strategy, double confidence, String reason) {
        if (strategy == null || strategy == RetrievalStrategy.UNKNOWN) {
            return defaultSemantic();
        }
        return switch (strategy) {
            case AGGREGATE_COUNT -> new InformationNeed(
                    "aggregate", GRANULARITY_AGGREGATE, false, confidence, reason);
            case TYPE_CATALOG -> new InformationNeed(
                    "catalog", GRANULARITY_TYPE_CATALOG, true, confidence, reason);
            case STRUCTURED_LIST -> new InformationNeed(
                    "list", "list", true, confidence, reason);
            case INSTANCE_FACT -> new InformationNeed(
                    "profile", GRANULARITY_INSTANCE, false, confidence, reason);
            case GRAPH_RELATIONAL -> new InformationNeed(
                    "role", "list", true, confidence, reason);
            case SEMANTIC_RAG -> defaultSemantic();
            case CLARIFY -> new InformationNeed(
                    FACET_SEMANTIC, GRANULARITY_NARRATIVE, false, confidence, reason);
            case UNKNOWN -> defaultSemantic();
        };
    }

    /**
     * LLM {@link RetrievalStrategy} 与规则推断 need 合并：type_catalog 保留规则 facet（profile/certificate），
     * 避免 LLM 产出 facet=catalog 导致检索目录无匹配维度。
     */
    public static InformationNeed mergeWithLlmStrategy(
            RetrievalStrategy strategy,
            double confidence,
            InformationNeed inferred
    ) {
        InformationNeed base = inferred != null ? inferred : defaultSemantic();
        if (strategy == null || strategy == RetrievalStrategy.UNKNOWN) {
            return base;
        }
        String reason = "llm_retrieval_strategy:" + strategy.token();
        if (strategy == RetrievalStrategy.TYPE_CATALOG) {
            if (base.isTypeCatalog() && base.hasFacet() && !isGenericCatalogFacet(base.facet())) {
                return new InformationNeed(
                        base.facet(),
                        GRANULARITY_TYPE_CATALOG,
                        true,
                        Math.max(confidence, base.confidence()),
                        reason + ";facet_from:" + nullToEmpty(base.reason())
                );
            }
            if (base.hasFacet()
                    && !isGenericCatalogFacet(base.facet())
                    && GRANULARITY_INSTANCE.equalsIgnoreCase(base.granularity())) {
                return base;
            }
        }
        // 仅当 LLM 判 semantic/unknown 时，规则 type_catalog 才覆盖（避免「他有哪些证照」被 catalog 抢走）
        if (base.isTypeCatalog()
                && (strategy == RetrievalStrategy.SEMANTIC_RAG || strategy == RetrievalStrategy.UNKNOWN)) {
            return base;
        }
        // queryType 映射的 instance need（如 person_certificate_list）不被 INSTANCE_FACT→profile 覆盖
        if (base.hasFacet()
                && !isGenericCatalogFacet(base.facet())
                && nullToEmpty(base.reason()).contains("query_type_mapping:")
                && (strategy == RetrievalStrategy.INSTANCE_FACT
                || strategy == RetrievalStrategy.STRUCTURED_LIST
                || strategy == RetrievalStrategy.SEMANTIC_RAG)) {
            return base;
        }
        // 阈值/筛选问句的规则 aggregate need 不被 LLM 覆盖
        if (base.isAggregate()
                && nullToEmpty(base.reason()).startsWith("inference:filter_threshold:")
                && (strategy == RetrievalStrategy.SEMANTIC_RAG
                || strategy == RetrievalStrategy.STRUCTURED_LIST
                || strategy == RetrievalStrategy.INSTANCE_FACT
                || strategy == RetrievalStrategy.AGGREGATE_COUNT)) {
            return base;
        }
        if (CertificateListQuestionSupport.isGlobalCertificateListNeed(base)
                && (strategy == RetrievalStrategy.SEMANTIC_RAG
                || strategy == RetrievalStrategy.STRUCTURED_LIST
                || strategy == RetrievalStrategy.INSTANCE_FACT
                || strategy == RetrievalStrategy.TYPE_CATALOG)) {
            return base;
        }
        if (RegionListQuestionSupport.isRegionCompanyListNeed(base)
                && (strategy == RetrievalStrategy.SEMANTIC_RAG
                || strategy == RetrievalStrategy.STRUCTURED_LIST
                || strategy == RetrievalStrategy.INSTANCE_FACT
                || strategy == RetrievalStrategy.TYPE_CATALOG)) {
            return base;
        }
        return fromRetrievalStrategy(strategy, confidence, reason);
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

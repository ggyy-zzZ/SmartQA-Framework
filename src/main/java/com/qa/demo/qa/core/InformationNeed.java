package com.qa.demo.qa.core;

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
                    "profile", GRANULARITY_TYPE_CATALOG, true, confidence, reason);
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
}

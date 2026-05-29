package com.qa.demo.qa.core;

/**
 * 信息需求：描述用户要什么形态的信息（与具体业务场景解耦）。
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
    public static final String GRANULARITY_NARRATIVE = "narrative";

    public static InformationNeed defaultSemantic() {
        return new InformationNeed(FACET_SEMANTIC, GRANULARITY_NARRATIVE, false, 0.5, "default");
    }

    public boolean isTypeCatalog() {
        return GRANULARITY_TYPE_CATALOG.equalsIgnoreCase(granularity);
    }

    public boolean hasFacet() {
        return facet != null && !facet.isBlank();
    }
}

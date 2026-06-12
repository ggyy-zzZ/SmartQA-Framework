package com.qa.demo.qa.core;

/**
 * 信息需求：描述用户要什么形态的信息（与具体业务场景解耦）。
 * 由配置化规则或 LLM 推断，驱动检索目录匹配与闸门。
 * <p>
 * P0-S2 起：facet 字段保留 String，driving facet 在 DRL/规则中按字符串相等比较即可；
 * 不引入 facetEnum，避免无谓的扩展（facet 是窄枚举空间，1-2 个 step 内即可统一）。
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

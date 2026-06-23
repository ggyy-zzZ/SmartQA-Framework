package com.qa.demo.qa.docvec.routing;

/**
 * DocVec 结构化查询类型（由 LLM 路由输出，执行层映射到参数化 SQL 或 RAG）。
 */
public enum DocVecQueryType {
    PERSON_ROLE_LIST,
    PERSON_ROLE_REGION_FILTER,
    REGION_COMPANY_LIST,
    COMPANY_COUNT,
    CERTIFICATE_HOLDER_LIST,
    PRIOR_LIST_REGION_FILTER,
    COMPANY_DETAIL,
    SEMANTIC;

    public static DocVecQueryType fromToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return SEMANTIC;
        }
        String token = raw.trim().toLowerCase().replace('-', '_');
        return switch (token) {
            case "person_role_list", "person_role" -> PERSON_ROLE_LIST;
            case "person_role_region_filter", "person_role_region" -> PERSON_ROLE_REGION_FILTER;
            case "region_company_list", "region_list" -> REGION_COMPANY_LIST;
            case "company_count", "aggregate_count" -> COMPANY_COUNT;
            case "certificate_holder_list", "certificate_list" -> CERTIFICATE_HOLDER_LIST;
            case "prior_list_region_filter", "prior_region_filter" -> PRIOR_LIST_REGION_FILTER;
            case "company_detail" -> COMPANY_DETAIL;
            default -> SEMANTIC;
        };
    }

    public boolean sqlCapable() {
        return this != COMPANY_DETAIL && this != SEMANTIC;
    }
}

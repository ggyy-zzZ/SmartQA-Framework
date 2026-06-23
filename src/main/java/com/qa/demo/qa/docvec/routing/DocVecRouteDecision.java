package com.qa.demo.qa.docvec.routing;

/**
 * 问句路由决策（LLM 输出槽位 + 执行层映射）。
 */
public record DocVecRouteDecision(
        DocVecRetrievalMode mode,
        String reason,
        DocVecQueryType queryType,
        String personName,
        String roleLabel,
        String regionKeyword,
        boolean countQuery,
        String operatingStatusFilter,
        String certificateTypeName,
        int certificateTypeId,
        String companyNameHint,
        boolean followUpApplied,
        double routeConfidence
) {
    public static DocVecRouteDecision rag(DocVecQueryType queryType, String reason) {
        return new DocVecRouteDecision(
                DocVecRetrievalMode.RAG,
                reason,
                queryType == null ? DocVecQueryType.SEMANTIC : queryType,
                "", "", "", false, "", "", -1, "", false, 0.0
        );
    }

    public static DocVecRouteDecision rag(String reason) {
        return rag(DocVecQueryType.SEMANTIC, reason);
    }

    public static DocVecRouteDecision sql(
            DocVecQueryType queryType,
            String reason,
            String personName,
            String roleLabel,
            String regionKeyword,
            boolean countQuery,
            String operatingStatusFilter,
            String certificateTypeName,
            int certificateTypeId,
            String companyNameHint,
            boolean followUpApplied,
            double confidence
    ) {
        return new DocVecRouteDecision(
                DocVecRetrievalMode.SQL,
                reason,
                queryType,
                safe(personName),
                safe(roleLabel),
                safe(regionKeyword),
                countQuery,
                safe(operatingStatusFilter),
                safe(certificateTypeName),
                certificateTypeId,
                safe(companyNameHint),
                followUpApplied,
                confidence
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.qa.demo.qa.core;

/**
 * 可执行的检索计划：由意图决策 + 配置推导出的 topK 与通路策略，供 Pipeline / Graph / SQL 统一消费。
 */
public record RetrievalPlan(
        IntentDecision intent,
        int graphRecallTopK,
        int finalEvidenceTopK,
        boolean personRoleList,
        boolean personCertificateList,
        boolean preferGraphOnly,
        boolean skipEmployeeBaseAppend
) {
    public static RetrievalPlan of(IntentDecision intent, int graphRecallTopK, int finalEvidenceTopK) {
        boolean personRoleList = intent != null && intent.isPersonRoleListQuery();
        boolean personCertificateList = intent != null && intent.isPersonCertificateListQuery();
        return new RetrievalPlan(
                intent,
                graphRecallTopK,
                finalEvidenceTopK,
                personRoleList,
                personCertificateList,
                personRoleList,
                personRoleList || personCertificateList
        );
    }
}

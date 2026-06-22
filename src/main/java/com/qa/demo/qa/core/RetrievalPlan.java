package com.qa.demo.qa.core;

/**
 * 可执行的检索计划：由意图决策 + catalog execution 配置推导出的 topK 与通路策略。
 */
public record RetrievalPlan(
        IntentDecision intent,
        InformationNeed need,
        int graphRecallTopK,
        int finalEvidenceTopK,
        RetrievalExecutionProfile execution
) {
    public static RetrievalPlan of(IntentDecision intent, int graphRecallTopK, int finalEvidenceTopK) {
        return of(intent, null, graphRecallTopK, finalEvidenceTopK, RetrievalExecutionProfile.DEFAULT);
    }

    public static RetrievalPlan of(
            IntentDecision intent,
            int graphRecallTopK,
            int finalEvidenceTopK,
            RetrievalExecutionProfile execution
    ) {
        return of(intent, null, graphRecallTopK, finalEvidenceTopK, execution);
    }

    public static RetrievalPlan of(
            IntentDecision intent,
            InformationNeed need,
            int graphRecallTopK,
            int finalEvidenceTopK,
            RetrievalExecutionProfile execution
    ) {
        return new RetrievalPlan(
                intent,
                need,
                graphRecallTopK,
                finalEvidenceTopK,
                execution == null ? RetrievalExecutionProfile.DEFAULT : execution
        );
    }

    /** @deprecated 使用 {@link #execution()} 的 dedicatedListPath */
    public boolean personRoleList() {
        return execution != null && execution.dedicatedListPath();
    }

    /** @deprecated 使用 {@link #execution()} 的 dedicatedCertificatePath */
    public boolean personCertificateList() {
        return execution != null && execution.dedicatedCertificatePath();
    }

    public boolean skipEmployeeBaseAppend() {
        return execution != null && execution.skipEmployeeBaseAppend();
    }
}

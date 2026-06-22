package com.qa.demo.qa.core;

import java.util.List;

/**
 * 意图路由结果：检索通道 intent、槽位与 LLM {@link RetrievalStrategy}。
 * <p>
 * 人物相关：{@link #personName()} 为展示用业务属性；{@link #personEmployeeId()} 为检索锚点。
 */
public record IntentDecision(
        String intent,
        double confidence,
        String reason,
        String personName,
        List<String> companyHints,
        String roleFocus,
        Integer personEmployeeId,
        String retrievalStrategy
) {
    public IntentDecision(String intent, double confidence, String reason) {
        this(intent, confidence, reason, "", List.of(), "any", null, "");
    }

    public IntentDecision(
            String intent,
            double confidence,
            String reason,
            String personName,
            List<String> companyHints,
            String roleFocus
    ) {
        this(intent, confidence, reason, personName, companyHints, roleFocus, null, "");
    }

    public IntentDecision(
            String intent,
            double confidence,
            String reason,
            String personName,
            List<String> companyHints,
            String roleFocus,
            Integer personEmployeeId
    ) {
        this(intent, confidence, reason, personName, companyHints, roleFocus, personEmployeeId, "");
    }

    public boolean hasPersonFocus() {
        return personName != null && !personName.isBlank();
    }

    public boolean hasPersonEmployeeId() {
        return personEmployeeId != null && personEmployeeId > 0;
    }

    public boolean hasCompanyHints() {
        return companyHints != null && !companyHints.isEmpty();
    }

    public boolean hasRetrievalStrategy() {
        return retrievalStrategy != null && !retrievalStrategy.isBlank();
    }

    public RetrievalStrategy resolvedRetrievalStrategy() {
        if (hasRetrievalStrategy()) {
            RetrievalStrategy strategy = RetrievalStrategy.fromToken(retrievalStrategy);
            if (strategy != RetrievalStrategy.UNKNOWN) {
                return strategy;
            }
        }
        return RetrievalStrategy.UNKNOWN;
    }

    public boolean isStructuredListStrategy() {
        RetrievalStrategy strategy = resolvedRetrievalStrategy();
        return strategy == RetrievalStrategy.STRUCTURED_LIST
                || strategy == RetrievalStrategy.GRAPH_RELATIONAL;
    }
}

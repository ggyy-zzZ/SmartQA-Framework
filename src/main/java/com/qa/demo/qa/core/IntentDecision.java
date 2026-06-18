package com.qa.demo.qa.core;

import java.util.List;

/**
 * 意图路由结果：除检索通道 intent 外，可携带 LLM 抽取的实体与查询形态，供图谱等检索器使用。
 * <p>
 * 人物相关：{@link #personName()} 为展示用业务属性（规范姓名）；{@link #personEmployeeId()} 为检索锚点用的唯一标识。
 * 检索层应优先使用 {@link #personEmployeeId()}，勿用姓名或编码字段承担业务解释。
 */
public record IntentDecision(
        String intent,
        double confidence,
        String reason,
        /** person_role_list | person_certificate_list | company_profile | company_certificate | company_seal | ... */
        String queryType,
        /** 规范姓名（展示/生成），非唯一键 */
        String personName,
        List<String> companyHints,
        /** legal_rep | director | shareholder | any */
        String roleFocus,
        /** 员工表主键；人物类检索的优先锚点 */
        Integer personEmployeeId,
        /** LLM 决定的检索执行策略，见 {@link RetrievalStrategy} */
        String retrievalStrategy
) {
    public IntentDecision(String intent, double confidence, String reason) {
        this(intent, confidence, reason, "", "", List.of(), "any", null, "");
    }

    public IntentDecision(
            String intent,
            double confidence,
            String reason,
            String queryType,
            String personName,
            List<String> companyHints,
            String roleFocus
    ) {
        this(intent, confidence, reason, queryType, personName, companyHints, roleFocus, null, "");
    }

    public IntentDecision(
            String intent,
            double confidence,
            String reason,
            String queryType,
            String personName,
            List<String> companyHints,
            String roleFocus,
            Integer personEmployeeId
    ) {
        this(intent, confidence, reason, queryType, personName, companyHints, roleFocus, personEmployeeId, "");
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
        if ("aggregate".equalsIgnoreCase(queryType)) {
            return RetrievalStrategy.AGGREGATE_COUNT;
        }
        return RetrievalStrategy.UNKNOWN;
    }

    public boolean isPersonRoleListQuery() {
        return "person_role_list".equalsIgnoreCase(queryType);
    }

    public boolean isPersonCertificateListQuery() {
        return "person_certificate_list".equalsIgnoreCase(queryType);
    }

    public boolean isCompanyComplianceQuery() {
        return "company_certificate".equalsIgnoreCase(queryType)
                || "company_seal".equalsIgnoreCase(queryType)
                || "company_compliance".equalsIgnoreCase(queryType);
    }
}

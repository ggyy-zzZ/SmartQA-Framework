package com.qa.demo.qa.core;

import java.util.List;

/**
 * 意图路由结果：除检索通道 intent 外，可携带 LLM 抽取的实体与查询形态，供图谱等检索器使用。
 */
public record IntentDecision(
        String intent,
        double confidence,
        String reason,
        /** person_role_list | company_profile | company_certificate | company_seal | shareholder | relation | aggregate | policy | semantic | mixed | unknown */
        String queryType,
        String personName,
        List<String> companyHints,
        /** legal_rep | director | shareholder | any */
        String roleFocus
) {
    public IntentDecision(String intent, double confidence, String reason) {
        this(intent, confidence, reason, "", "", List.of(), "any");
    }

    public boolean hasPersonFocus() {
        return personName != null && !personName.isBlank();
    }

    public boolean hasCompanyHints() {
        return companyHints != null && !companyHints.isEmpty();
    }

    public boolean isPersonRoleListQuery() {
        return "person_role_list".equalsIgnoreCase(queryType);
    }

    public boolean isCompanyComplianceQuery() {
        return "company_certificate".equalsIgnoreCase(queryType)
                || "company_seal".equalsIgnoreCase(queryType);
    }
}

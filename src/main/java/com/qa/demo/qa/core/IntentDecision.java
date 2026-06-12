package com.qa.demo.qa.core;

import com.qa.demo.qa.rules.QaQueryType;

import java.util.List;

/**
 * 意图路由结果：除检索通道 intent 外，可携带 LLM 抽取的实体与查询形态，供图谱等检索器使用。
 * <p>
 * 人物相关：{@link #personName()} 为展示用业务属性（规范姓名）；{@link #personEmployeeId()} 为检索锚点用的唯一标识。
 * 检索层应优先使用 {@link #personEmployeeId()}，勿用姓名或编码字段承担业务解释。
 * <p>
 * P0-S2 起进入双写期：{@link #queryType} (String) 保留 6 个月，新增 {@link #queryTypeEnum} (QaQueryType)
 * 作为内部比较与规则输入；两者通过 {@link QaQueryType#from(String)} 保持一致。
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
        /**
         * P0-S2 起的 queryType 枚举形态。6 个月双写期内与 {@link #queryType} 同步。
         * 推荐内部比较统一走枚举；外部读 {@code queryType} 字符串保持兼容。
         */
        QaQueryType queryTypeEnum
) {
    public IntentDecision(String intent, double confidence, String reason) {
        this(intent, confidence, reason, "", "", List.of(), "any", null, QaQueryType.UNKNOWN);
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
        this(intent, confidence, reason, queryType, personName, companyHints, roleFocus, null,
                QaQueryType.from(queryType));
    }

    /**
     * 8 参数重载：保留兼容旧调用方（如 {@code IntentSlots.normalize}）。
     * 第 4 个参数是 queryType 字符串，第 8 个是 personEmployeeId。
     */
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
        this(intent, confidence, reason, queryType, personName, companyHints, roleFocus, personEmployeeId,
                QaQueryType.from(queryType));
    }

    /**
     * 8 参数重载：枚举优先的写入入口。
     */
    public IntentDecision(
            String intent,
            double confidence,
            String reason,
            QaQueryType queryTypeEnum,
            String personName,
            List<String> companyHints,
            String roleFocus,
            Integer personEmployeeId
    ) {
        this(intent,
                confidence,
                reason,
                queryTypeEnum == null ? "" : queryTypeEnum.name(),
                personName,
                companyHints,
                roleFocus,
                personEmployeeId,
                queryTypeEnum == null ? QaQueryType.UNKNOWN : queryTypeEnum);
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

    public boolean isPersonRoleListQuery() {
        return queryTypeEnum == QaQueryType.PERSON_ROLE_LIST
                || "person_role_list".equalsIgnoreCase(queryType);
    }

    public boolean isPersonCertificateListQuery() {
        return queryTypeEnum == QaQueryType.PERSON_CERTIFICATE_LIST
                || "person_certificate_list".equalsIgnoreCase(queryType);
    }

    public boolean isCompanyComplianceQuery() {
        return queryTypeEnum == QaQueryType.COMPANY_CERTIFICATE
                || queryTypeEnum == QaQueryType.COMPANY_SEAL
                || queryTypeEnum == QaQueryType.COMPANY_COMPLIANCE
                || "company_certificate".equalsIgnoreCase(queryType)
                || "company_seal".equalsIgnoreCase(queryType)
                || "company_compliance".equalsIgnoreCase(queryType);
    }
}

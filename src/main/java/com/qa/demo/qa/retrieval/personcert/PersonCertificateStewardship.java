package com.qa.demo.qa.retrieval.personcert;

/**
 * 人物在证照上的职责角色（业务语义，与 employee_id / certificate_type code 解耦）。
 */
public record PersonCertificateStewardship(
        String certTypeLabel,
        String companyName,
        String stewardRoleLabel,
        String statusLabel,
        String certificateRecordId,
        String companyId,
        String employeeId,
        String certificateTypeCode
) {
    public String toEvidenceLine() {
        return "证照类型=" + certTypeLabel
                + "; 公司=" + companyName
                + "; 角色=" + stewardRoleLabel
                + "; 状态=" + statusLabel;
    }
}

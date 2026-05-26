package com.qa.demo.qa.retrieval.personcert;

/**
 * 人物在证照上的职责（证据层业务属性）。
 * <p>
 * 关联键（{@code certificateRecordId} / {@code companyId} / {@code employeeId}）仅供去重与 ContextChunk 索引，
 * 不写入 {@link #toEvidenceLine()}，避免让编码承担业务解释。
 */
public record PersonCertificateStewardship(
        String certTypeLabel,
        String companyName,
        String stewardRoleLabel,
        String statusLabel,
        String certificateRecordId,
        String companyId,
        String employeeId
) {
    public String toEvidenceLine() {
        return "证照类型=" + certTypeLabel
                + "; 公司=" + companyName
                + "; 角色=" + stewardRoleLabel
                + "; 状态=" + statusLabel;
    }
}

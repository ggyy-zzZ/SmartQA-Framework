package com.qa.demo.qa.retrieval.personcert;

import com.qa.demo.knowledge.EvidenceSchemaRegistry;

/**
 * 人物证照职责记录。
 */
public record PersonCertificateStewardship(
        String certType,
        String companyName,
        String stewardRole,
        String status,
        String anchorId,
        String companyId,
        String employeeId
) {
    public static final String SCHEMA_ID = "person_certificate_v1";

    public String toEvidenceLine(EvidenceSchemaRegistry registry) {
        return String.format("%s | %s | %s | 状态: %s",
                stewardRole, certType, companyName, status);
    }

    public String displayLabel() {
        return companyName;
    }
}
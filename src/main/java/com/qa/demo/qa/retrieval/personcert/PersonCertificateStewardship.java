package com.qa.demo.qa.retrieval.personcert;

import com.qa.demo.knowledge.EvidenceSchemaRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人物在证照上的职责行；关联键仅用于去重与索引，snippet 由 {@link EvidenceSchemaRegistry} 按 schema 生成。
 */
public record PersonCertificateStewardship(
        String certTypeLabel,
        String displayLabel,
        String stewardRoleLabel,
        String statusLabel,
        String certificateRecordId,
        String anchorId,
        String employeeId
) {
    public static final String SCHEMA_ID = "person_certificate_v1";

    public String toEvidenceLine(EvidenceSchemaRegistry schemas) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("certType", certTypeLabel);
        values.put("organization", displayLabel);
        values.put("stewardRole", stewardRoleLabel);
        values.put("status", statusLabel);
        return schemas.formatSnippet(SCHEMA_ID, values);
    }
}

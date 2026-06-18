package com.qa.demo.qa.core;

/**
 * 统一证据片段：锚点 ID 与展示标签分离，业务字段名由 {@code evidenceSchema} 约定。
 */
public record ContextChunk(
        /** 检索锚点（公司 ID、员工 ID、系统状态码等），非展示用 */
        String anchorId,
        /** 面向用户的展示名（公司名、员工姓名等） */
        String displayLabel,
        /** company | employee | system */
        String entityKind,
        String field,
        String snippet,
        double score,
        String source,
        /** 证据形态标识，对应 qa/evidence-schemas.json */
        String evidenceSchema
) {
    public static final String KIND_COMPANY = "company";
    public static final String KIND_EMPLOYEE = "employee";
    public static final String KIND_SYSTEM = "system";
    public static final String KIND_DOCUMENT = "document";

    public static ContextChunk ofCompany(
            String anchorId,
            String displayLabel,
            String field,
            String snippet,
            double score,
            String source
    ) {
        return new ContextChunk(
                nullToEmpty(anchorId),
                nullToEmpty(displayLabel),
                KIND_COMPANY,
                nullToEmpty(field),
                nullToEmpty(snippet),
                score,
                nullToEmpty(source),
                ""
        );
    }

    public static ContextChunk ofCompany(
            String anchorId,
            String displayLabel,
            String field,
            String snippet,
            double score,
            String source,
            String evidenceSchema
    ) {
        return new ContextChunk(
                nullToEmpty(anchorId),
                nullToEmpty(displayLabel),
                KIND_COMPANY,
                nullToEmpty(field),
                nullToEmpty(snippet),
                score,
                nullToEmpty(source),
                nullToEmpty(evidenceSchema)
        );
    }

    public static ContextChunk ofEmployee(
            String anchorId,
            String displayLabel,
            String field,
            String snippet,
            double score,
            String source,
            String evidenceSchema
    ) {
        return new ContextChunk(
                nullToEmpty(anchorId),
                nullToEmpty(displayLabel),
                KIND_EMPLOYEE,
                nullToEmpty(field),
                nullToEmpty(snippet),
                score,
                nullToEmpty(source),
                nullToEmpty(evidenceSchema)
        );
    }

    public static ContextChunk ofSystem(
            String statusCode,
            String field,
            String snippet,
            double score,
            String source
    ) {
        return new ContextChunk(
                nullToEmpty(statusCode),
                "",
                KIND_SYSTEM,
                nullToEmpty(field),
                nullToEmpty(snippet),
                score,
                nullToEmpty(source),
                ""
        );
    }

    public static ContextChunk ofDocument(
            String chunkKey,
            String title,
            String field,
            String snippet,
            double score,
            String source
    ) {
        return new ContextChunk(
                nullToEmpty(chunkKey),
                nullToEmpty(title),
                KIND_DOCUMENT,
                nullToEmpty(field),
                nullToEmpty(snippet),
                score,
                nullToEmpty(source),
                "user_document_v1"
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

package com.qa.demo.qa.docvec.session;

import com.qa.demo.qa.docvec.routing.DocVecQueryType;

import java.util.List;

/**
 * 传给 LLM 路由与 SQL 过滤的会话快照（实验路径专用，内存会话）。
 */
public record DocVecSessionSnapshot(
        boolean followUp,
        String priorQuestion,
        String priorAnswerSummary,
        DocVecQueryType priorQueryType,
        String priorPersonName,
        String priorRoleLabel,
        String priorCertificateTypeName,
        String priorRegionKeyword,
        List<String> priorCompanyNames,
        List<String> priorCompanyIds
) {
    public static DocVecSessionSnapshot empty() {
        return new DocVecSessionSnapshot(
                false, "", "", DocVecQueryType.SEMANTIC,
                "", "", "", "", List.of(), List.of()
        );
    }
}

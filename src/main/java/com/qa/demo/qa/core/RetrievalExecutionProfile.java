package com.qa.demo.qa.core;

import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogConfig;

/**
 * 检索执行策略：由 retrieval-catalog.json 的 execution 块驱动，替代 Java 内 queryType 硬编码分支。
 */
public record RetrievalExecutionProfile(
        boolean dedicatedListPath,
        boolean dedicatedCertificatePath,
        String routeLabel,
        boolean skipTruncation,
        boolean includeCompiledDocs,
        String correctionEntityKind,
        boolean expandRecallTopK,
        boolean skipEmployeeBaseAppend
) {
    public static final RetrievalExecutionProfile DEFAULT = new RetrievalExecutionProfile(
            false,
            false,
            "",
            false,
            false,
            "company",
            false,
            false
    );

    public static RetrievalExecutionProfile fromTemplate(RetrievalCatalogConfig.ExecutionTemplate template) {
        if (template == null) {
            return DEFAULT;
        }
        boolean dedicatedList = "dedicated_list".equalsIgnoreCase(template.getPath());
        boolean dedicatedCert = "dedicated_certificate".equalsIgnoreCase(template.getPath());
        return new RetrievalExecutionProfile(
                dedicatedList,
                dedicatedCert,
                template.getRouteLabel() == null ? "" : template.getRouteLabel().trim(),
                template.isSkipTruncation(),
                template.isIncludeCompiledDocs(),
                template.getCorrectionEntityKind() == null ? "company" : template.getCorrectionEntityKind(),
                template.isExpandRecallTopK(),
                template.isSkipEmployeeBaseAppend()
        );
    }

    public boolean hasRouteLabel() {
        return routeLabel != null && !routeLabel.isBlank();
    }

    public boolean shouldApplyCorrectionNarrow() {
        return correctionEntityKind != null && !correctionEntityKind.isBlank();
    }
}

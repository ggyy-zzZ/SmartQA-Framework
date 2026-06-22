package com.qa.demo.qa.retrieval;

/**
 * 单次问答的证据呈现上下文（由策略层解析问句与请求参数得出）。
 */
public record EvidencePresentationContext(
        EvidencePresentationMode mode,
        boolean userEmphasizedComplete,
        int evidenceTopK,
        int sqlMaxRows
) {
    public boolean isFullPresentation() {
        return mode == EvidencePresentationMode.FULL;
    }
}

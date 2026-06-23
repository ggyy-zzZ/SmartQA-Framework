package com.qa.demo.qa.docvec.web;

import java.util.List;
import java.util.Map;

public record DocVecAskResponse(
        String question,
        String answer,
        double confidence,
        boolean canAnswer,
        String route,
        String collection,
        int recalledCount,
        int evidenceCount,
        List<DocVecEvidenceItem> evidence,
        Map<String, Object> debug,
        String conversationId,
        String timestamp
) {
}

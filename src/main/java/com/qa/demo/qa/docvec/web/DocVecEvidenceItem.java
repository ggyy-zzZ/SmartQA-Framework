package com.qa.demo.qa.docvec.web;

import java.util.List;
import java.util.Map;

public record DocVecEvidenceItem(
        String docId,
        String companyName,
        double score,
        String preview,
        String source
) {
}

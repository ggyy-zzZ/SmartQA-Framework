package com.qa.demo.qa.core;

public record ContextChunk(
        String companyId,
        String companyName,
        String field,
        String snippet,
        double score,
        String source
) {
}

package com.qa.demo.qa.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SemanticSchemaRegistryTest {

    @Autowired
    private SemanticSchemaRegistry semanticSchemaRegistry;

    @Test
    void loadsCompanyRoleReferences() {
        assertTrue(semanticSchemaRegistry.companyRoleColumnLabels().containsKey("legal_rep_id"));
    }

    @Test
    void buildsLlmSummaryWithJoinHints() {
        String summary = semanticSchemaRegistry.buildLlmSchemaSummary();
        assertFalse(summary.isBlank());
        assertTrue(summary.contains("legal_rep_id"));
        assertTrue(summary.contains("employee"));
        assertTrue(summary.contains("certificate_management"));
    }
}

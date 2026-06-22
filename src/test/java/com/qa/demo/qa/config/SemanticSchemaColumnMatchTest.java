package com.qa.demo.qa.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SemanticSchemaColumnMatchTest {

    @Autowired
    private SemanticSchemaRegistry semanticSchemaRegistry;

    @Test
    void matchesOperatingStatusFromLabel() {
        Optional<SemanticSchemaColumnRef> hit = semanticSchemaRegistry.matchDistinctColumn(
                "公司经营状态包含哪些种类");
        assertTrue(hit.isPresent());
        assertEquals("company", hit.get().entityId());
        assertEquals("operating_status", hit.get().column());
        assertEquals("经营状态", hit.get().label());
    }

    @Test
    void matchesCertificateTypeFromPartialLabel() {
        Optional<SemanticSchemaColumnRef> hit = semanticSchemaRegistry.matchDistinctColumn(
                "资质证照有哪些类型");
        assertTrue(hit.isPresent());
        assertEquals("certificate_management", hit.get().entityId());
        assertEquals("certificate_type", hit.get().column());
    }
}

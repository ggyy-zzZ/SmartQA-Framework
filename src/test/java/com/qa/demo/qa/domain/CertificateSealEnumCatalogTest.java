package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CertificateSealEnumCatalogTest {

    private static CertificateSealEnumCatalog catalog;

    @BeforeAll
    static void setUp() {
        catalog = CertificateSealEnumCatalog.loadDefault(new ObjectMapper());
    }

    @Test
    void resolvesCertificateCodeAndSnakeCase() {
        assertEquals("ICP备案", catalog.resolveCertificateLabel("9"));
        assertEquals("人力资源服务许可证", catalog.resolveCertificateLabel("human_resources_service_license"));
    }

    @Test
    void resolvesSealCode() {
        assertEquals("合同专用章2", catalog.resolveSealLabel("11"));
        assertEquals("法人手签章", catalog.resolveSealLabel("legal_signature"));
    }
}

package com.qa.demo.qa.retrieval.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.CertificateSealEnumCatalog;
import com.qa.demo.qa.domain.GraphCompanyFacetCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphCompanySnippetBuilderTest {

    private static GraphCompanyFacetCatalog catalog;
    private static CertificateSealEnumCatalog enumCatalog;

    @BeforeAll
    static void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        catalog = GraphCompanyFacetCatalog.loadDefault(mapper);
        enumCatalog = CertificateSealEnumCatalog.loadDefault(mapper);
    }

    @Test
    void certificateQueryTypeOmitsShareholdersAndSeals() {
        Map<String, String> scalars = Map.of("status", "存续");
        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("shareholders", "[A(10%)]");
        lists.put("certificates", "[营业执照:有效]");
        lists.put("seals", "[公章/财务(有效)]");

        String snippet = GraphCompanySnippetBuilder.buildSnippet(
                scalars,
                lists,
                catalog.facetsForQueryType("company_certificate"),
                catalog,
                enumCatalog
        );
        assertTrue(snippet.contains("证照="));
        assertFalse(snippet.contains("股东="));
        assertFalse(snippet.contains("印章="));
    }

    @Test
    void sealQueryTypeIncludesSealsOnlyAmongLists() {
        Map<String, String> scalars = Map.of("status", "存续");
        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("certificates", "[营业执照:有效]");
        lists.put("seals", "[公章/财务(有效)]");

        String snippet = GraphCompanySnippetBuilder.buildSnippet(
                scalars,
                lists,
                catalog.facetsForQueryType("company_seal"),
                catalog,
                enumCatalog
        );
        assertTrue(snippet.contains("印章="));
        assertFalse(snippet.contains("证照="));
    }

    @Test
    void buildFromIntentUsesQueryType() {
        IntentDecision intent = new IntentDecision(
                "graph", 0.9, "test", "company_certificate", "", List.of("测试公司"), "any"
        );
        Map<String, String> scalars = Map.of("status", "存续");
        Map<String, String> lists = Map.of("certificates", "[ICP:有效]", "shareholders", "[X]");
        String snippet = GraphCompanySnippetBuilder.buildSnippet(
                scalars,
                lists,
                catalog.facetsForQueryType(intent.queryType()),
                catalog,
                enumCatalog
        );
        assertTrue(snippet.contains("证照="));
        assertFalse(snippet.contains("股东="));
    }

    @Test
    void numericCertificateAndSealCodesResolveToChineseLabels() {
        Map<String, String> scalars = Map.of("status", "存续");
        Map<String, String> lists = Map.of(
                "certificates", "[9:有效, 17:有效]",
                "seals", "[1(有效), 4(有效)]"
        );
        String snippet = GraphCompanySnippetBuilder.buildSnippet(
                scalars,
                lists,
                List.of("status", "certificates", "seals"),
                catalog,
                enumCatalog
        );
        assertTrue(snippet.contains("ICP备案:有效"));
        assertTrue(snippet.contains("ISO9001:有效"));
        assertTrue(snippet.contains("法定名称章"));
        assertTrue(snippet.contains("合同专用章"));
    }
}

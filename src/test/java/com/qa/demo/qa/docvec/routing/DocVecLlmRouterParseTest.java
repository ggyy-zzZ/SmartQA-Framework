package com.qa.demo.qa.docvec.routing;

import com.qa.demo.qa.docvec.session.DocVecSessionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocVecLlmRouterParseTest {

    private final DocVecLlmRouter router = new DocVecLlmRouter(null, new com.fasterxml.jackson.databind.ObjectMapper(),
            new DocVecSlotResolver(com.qa.demo.qa.domain.CertificateSealEnumCatalog.loadDefault(
                    new com.fasterxml.jackson.databind.ObjectMapper())));

    @Test
    void parsesCertificateListRoute() throws Exception {
        String json = """
                {
                  "mode": "sql",
                  "queryType": "certificate_holder_list",
                  "certificateTypeName": "人力资源服务许可证",
                  "reason": "全库证照枚举",
                  "confidence": 0.95
                }
                """;
        DocVecRouteDecision d = router.parseAndNormalize(
                "现在有 人力资源服务许可证的主体有哪些",
                DocVecSessionSnapshot.empty(),
                json
        );
        assertEquals(DocVecRetrievalMode.SQL, d.mode());
        assertEquals(DocVecQueryType.CERTIFICATE_HOLDER_LIST, d.queryType());
        assertEquals(4, d.certificateTypeId());
    }

    @Test
    void parsesPersonRoleRegionFollowUp() throws Exception {
        String json = """
                {
                  "mode": "sql",
                  "queryType": "person_role_region_filter",
                  "personName": "戴科彬",
                  "roleLabel": "法定代表人",
                  "regionKeyword": "北京",
                  "reason": "在上文法人列表上筛北京",
                  "confidence": 0.9
                }
                """;
        DocVecSessionSnapshot session = new DocVecSessionSnapshot(
                true,
                "戴科彬还担任哪些法人",
                "26家",
                DocVecQueryType.PERSON_ROLE_LIST,
                "戴科彬",
                "法定代表人",
                "",
                "",
                java.util.List.of("A公司", "B公司"),
                java.util.List.of("1", "2")
        );
        DocVecRouteDecision d = router.parseAndNormalize("其中有哪些在北京", session, json);
        assertEquals(DocVecQueryType.PERSON_ROLE_REGION_FILTER, d.queryType());
        assertEquals("戴科彬", d.personName());
        assertEquals("北京", d.regionKeyword());
        assertTrue(d.followUpApplied());
    }

    @Test
    void parsesCompanyDetailRag() throws Exception {
        String json = """
                {
                  "mode": "rag",
                  "queryType": "company_detail",
                  "companyNameHint": "同道精英（天津）信息技术有限公司",
                  "reason": "单公司法人",
                  "confidence": 0.88
                }
                """;
        DocVecRouteDecision d = router.parseAndNormalize(
                "同道精英天津信息技术有限公司的法人是谁",
                DocVecSessionSnapshot.empty(),
                json
        );
        assertEquals(DocVecRetrievalMode.RAG, d.mode());
        assertEquals(DocVecQueryType.COMPANY_DETAIL, d.queryType());
    }
}

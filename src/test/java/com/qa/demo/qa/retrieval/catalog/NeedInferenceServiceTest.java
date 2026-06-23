package com.qa.demo.qa.retrieval.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import com.qa.demo.qa.retrieval.structured.RegionResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NeedInferenceServiceTest {

    private NeedInferenceService needInferenceService;

    @BeforeEach
    void setUp() {
        RetrievalCatalogConfig catalogConfig = new RetrievalCatalogConfig();
        RetrievalCatalogConfig.NeedInferenceRule operatingStatus = new RetrievalCatalogConfig.NeedInferenceRule();
        operatingStatus.setId("operating_status_catalog");
        operatingStatus.setAllKeywords(List.of("经营状态"));
        operatingStatus.setAnyKeywords(List.of("种类", "类型", "哪些", "包含"));
        RetrievalCatalogConfig.NeedTemplate need = new RetrievalCatalogConfig.NeedTemplate();
        need.setFacet("profile");
        need.setGranularity(InformationNeed.GRANULARITY_TYPE_CATALOG);
        need.setListExpected(true);
        operatingStatus.setNeed(need);
        RetrievalCatalogConfig.NeedInferenceRule legalRep = new RetrievalCatalogConfig.NeedInferenceRule();
        legalRep.setId("legal_rep_subject_reverse");
        legalRep.setAllKeywords(List.of("法人"));
        legalRep.setAnyKeywords(List.of("哪些", "主体", "公司", "企业", "是"));
        RetrievalCatalogConfig.NeedTemplate roleList = new RetrievalCatalogConfig.NeedTemplate();
        roleList.setFacet("role");
        roleList.setGranularity("list");
        roleList.setListExpected(true);
        legalRep.setNeed(roleList);
        catalogConfig.setNeedInferenceRules(List.of(operatingStatus, legalRep));

        RetrievalCatalogRegistry registry = mock(RetrievalCatalogRegistry.class);
        when(registry.config()).thenReturn(catalogConfig);

        BusinessRulesConfig rulesConfig = new BusinessRulesConfig();
        rulesConfig.getIntentRouting().setFollowUpReferenceMarkers(List.of("这些", "那些"));
        RegionResolverService regionResolver = mock(RegionResolverService.class);
        when(regionResolver.extractRegionCodes("北京的公司有哪些"))
                .thenReturn(new RegionResolverService.RegionResolveResult(List.of("110000"), List.of("北京市")));
        needInferenceService = new NeedInferenceService(
                registry,
                new ConversationScopeSupport(rulesConfig),
                rulesConfig,
                regionResolver,
                mock(EnterpriseCanonicalFactsRegistry.class));
    }

    @Test
    void pronounCertificateListIsNotTypeCatalog() {
        InformationNeed need = needInferenceService.infer("他有哪些资质证照", null);
        assertFalse(need.isTypeCatalog());
    }

    @Test
    void operatingStatusCatalog_beatsSemanticStrategy() {
        IntentDecision semanticIntent = new IntentDecision(
                "vector",
                0.65,
                "llm",
                "",
                List.of("公司"),
                "any",
                null,
                "semantic_rag"
        );
        InformationNeed need = needInferenceService.infer("公司经营状态包含哪些种类", semanticIntent);
        assertTrue(need.isTypeCatalog());
        assertEquals("profile", need.facet());
        assertEquals("inference_heuristic:type_catalog", need.reason());
    }

    @Test
    void legalRepSubjectReverseInfersRoleList() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AssistantConfigJsonLoader loader = mock(AssistantConfigJsonLoader.class);
        when(loader.readTree("retrieval-catalog")).thenReturn(
                mapper.readTree(java.nio.file.Paths.get("src/main/resources/qa/retrieval-catalog.json").toFile()));
        RetrievalCatalogRegistry catalogRegistry = new RetrievalCatalogRegistry(mapper, loader);
        assertEquals(16, catalogRegistry.config().getNeedInferenceRules().size());
        BusinessRulesConfig rulesConfig = new BusinessRulesConfig();
        rulesConfig.getIntentRouting().setFollowUpReferenceMarkers(List.of("这些", "那些"));
        NeedInferenceService service = new NeedInferenceService(
                catalogRegistry,
                new ConversationScopeSupport(rulesConfig),
                rulesConfig,
                mock(RegionResolverService.class),
                mock(EnterpriseCanonicalFactsRegistry.class));

        IntentDecision semanticIntent = new IntentDecision(
                "vector",
                0.65,
                "llm",
                "戴科彬",
                List.of(),
                "any",
                null,
                "semantic_rag"
        );
        InformationNeed inferred = service.infer("戴科彬法人是哪些主体", semanticIntent);
        assertEquals("role", inferred.facet());
        assertEquals("list", inferred.granularity());
        InformationNeedMerger merger = new InformationNeedMerger(catalogRegistry);
        InformationNeed need = merger.merge(
                com.qa.demo.qa.core.RetrievalStrategy.SEMANTIC_RAG,
                0.65,
                inferred,
                semanticIntent
        );
        assertEquals("role", need.facet());
        assertEquals("list", need.granularity());
        assertEquals("正在查询任职关系（业务库）…",
                catalogRegistry.thinkingMessageFor(need, semanticIntent));
        assertTrue(catalogRegistry.requiresPersonClarification(need, semanticIntent));
    }
}

package com.qa.demo.qa.retrieval.catalog;

import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import com.qa.demo.qa.retrieval.structured.RegionResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        catalogConfig.setNeedInferenceRules(List.of(operatingStatus));

        RetrievalCatalogConfig.NeedTemplate semantic = new RetrievalCatalogConfig.NeedTemplate();
        semantic.setFacet("semantic");
        semantic.setGranularity("narrative");
        semantic.setListExpected(false);
        catalogConfig.setQueryTypeMapping(Map.of("semantic", semantic));

        RetrievalCatalogRegistry registry = mock(RetrievalCatalogRegistry.class);
        when(registry.config()).thenReturn(catalogConfig);
        when(registry.mapQueryType("semantic")).thenReturn(semantic);

        BusinessRulesConfig rulesConfig = new BusinessRulesConfig();
        rulesConfig.getIntentRouting().setFollowUpReferenceMarkers(List.of("这些", "那些"));
        RegionResolverService regionResolver = mock(RegionResolverService.class);
        when(regionResolver.extractRegionCodes("北京的公司有哪些"))
                .thenReturn(new RegionResolverService.RegionResolveResult(List.of("110000"), List.of("北京市")));
        needInferenceService = new NeedInferenceService(
                registry, new ConversationScopeSupport(rulesConfig), rulesConfig, regionResolver);
    }

    @Test
    void pronounCertificateListIsNotTypeCatalog() {
        InformationNeed need = needInferenceService.infer("他有哪些资质证照", null);
        assertFalse(need.isTypeCatalog());
    }

    @Test
    void operatingStatusCatalog_beatsSemanticQueryType() {
        IntentDecision semanticIntent = new IntentDecision(
                "enterprise_qa",
                0.65,
                "llm",
                "semantic",
                "",
                List.of("公司"),
                "any"
        );
        InformationNeed need = needInferenceService.infer("公司经营状态包含哪些种类", semanticIntent);
        assertTrue(need.isTypeCatalog());
        assertEquals("profile", need.facet());
        assertEquals("inference_forced:operating_status_catalog", need.reason());
    }
}

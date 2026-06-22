package com.qa.demo.qa.retrieval.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalExecutionProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalCatalogRegistryExecutionTest {

    private RetrievalCatalogRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        RetrievalCatalogConfig config = new RetrievalCatalogConfig();
        config.getNeedExecutionProfiles().add(roleListProfile());
        config.getNeedExecutionProfiles().add(certificateListProfile());
        ObjectMapper mapper = new ObjectMapper();
        AssistantConfigJsonLoader loader = mock(AssistantConfigJsonLoader.class);
        when(loader.readTree("retrieval-catalog")).thenReturn(mapper.valueToTree(config));
        registry = new RetrievalCatalogRegistry(mapper, loader);
    }

    @Test
    void roleListNeedUsesDedicatedListExecution() {
        InformationNeed need = new InformationNeed("role", "list", true, 0.9, "test");
        IntentDecision intent = personIntent("legal_rep");
        RetrievalExecutionProfile profile = registry.executionFor(need, intent);
        assertTrue(profile.dedicatedListPath());
        assertTrue(profile.skipTruncation());
        assertTrue(profile.expandRecallTopK());
        assertEquals("dedicated_list_sql", profile.routeLabel());
        assertFalse(profile.shouldApplyCorrectionNarrow());
    }

    @Test
    void personCertificateListUsesDedicatedCertificateExecution() {
        InformationNeed need = new InformationNeed(
                "certificate",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.9,
                "test"
        );
        IntentDecision intent = personIntent("any");
        RetrievalExecutionProfile profile = registry.executionFor(need, intent);
        assertTrue(profile.dedicatedCertificatePath());
        assertTrue(profile.includeCompiledDocs());
        assertEquals("dedicated_certificate_sql", profile.routeLabel());
    }

    @Test
    void unknownNeedFallsBackToDefault() {
        InformationNeed need = new InformationNeed("unknown", "narrative", false, 0.5, "test");
        RetrievalExecutionProfile profile = registry.executionFor(need, new IntentDecision(
                "vector", 0.5, "rule", "", List.of(), "any", null, "semantic_rag"));
        assertEquals(RetrievalExecutionProfile.DEFAULT, profile);
    }

    private static RetrievalCatalogConfig.NeedExecutionProfile roleListProfile() {
        RetrievalCatalogConfig.NeedExecutionMatch match = new RetrievalCatalogConfig.NeedExecutionMatch();
        match.setFacets(List.of("role"));
        match.setGranularities(List.of("list"));
        match.setListExpected(true);
        RetrievalCatalogConfig.ExecutionTemplate execution = new RetrievalCatalogConfig.ExecutionTemplate();
        execution.setPath("dedicated_list");
        execution.setRouteLabel("dedicated_list_sql");
        execution.setSkipTruncation(true);
        execution.setSkipEmployeeBaseAppend(true);
        execution.setExpandRecallTopK(true);
        execution.setCorrectionEntityKind("");
        RetrievalCatalogConfig.NeedExecutionProfile profile = new RetrievalCatalogConfig.NeedExecutionProfile();
        profile.setId("role_list");
        profile.setMatch(match);
        profile.setExecution(execution);
        return profile;
    }

    private static RetrievalCatalogConfig.NeedExecutionProfile certificateListProfile() {
        RetrievalCatalogConfig.NeedExecutionMatch match = new RetrievalCatalogConfig.NeedExecutionMatch();
        match.setFacets(List.of("certificate"));
        match.setGranularities(List.of(InformationNeed.GRANULARITY_INSTANCE));
        match.setListExpected(true);
        match.setRequiresPerson(true);
        RetrievalCatalogConfig.ExecutionTemplate execution = new RetrievalCatalogConfig.ExecutionTemplate();
        execution.setPath("dedicated_certificate");
        execution.setRouteLabel("dedicated_certificate_sql");
        execution.setSkipTruncation(true);
        execution.setSkipEmployeeBaseAppend(true);
        execution.setExpandRecallTopK(true);
        execution.setIncludeCompiledDocs(true);
        execution.setCorrectionEntityKind("");
        RetrievalCatalogConfig.NeedExecutionProfile profile = new RetrievalCatalogConfig.NeedExecutionProfile();
        profile.setId("certificate_instance_list_with_person");
        profile.setMatch(match);
        profile.setExecution(execution);
        return profile;
    }

    private static IntentDecision personIntent(String roleFocus) {
        return new IntentDecision(
                "hybrid",
                0.9,
                "rule",
                "张三",
                List.of(),
                roleFocus,
                null,
                "graph_relational"
        );
    }

}

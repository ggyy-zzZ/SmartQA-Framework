package com.qa.demo.qa.retrieval.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.core.RetrievalExecutionProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        config.getQueryTypeMapping().put("person_role_list", roleListTemplate());
        config.getQueryTypeMapping().put("person_certificate_list", certificateListTemplate());
        ObjectMapper mapper = new ObjectMapper();
        AssistantConfigJsonLoader loader = mock(AssistantConfigJsonLoader.class);
        when(loader.readTree("retrieval-catalog")).thenReturn(mapper.valueToTree(config));
        registry = new RetrievalCatalogRegistry(mapper, loader);
    }

    @Test
    void personRoleListUsesDedicatedListExecution() {
        RetrievalExecutionProfile profile = registry.executionFor("person_role_list");
        assertTrue(profile.dedicatedListPath());
        assertTrue(profile.skipTruncation());
        assertTrue(profile.expandRecallTopK());
        assertEquals("unified_person_role", profile.routeLabel());
        assertFalse(profile.shouldApplyCorrectionNarrow());
    }

    @Test
    void personCertificateListUsesDedicatedCertificateExecution() {
        RetrievalExecutionProfile profile = registry.executionFor("person_certificate_list");
        assertTrue(profile.dedicatedCertificatePath());
        assertTrue(profile.includeCompiledDocs());
        assertEquals("unified_person_certificate", profile.routeLabel());
    }

    @Test
    void unknownQueryTypeFallsBackToDefault() {
        RetrievalExecutionProfile profile = registry.executionFor("unknown_type");
        assertEquals(RetrievalExecutionProfile.DEFAULT, profile);
    }

    private static RetrievalCatalogConfig.NeedTemplate roleListTemplate() {
        RetrievalCatalogConfig.ExecutionTemplate execution = new RetrievalCatalogConfig.ExecutionTemplate();
        execution.setPath("dedicated_list");
        execution.setRouteLabel("unified_person_role");
        execution.setSkipTruncation(true);
        execution.setSkipEmployeeBaseAppend(true);
        execution.setExpandRecallTopK(true);
        execution.setCorrectionEntityKind("");
        RetrievalCatalogConfig.NeedTemplate template = new RetrievalCatalogConfig.NeedTemplate();
        template.setExecution(execution);
        return template;
    }

    private static RetrievalCatalogConfig.NeedTemplate certificateListTemplate() {
        RetrievalCatalogConfig.ExecutionTemplate execution = new RetrievalCatalogConfig.ExecutionTemplate();
        execution.setPath("dedicated_certificate");
        execution.setRouteLabel("unified_person_certificate");
        execution.setSkipTruncation(true);
        execution.setSkipEmployeeBaseAppend(true);
        execution.setExpandRecallTopK(true);
        execution.setIncludeCompiledDocs(true);
        execution.setCorrectionEntityKind("");
        RetrievalCatalogConfig.NeedTemplate template = new RetrievalCatalogConfig.NeedTemplate();
        template.setExecution(execution);
        return template;
    }
}

package com.qa.demo.qa.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.retrieval.catalog.InformationNeedMerger;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InformationNeedMergeTest {

    private InformationNeedMerger merger;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AssistantConfigJsonLoader loader = mock(AssistantConfigJsonLoader.class);
        when(loader.readTree("retrieval-catalog")).thenReturn(
                mapper.readTree(Paths.get("src/main/resources/qa/retrieval-catalog.json").toFile()));
        merger = new InformationNeedMerger(new RetrievalCatalogRegistry(mapper, loader));
    }

    @Test
    void typeCatalogStrategyKeepsRuleFacet() {
        InformationNeed inferred = new InformationNeed(
                "profile",
                InformationNeed.GRANULARITY_TYPE_CATALOG,
                true,
                0.92,
                "inference_forced:operating_status_catalog"
        );
        InformationNeed merged = merger.merge(
                RetrievalStrategy.TYPE_CATALOG,
                0.88,
                inferred,
                null
        );
        assertTrue(merged.isTypeCatalog());
        assertEquals("profile", merged.facet());
    }

    @Test
    void ruleTypeCatalogBeatsLlmSemantic() {
        InformationNeed inferred = new InformationNeed(
                "profile",
                InformationNeed.GRANULARITY_TYPE_CATALOG,
                true,
                0.92,
                "inference_forced:operating_status_catalog"
        );
        InformationNeed merged = merger.merge(
                RetrievalStrategy.SEMANTIC_RAG,
                0.7,
                inferred,
                null
        );
        assertTrue(merged.isTypeCatalog());
        assertEquals("profile", merged.facet());
    }

    @Test
    void llmInstanceBeatsHeuristicTypeCatalog() {
        InformationNeed inferred = new InformationNeed(
                "certificate",
                InformationNeed.GRANULARITY_TYPE_CATALOG,
                true,
                0.88,
                "inference_heuristic:type_catalog"
        );
        InformationNeed merged = merger.merge(
                RetrievalStrategy.INSTANCE_FACT,
                0.85,
                inferred,
                null
        );
        assertEquals("profile", merged.facet());
        assertEquals(InformationNeed.GRANULARITY_INSTANCE, merged.granularity());
    }

    @Test
    void structuredListIntentKeepsCertificateNeed() {
        InformationNeed inferred = new InformationNeed(
                "certificate",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.85,
                "inference_rule:certificate_instance"
        );
        IntentDecision intent = new IntentDecision(
                "mysql", 0.9, "llm", "张三", List.of(), "any", null, "structured_list");
        InformationNeed merged = merger.merge(
                RetrievalStrategy.INSTANCE_FACT,
                0.9,
                inferred,
                intent
        );
        assertEquals("certificate", merged.facet());
        assertEquals(InformationNeed.GRANULARITY_INSTANCE, merged.granularity());
    }

    @Test
    void filterThresholdAggregateBeatsLlmStructuredList() {
        InformationNeed inferred = new InformationNeed(
                "establishment_date",
                InformationNeed.GRANULARITY_AGGREGATE,
                true,
                0.86,
                "inference:filter_threshold:establishment_date"
        );
        InformationNeed merged = merger.merge(
                RetrievalStrategy.STRUCTURED_LIST,
                0.8,
                inferred,
                null
        );
        assertTrue(merged.isAggregate());
        assertEquals("establishment_date", merged.facet());
    }

    @Test
    void structuredListUsesLlmWhenNotCatalog() {
        InformationNeed inferred = InformationNeed.defaultSemantic();
        InformationNeed merged = merger.merge(
                RetrievalStrategy.STRUCTURED_LIST,
                0.8,
                inferred,
                null
        );
        assertEquals("list", merged.facet());
        assertEquals("list", merged.granularity());
    }

    @Test
    void pronounCertificateNeedBeatsLlmStructuredList() {
        InformationNeed inferred = new InformationNeed(
                "certificate",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.86,
                "inference_rule:pronoun_certificate_list"
        );
        InformationNeed merged = merger.merge(
                RetrievalStrategy.STRUCTURED_LIST,
                0.85,
                inferred,
                null
        );
        assertEquals("certificate", merged.facet());
        assertEquals(InformationNeed.GRANULARITY_INSTANCE, merged.granularity());
    }

    @Test
    void roleListBeatsLlmStructuredList() {
        InformationNeed inferred = new InformationNeed(
                "role",
                "list",
                true,
                0.82,
                "inference_rule:role_list"
        );
        InformationNeed merged = merger.merge(
                RetrievalStrategy.STRUCTURED_LIST,
                0.85,
                inferred,
                null
        );
        assertEquals("role", merged.facet());
        assertEquals("list", merged.granularity());
    }

    @Test
    void roleListFromRuleBeatsLlmSemantic() {
        InformationNeed inferred = new InformationNeed(
                "role",
                "list",
                true,
                0.82,
                "inference_rule:legal_rep_subject_reverse"
        );
        IntentDecision intent = new IntentDecision(
                "vector", 0.65, "llm", "戴科彬", List.of(), "any", null, "semantic_rag");
        InformationNeed merged = merger.merge(
                RetrievalStrategy.SEMANTIC_RAG,
                0.65,
                inferred,
                intent
        );
        assertEquals("role", merged.facet());
        assertEquals("list", merged.granularity());
    }
}

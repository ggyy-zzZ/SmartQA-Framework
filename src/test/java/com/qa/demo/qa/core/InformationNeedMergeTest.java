package com.qa.demo.qa.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InformationNeedMergeTest {

    @Test
    void typeCatalogStrategyKeepsRuleFacet() {
        InformationNeed inferred = new InformationNeed(
                "profile",
                InformationNeed.GRANULARITY_TYPE_CATALOG,
                true,
                0.92,
                "inference_forced:operating_status_catalog"
        );
        InformationNeed merged = InformationNeed.mergeWithLlmStrategy(
                RetrievalStrategy.TYPE_CATALOG,
                0.88,
                inferred
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
        InformationNeed merged = InformationNeed.mergeWithLlmStrategy(
                RetrievalStrategy.SEMANTIC_RAG,
                0.7,
                inferred
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
        InformationNeed merged = InformationNeed.mergeWithLlmStrategy(
                RetrievalStrategy.INSTANCE_FACT,
                0.85,
                inferred
        );
        assertEquals("profile", merged.facet());
        assertEquals(InformationNeed.GRANULARITY_INSTANCE, merged.granularity());
    }

    @Test
    void queryTypeCertificateBeatsLlmInstanceProfile() {
        InformationNeed inferred = new InformationNeed(
                "certificate",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.85,
                "query_type_mapping:person_certificate_list"
        );
        InformationNeed merged = InformationNeed.mergeWithLlmStrategy(
                RetrievalStrategy.INSTANCE_FACT,
                0.9,
                inferred
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
        InformationNeed merged = InformationNeed.mergeWithLlmStrategy(
                RetrievalStrategy.STRUCTURED_LIST,
                0.8,
                inferred
        );
        assertTrue(merged.isAggregate());
        assertEquals("establishment_date", merged.facet());
    }

    @Test
    void structuredListUsesLlmWhenNotCatalog() {
        InformationNeed inferred = InformationNeed.defaultSemantic();
        InformationNeed merged = InformationNeed.mergeWithLlmStrategy(
                RetrievalStrategy.STRUCTURED_LIST,
                0.8,
                inferred
        );
        assertEquals("list", merged.facet());
        assertEquals("list", merged.granularity());
    }
}

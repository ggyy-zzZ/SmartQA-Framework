package com.qa.demo.qa.intent;

import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.retrieval.EmployeeBaseKnowledgeService;
import com.qa.demo.qa.retrieval.GraphContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonNameResolverTest {

    private EmployeeBaseKnowledgeService employeeBase;
    private ActiveLearningService activeLearning;
    private GraphContextService graphContextService;
    private PersonNameResolver resolver;

    @BeforeEach
    void setUp() {
        employeeBase = mock(EmployeeBaseKnowledgeService.class);
        activeLearning = mock(ActiveLearningService.class);
        graphContextService = mock(GraphContextService.class);
        resolver = new PersonNameResolver(employeeBase, activeLearning, graphContextService);
    }

    @Test
    void prefersActiveLearningAlias() {
        when(activeLearning.resolvePersonAlias(eq("戴先生"), any())).thenReturn("戴科彬");
        assertEquals("戴科彬", resolver.resolve("戴先生", List.of()));
        assertEquals("戴科彬", resolver.resolve("戴先生", List.of(), "any").canonicalName());
    }

    @Test
    void resolvesViaGraphRoleWhenEmployeeAmbiguous() {
        when(activeLearning.resolvePersonAlias(eq("戴先生"), any())).thenReturn("戴先生");
        when(employeeBase.resolveCanonicalName("戴先生")).thenReturn("戴先生");
        when(graphContextService.listPersonNamesByHintAndRole(eq("戴"), eq("legal_rep"), anyInt()))
                .thenReturn(List.of("戴科彬"));
        PersonNameResolution resolution = resolver.resolve("戴先生", List.of(), "legal_rep");
        assertEquals("戴科彬", resolution.canonicalName());
        assertTrue(!resolution.ambiguous());
    }

    @Test
    void marksAmbiguousWhenGraphReturnsMultiple() {
        when(activeLearning.resolvePersonAlias(eq("戴先生"), any())).thenReturn("戴先生");
        when(employeeBase.resolveCanonicalName("戴先生")).thenReturn("戴先生");
        when(graphContextService.listPersonNamesByHintAndRole(any(), eq("legal_rep"), anyInt()))
                .thenReturn(List.of("戴科彬", "戴小明"));
        PersonNameResolution resolution = resolver.resolve("戴先生", List.of(), "legal_rep");
        assertTrue(resolution.needsClarification());
    }
}

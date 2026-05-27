package com.qa.demo.qa.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.knowledge.EvidenceSchemaRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmployeeBaseKnowledgeServiceResolveTest {

    private EmployeeBaseKnowledgeService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new EmployeeBaseKnowledgeService(new QaAssistantProperties(), new EvidenceSchemaRegistry(new ObjectMapper()));
        index(1, "戴科彬", null);
        index(2, "戴小明", null);
    }

    @Test
    void resolvesHonorificToUniqueFullName() throws Exception {
        clearIndex();
        index(1, "戴科彬", null);
        assertEquals("戴科彬", service.resolveCanonicalName("戴先生"));
    }

    @Test
    void keepsHonorificWhenMultipleSameSurname() {
        assertEquals("戴先生", service.resolveCanonicalName("戴先生"));
    }

    private void index(int id, String name, String anotherName) throws Exception {
        service.getClass().getDeclaredMethod("load").setAccessible(true);
        Field idToRecord = EmployeeBaseKnowledgeService.class.getDeclaredField("idToRecord");
        idToRecord.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, EmployeeBaseKnowledgeService.EmployeeRecord> map =
                (Map<Integer, EmployeeBaseKnowledgeService.EmployeeRecord>) idToRecord.get(service);
        map.put(id, new EmployeeBaseKnowledgeService.EmployeeRecord(id, name, anotherName));

        Field nameToId = EmployeeBaseKnowledgeService.class.getDeclaredField("nameToId");
        nameToId.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Integer> names = (Map<String, Integer>) nameToId.get(service);
        if (name != null && !name.isBlank()) {
            names.put(name.trim().toLowerCase(), id);
        }
    }

    private void clearIndex() throws Exception {
        Field idToRecord = EmployeeBaseKnowledgeService.class.getDeclaredField("idToRecord");
        idToRecord.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, ?> map = (Map<Integer, ?>) idToRecord.get(service);
        map.clear();
        Field nameToId = EmployeeBaseKnowledgeService.class.getDeclaredField("nameToId");
        nameToId.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> names = (Map<String, ?>) nameToId.get(service);
        names.clear();
    }
}

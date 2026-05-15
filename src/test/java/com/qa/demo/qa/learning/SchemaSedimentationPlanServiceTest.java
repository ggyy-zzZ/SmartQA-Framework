package com.qa.demo.qa.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaSedimentationPlanServiceTest {

    @Test
    void extractJsonObject_stripsFence() {
        String wrapped = "```json\n{\"feasible\":true}\n```";
        assertEquals("{\"feasible\":true}", SchemaSedimentationPlanService.extractJsonObject(wrapped));
    }

    @Test
    void extractJsonObject_prefixNoise() {
        String s = "here\n{\"a\":1}";
        assertEquals("{\"a\":1}", SchemaSedimentationPlanService.extractJsonObject(s));
    }
}

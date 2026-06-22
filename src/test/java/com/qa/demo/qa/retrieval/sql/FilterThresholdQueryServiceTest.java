package com.qa.demo.qa.retrieval.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FilterThresholdQueryServiceTest {

    @Test
    void parseStoredCapitalWanYuan() {
        assertEquals(1_000_000L, FilterThresholdQueryService.parseStoredCapitalYuan("100万"));
        assertEquals(1_000_000L, FilterThresholdQueryService.parseStoredCapitalYuan("100w"));
    }

    @Test
    void parseStoredCapitalIgnoresPlaceholder() {
        assertNull(FilterThresholdQueryService.parseStoredCapitalYuan("未维护"));
    }
}

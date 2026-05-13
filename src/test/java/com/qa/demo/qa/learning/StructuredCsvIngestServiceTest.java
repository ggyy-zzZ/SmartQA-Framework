package com.qa.demo.qa.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructuredCsvIngestServiceTest {

    @Test
    void countDataRows_skipsHeaderAndBlanks() {
        assertEquals(2, StructuredCsvIngestService.countDataRows("h1,h2\na,b\n\nc,d", true));
        assertEquals(3, StructuredCsvIngestService.countDataRows("a\nb\nc", false));
        assertEquals(0, StructuredCsvIngestService.countDataRows("\n\n", true));
        assertEquals(0, StructuredCsvIngestService.countDataRows("only_header", true));
        assertEquals(1, StructuredCsvIngestService.countDataRows("only_header", false));
    }
}

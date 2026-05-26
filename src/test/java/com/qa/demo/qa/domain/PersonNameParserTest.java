package com.qa.demo.qa.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonNameParserTest {

    @Test
    void stripsHonorificSuffix() {
        assertEquals("戴", PersonNameParser.stripHonorific("戴先生"));
        assertTrue(PersonNameParser.hasHonorificSuffix("戴先生"));
    }

    @Test
    void leavesFullNameUnchanged() {
        assertEquals("戴科彬", PersonNameParser.stripHonorific("戴科彬"));
        assertEquals("戴科彬", PersonNameParser.stripHonorific("戴科彬"));
    }
}

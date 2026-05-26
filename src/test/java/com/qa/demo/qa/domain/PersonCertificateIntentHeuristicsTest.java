package com.qa.demo.qa.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonCertificateIntentHeuristicsTest {

    @Test
    void detectsPersonCertificateQuestion() {
        assertTrue(PersonCertificateIntentHeuristics.isPersonCertificateListQuestion(
                "张雁雯负责哪些证照", "张雁雯"));
    }

    @Test
    void extractsPersonNameBeforeStewardVerb() {
        assertEquals("张雁雯", PersonCertificateIntentHeuristics.extractPersonNameFromQuestion(
                "张雁雯管哪些资质证照"));
    }

    @Test
    void certificateTypeFollowUpWithoutCertKeyword() {
        assertTrue(PersonCertificateIntentHeuristics.isCertificateTypeFollowUp("涉及类型有哪些"));
    }
}

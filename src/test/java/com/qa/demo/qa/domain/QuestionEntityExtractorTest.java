package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QuestionEntityExtractorTest {

    private static QuestionEntityExtractor extractor;

    @BeforeAll
    static void setUp() {
        extractor = new QuestionEntityExtractor(EnterpriseLexicon.loadDefault(new ObjectMapper()));
    }

    @Test
    void extractsPersonFromSubjectPhrasing() {
        assertEquals("戴科彬", extractor.extractPersonName("戴科彬是哪些主体的法人"));
    }

    @Test
    void infersPersonRoleListQueryType() {
        String type = extractor.inferQueryType("戴科彬是哪些主体的法人", "戴科彬");
        assertEquals("person_role_list", type);
    }

    @Test
    void extractsCompanyHintBySuffix() {
        assertNotNull(extractor.extractCompanyHints("万仕道（北京）管理咨询有限责任公司 的股东"));
    }
}

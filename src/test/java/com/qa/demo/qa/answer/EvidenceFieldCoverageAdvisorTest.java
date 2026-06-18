package com.qa.demo.qa.answer;

import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceFieldCoverageAdvisorTest {

    private EvidenceFieldCoverageAdvisor advisor;

    @BeforeEach
    void setUp() {
        BusinessRulesConfig config = new BusinessRulesConfig();
        BusinessRulesConfig.FilterFieldCoverageRule capital = new BusinessRulesConfig.FilterFieldCoverageRule();
        capital.setId("registered_capital");
        capital.setDisplayLabel("注册资本");
        capital.setQuestionAnyKeywords(List.of("注册资本", "注册资金"));
        capital.setFilterIntentKeywords(List.of("有哪些", "超过", "以上"));
        capital.setSnippetMarkers(List.of("注册资本", "registeredCapital"));
        BusinessRulesConfig.FilterFieldCoverageRule establishment = new BusinessRulesConfig.FilterFieldCoverageRule();
        establishment.setId("establishment_date");
        establishment.setDisplayLabel("成立日期");
        establishment.setQuestionAnyKeywords(List.of("设立日期", "成立日期"));
        establishment.setFilterIntentKeywords(List.of("有哪些", "之前", "之后"));
        establishment.setSnippetMarkers(List.of("成立日期", "establishmentDate"));
        config.setFilterFieldCoverageRules(List.of(capital, establishment));
        advisor = new EvidenceFieldCoverageAdvisor(config);
    }

    @Test
    void detectsMissingCapitalFieldWhenEvidenceEmpty() {
        var gap = advisor.detectFilterFieldGap(
                "注册资金 100w 以上的公司有哪些",
                List.of(),
                InformationNeed.defaultSemantic()
        );
        assertTrue(gap.isPresent());
        assertEquals("registered_capital", gap.get().ruleId());
    }

    @Test
    void detectsMissingCapitalField() {
        var gap = advisor.detectFilterFieldGap(
                "注册资金 100w 以上的公司有哪些",
                List.of(ContextChunk.ofCompany("1", "测试公司", "field", "状态=存续", 5.0, "vector")),
                InformationNeed.defaultSemantic()
        );
        assertTrue(gap.isPresent());
        assertTrue(gap.get().userHint().contains("注册资本"));
    }

    @Test
    void detectsGapWhenOnlyLabelWithoutValue() {
        var gap = advisor.detectFilterFieldGap(
                "注册资金 100w 以上的公司有哪些",
                List.of(ContextChunk.ofCompany("1", "测试公司", "field", "注册资本：; 经营状态=存续", 5.0, "vector")),
                new InformationNeed("list", "list", true, 0.8, "llm")
        );
        assertTrue(gap.isPresent());
    }

    @Test
    void detectsGapWhenTooFewChunksHaveCapitalValue() {
        List<ContextChunk> evidence = new ArrayList<>();
        evidence.add(ContextChunk.ofCompany("1", "A", "field", "注册资本=1000万", 5.0, "vector"));
        for (int i = 2; i <= 12; i++) {
            evidence.add(ContextChunk.ofCompany(String.valueOf(i), "公司" + i, "field", "经营状态=存续", 4.0, "vector"));
        }
        var gap = advisor.detectFilterFieldGap(
                "注册资金 100w 以上的公司有哪些",
                evidence,
                InformationNeed.defaultSemantic()
        );
        assertTrue(gap.isPresent());
    }

    @Test
    void skipsWhenEnoughChunksHaveCapitalValue() {
        List<ContextChunk> evidence = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            evidence.add(ContextChunk.ofCompany(String.valueOf(i), "公司" + i, "field", "注册资本=" + i + "00万", 5.0, "vector"));
        }
        var gap = advisor.detectFilterFieldGap(
                "注册资金 100w 以上的公司有哪些",
                evidence,
                InformationNeed.defaultSemantic()
        );
        assertFalse(gap.isPresent());
    }

    @Test
    void skipsWhenSingleChunkHasCapitalValue() {
        var gap = advisor.detectFilterFieldGap(
                "注册资金 100w 以上的公司有哪些",
                List.of(ContextChunk.ofCompany("1", "测试公司", "field", "注册资本=1000万", 5.0, "vector")),
                InformationNeed.defaultSemantic()
        );
        assertFalse(gap.isPresent());
    }

    @Test
    void detectsGapWhenEstablishmentCoverageRatioLow() {
        List<ContextChunk> evidence = new ArrayList<>();
        for (int i = 1; i <= 19; i++) {
            evidence.add(ContextChunk.ofCompany(String.valueOf(i), "公司" + i, "field", "成立日期=2015-01-01", 5.0, "vector"));
        }
        for (int i = 20; i <= 30; i++) {
            evidence.add(ContextChunk.ofCompany(String.valueOf(i), "公司" + i, "field", "经营状态=存续", 4.0, "vector"));
        }
        var gap = advisor.detectFilterFieldGap(
                "设立日期在2010年之前的公司有哪些",
                evidence,
                InformationNeed.defaultSemantic()
        );
        assertTrue(gap.isPresent());
    }

    @Test
    void skipsTypeCatalogNeed() {
        InformationNeed catalog = new InformationNeed(
                "profile", InformationNeed.GRANULARITY_TYPE_CATALOG, true, 0.9, "test");
        var gap = advisor.detectFilterFieldGap(
                "经营状态包含哪些种类",
                List.of(),
                catalog
        );
        assertFalse(gap.isPresent());
    }
}

package com.qa.demo.qa.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlSchemaCatalogAssessmentServiceTest {

    @Test
    void combineAppendsAssessmentSection() {
        String out = MysqlSchemaCatalogAssessmentService.combineCatalogAndAssessment("# Cat\n", "1) ok");
        assertTrue(out.contains("大模型沉淀方案评估"));
        assertTrue(out.contains("1) ok"));
    }
}

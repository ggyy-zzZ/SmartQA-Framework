package com.qa.demo.qa.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class QaTaskPlannerServiceTest {

    private QaAssistantProperties properties;
    private QaTaskPlannerService planner;

    @BeforeEach
    void setUp() {
        properties = new QaAssistantProperties();
        properties.setAgentMultiStepEnabled(true);
        properties.setIntentLlmEnabled(false);
        planner = new QaTaskPlannerService(properties, mock(MiniMaxClient.class), new ObjectMapper());
    }

    @Test
    void singleShotWhenMultiStepDisabled() {
        properties.setAgentMultiStepEnabled(false);
        AgentTaskPlan plan = planner.plan("对比 A 和 B 的注册资本", intent(), need());
        assertFalse(plan.requiresMultiStepExecution());
        assertEquals("disabled", plan.plannerSource());
    }

    @Test
    void heuristicCompareSplitsTwoRetrievePlusSynthesize() {
        AgentTaskPlan plan = planner.plan("对比甲公司和乙公司的注册资本", intent(), need());
        assertTrue(plan.requiresMultiStepExecution());
        assertEquals("heuristic_compare", plan.plannerSource());
        assertEquals(3, plan.steps().size());
        assertEquals(AgentTool.STRUCTURED_RETRIEVE, plan.steps().get(0).tool());
        assertEquals(AgentTool.STRUCTURED_RETRIEVE, plan.steps().get(1).tool());
        assertEquals(AgentTool.SYNTHESIZE, plan.steps().get(2).tool());
    }

    @Test
    void needsMultiStepForCrossSourceKeywords() {
        assertTrue(planner.needsMultiStep("分别查询两家公司的证照情况", intent(), need()));
        assertFalse(planner.needsMultiStep("甲公司的法定代表人是谁", intent(), need()));
    }

    private static IntentDecision intent() {
        return new IntentDecision("hybrid", 0.8, "test");
    }

    private static InformationNeed need() {
        return InformationNeed.defaultSemantic();
    }
}

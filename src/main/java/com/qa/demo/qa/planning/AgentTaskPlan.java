package com.qa.demo.qa.planning;

import java.util.List;
import java.util.Optional;

/**
 * 多步任务计划：单步时与原有单轮检索等价。
 */
public record AgentTaskPlan(
        boolean multiStep,
        List<AgentTaskStep> steps,
        String plannerSource
) {
    public static AgentTaskPlan single(String question, String source) {
        return new AgentTaskPlan(
                false,
                List.of(new AgentTaskStep("1", AgentTool.STRUCTURED_RETRIEVE, question, List.of())),
                source
        );
    }

    public boolean requiresMultiStepExecution() {
        if (!multiStep || steps == null || steps.isEmpty()) {
            return false;
        }
        long retrieveSteps = steps.stream()
                .filter(s -> s.tool() != AgentTool.SYNTHESIZE)
                .count();
        return retrieveSteps > 1 || steps.stream().anyMatch(s -> s.tool() == AgentTool.SYNTHESIZE);
    }

    public List<AgentTaskStep> retrieveSteps() {
        return steps.stream().filter(s -> s.tool() != AgentTool.SYNTHESIZE).toList();
    }

    public Optional<AgentTaskStep> synthesizeStep() {
        return steps.stream().filter(s -> s.tool() == AgentTool.SYNTHESIZE).findFirst();
    }
}

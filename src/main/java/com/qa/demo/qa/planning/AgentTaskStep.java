package com.qa.demo.qa.planning;

import java.util.List;

/**
 * 单步子任务：由 Planner 产出，Executor 按 dependsOn 顺序执行。
 */
public record AgentTaskStep(
        String id,
        AgentTool tool,
        String question,
        List<String> dependsOn
) {
    public AgentTaskStep {
        if (id == null || id.isBlank()) {
            id = "1";
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        question = question == null ? "" : question.trim();
    }
}

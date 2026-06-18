package com.qa.demo.qa.execution;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.planning.AgentTaskStep;
import com.qa.demo.qa.planning.AgentTool;

import java.util.List;

/**
 * 单步执行结果，供跨源综合与响应 trace 使用。
 */
public record AgentStepResult(
        String stepId,
        AgentTool tool,
        String question,
        String retrievalSource,
        List<ContextChunk> evidence,
        String summary
) {
    public int evidenceCount() {
        return evidence == null ? 0 : evidence.size();
    }
}

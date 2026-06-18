package com.qa.demo.qa.execution;

import com.qa.demo.qa.constraint.ConstraintSet;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.intent.FollowUpIntentContext;
import com.qa.demo.qa.intent.IntentRouterService;
import com.qa.demo.qa.intent.IntentRoutingOutcome;
import com.qa.demo.qa.intent.IntentScopeNormalizer;
import com.qa.demo.qa.planning.AgentTaskPlan;
import com.qa.demo.qa.planning.AgentTaskStep;
import com.qa.demo.qa.planning.AgentTool;
import com.qa.demo.qa.retrieval.QaRetrievalPipeline;
import com.qa.demo.qa.retrieval.catalog.NeedInferenceService;
import com.qa.demo.qa.retrieval.sql.AggregateCountQueryService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多步 Executor：按 Planner 子任务依次调用检索工具，合并证据供综合/计算。
 */
@Service
public class QaMultiStepExecutor {

    private final QaExecutionService executionService;
    private final IntentRouterService intentRouterService;
    private final IntentScopeNormalizer intentScopeNormalizer;
    private final NeedInferenceService needInferenceService;
    private final AggregateCountQueryService aggregateCountQueryService;
    private final QaRetrievalPipeline retrievalPipeline;

    public QaMultiStepExecutor(
            QaExecutionService executionService,
            IntentRouterService intentRouterService,
            IntentScopeNormalizer intentScopeNormalizer,
            NeedInferenceService needInferenceService,
            AggregateCountQueryService aggregateCountQueryService,
            QaRetrievalPipeline retrievalPipeline
    ) {
        this.executionService = executionService;
        this.intentRouterService = intentRouterService;
        this.intentScopeNormalizer = intentScopeNormalizer;
        this.needInferenceService = needInferenceService;
        this.aggregateCountQueryService = aggregateCountQueryService;
        this.retrievalPipeline = retrievalPipeline;
    }

    public record MultiStepOutcome(
            String retrievalSource,
            List<ContextChunk> evidence,
            List<AgentStepResult> stepResults,
            AgentTaskPlan plan,
            boolean needsSynthesize
    ) {
    }

    public MultiStepOutcome execute(
            AgentTaskPlan plan,
            String scope,
            String originalQuestion,
            List<ContextChunk> learnedFirst,
            IntentDecision rootIntent,
            boolean explicitCompanyHint,
            ConstraintSet constraint,
            boolean appliedLearningRewrite
    ) throws IOException {
        Map<String, AgentStepResult> completed = new LinkedHashMap<>();
        List<ContextChunk> mergedEvidence = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (AgentTaskStep step : plan.steps()) {
            if (step.tool() == AgentTool.SYNTHESIZE) {
                continue;
            }
            if (!dependenciesMet(step, completed.keySet())) {
                continue;
            }
            AgentStepResult result = executeStep(
                    step,
                    scope,
                    learnedFirst,
                    rootIntent,
                    explicitCompanyHint,
                    constraint,
                    appliedLearningRewrite
            );
            completed.put(step.id(), result);
            appendUnique(mergedEvidence, seenKeys, result.evidence());
        }

        boolean needsSynthesize = plan.synthesizeStep().isPresent() || plan.multiStep();
        String source = "agent_multistep_" + plan.plannerSource();
        return new MultiStepOutcome(
                source,
                mergedEvidence,
                List.copyOf(completed.values()),
                plan,
                needsSynthesize
        );
    }

    private AgentStepResult executeStep(
            AgentTaskStep step,
            String scope,
            List<ContextChunk> learnedFirst,
            IntentDecision rootIntent,
            boolean explicitCompanyHint,
            ConstraintSet constraint,
            boolean appliedLearningRewrite
    ) throws IOException {
        return switch (step.tool()) {
            case AGGREGATE_COUNT -> executeAggregate(step);
            case DOCUMENT_RETRIEVE -> executeDocument(step, scope);
            case STRUCTURED_RETRIEVE -> executeStructured(
                    step, scope, learnedFirst, explicitCompanyHint, constraint, appliedLearningRewrite);
            case SYNTHESIZE -> new AgentStepResult(
                    step.id(), step.tool(), step.question(), "none", List.of(), "");
        };
    }

    private AgentStepResult executeStructured(
            AgentTaskStep step,
            String scope,
            List<ContextChunk> learnedFirst,
            boolean explicitCompanyHint,
            ConstraintSet constraint,
            boolean appliedLearningRewrite
    ) throws IOException {
        IntentRoutingOutcome routing = intentRouterService.decide(
                step.question(), explicitCompanyHint, learnedFirst, FollowUpIntentContext.inactive());
        IntentDecision subIntent = intentScopeNormalizer.normalize(routing.decision(), step.question());
        InformationNeed need = needInferenceService.infer(step.question(), subIntent);
        QaExecutionService.RetrievalOutcome outcome = executionService.retrieve(
                scope,
                step.question(),
                step.question(),
                learnedFirst,
                subIntent,
                need,
                constraint,
                explicitCompanyHint,
                appliedLearningRewrite
        );
        return new AgentStepResult(
                step.id(),
                step.tool(),
                step.question(),
                outcome.retrievalSource(),
                outcome.evidence(),
                summarizeEvidence(step.id(), step.tool(), outcome.evidence())
        );
    }

    private AgentStepResult executeAggregate(AgentTaskStep step) {
        List<ContextChunk> evidence = aggregateCountQueryService.retrieve(step.question());
        return new AgentStepResult(
                step.id(),
                step.tool(),
                step.question(),
                "llm_aggregate_count",
                evidence,
                summarizeEvidence(step.id(), step.tool(), evidence)
        );
    }

    private AgentStepResult executeDocument(AgentTaskStep step, String scope) throws IOException {
        IntentDecision docIntent = new IntentDecision(
                "document",
                0.6,
                "agent_document_substep",
                "",
                "",
                List.of(),
                "any",
                null,
                ""
        );
        QaRetrievalPipeline.RetrievalResult result = retrievalPipeline.retrieveByIntent(docIntent, step.question());
        return new AgentStepResult(
                step.id(),
                step.tool(),
                step.question(),
                result.retrievalSource(),
                result.evidence(),
                summarizeEvidence(step.id(), step.tool(), result.evidence())
        );
    }

    private static boolean dependenciesMet(AgentTaskStep step, Set<String> completedIds) {
        if (step.dependsOn() == null || step.dependsOn().isEmpty()) {
            return true;
        }
        return completedIds.containsAll(step.dependsOn());
    }

    private static void appendUnique(List<ContextChunk> target, Set<String> seen, List<ContextChunk> items) {
        if (items == null) {
            return;
        }
        for (ContextChunk chunk : items) {
            if (chunk == null) {
                continue;
            }
            String key = chunk.source() + "|" + chunk.anchorId() + "|" + chunk.displayLabel() + "|" + chunk.field();
            if (seen.add(key)) {
                target.add(chunk);
            }
        }
    }

    private static String summarizeEvidence(String stepId, AgentTool tool, List<ContextChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "步骤" + stepId + "(" + tool.wireName() + "): 无证据";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("步骤").append(stepId).append("(").append(tool.wireName()).append(") 命中 ")
                .append(evidence.size()).append(" 条: ");
        int limit = Math.min(3, evidence.size());
        for (int i = 0; i < limit; i++) {
            ContextChunk c = evidence.get(i);
            if (i > 0) {
                sb.append(" | ");
            }
            String snippet = c.snippet() == null ? "" : c.snippet();
            if (snippet.length() > 120) {
                snippet = snippet.substring(0, 120) + "…";
            }
            sb.append(snippet);
        }
        return sb.toString();
    }

    public String buildStepContextBlock(List<AgentStepResult> stepResults) {
        if (stepResults == null || stepResults.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("【多步任务中间结果】\n");
        for (AgentStepResult step : stepResults) {
            block.append("- ").append(step.summary()).append("\n");
        }
        block.append("请基于以上各步证据完成对比/计算/综合，数值仅来自证据，不足则说明缺口。\n");
        return block.toString();
    }
}

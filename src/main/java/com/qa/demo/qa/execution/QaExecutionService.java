package com.qa.demo.qa.execution;

import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.answer.QaAnswerFallbackService;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.constraint.ConstraintSet;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.QaScopes;
import com.qa.demo.qa.retrieval.QaRetrievalPipeline;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Executor 层：按规划结果调用检索器与生成器（MySQL / SQL / 向量 / 文档）。
 */
@Service
public class QaExecutionService {

    private final QaRetrievalPipeline retrievalPipeline;
    private final QaAssistantProperties properties;
    private final MiniMaxClient miniMaxClient;
    private final QaAnswerFallbackService answerFallbackService;

    public QaExecutionService(
            QaRetrievalPipeline retrievalPipeline,
            QaAssistantProperties properties,
            MiniMaxClient miniMaxClient,
            QaAnswerFallbackService answerFallbackService
    ) {
        this.retrievalPipeline = retrievalPipeline;
        this.properties = properties;
        this.miniMaxClient = miniMaxClient;
        this.answerFallbackService = answerFallbackService;
    }

    public record RetrievalOutcome(
            String retrievalSource,
            List<ContextChunk> evidence,
            QaRetrievalPipeline.RetrievalResult raw
    ) {
    }

    public record GenerationOutcome(
            String answer,
            String route,
            double confidence,
            boolean degraded,
            String fallbackReason
    ) {
    }

    public RetrievalOutcome retrieve(
            String scope,
            String question,
            String retrievalQuestion,
            List<ContextChunk> learnedFirst,
            IntentDecision intentDecision,
            InformationNeed informationNeed,
            ConstraintSet constraint,
            boolean explicitCompanyHint,
            boolean appliedLearningRewrite
    ) throws IOException {
        QaRetrievalPipeline.RetrievalResult result;
        if (QaScopes.ENTERPRISE.equals(scope) && properties.isUnifiedRetrievalEnabled()) {
            result = retrievalPipeline.retrieveUnifiedEnterprise(
                    retrievalQuestion, learnedFirst, intentDecision, informationNeed, constraint);
        } else if (retrievalPipeline.preferActiveLearning(question, explicitCompanyHint, learnedFirst)) {
            result = new QaRetrievalPipeline.RetrievalResult("active_learning_priority", learnedFirst);
        } else {
            result = retrievalPipeline.retrieveByIntent(intentDecision, retrievalQuestion);
            if (QaScopes.ENTERPRISE.equals(scope)) {
                result = retrievalPipeline.mergeEnterpriseActiveLearning(result, learnedFirst, explicitCompanyHint);
            }
        }

        List<ContextChunk> evidence = new ArrayList<>(result.evidence());
        if (QaScopes.ENTERPRISE.equals(scope) && appliedLearningRewrite) {
            evidence.removeIf(c ->
                    "mysql-employee-precheck".equals(c.source())
                            && "employee_not_found".equals(c.anchorId())
            );
        }
        return new RetrievalOutcome(result.retrievalSource(), evidence, result);
    }

    public GenerationOutcome generateMultiStep(
            String modelContextBlock,
            String stepContextBlock,
            String question,
            List<ContextChunk> evidence,
            IntentDecision intentDecision,
            String retrievalSource
    ) {
        String mergedContext = mergeContext(modelContextBlock, stepContextBlock);
        return generate(mergedContext, question, evidence, intentDecision, retrievalSource, true);
    }

    private static String mergeContext(String modelContextBlock, String stepContextBlock) {
        String a = modelContextBlock == null ? "" : modelContextBlock.trim();
        String b = stepContextBlock == null ? "" : stepContextBlock.trim();
        if (a.isBlank()) {
            return b;
        }
        if (b.isBlank()) {
            return a;
        }
        return a + "\n\n" + b;
    }

    public GenerationOutcome generate(
            String modelContextBlock,
            String question,
            List<ContextChunk> evidence,
            IntentDecision intentDecision,
            String retrievalSource,
            boolean allowGenerate
    ) {
        if (!allowGenerate) {
            return new GenerationOutcome("", retrievalSource, 0.20, false, "");
        }
        try {
            String answer = miniMaxClient.askWithEvidence(modelContextBlock, question, evidence, intentDecision);
            double confidence = answerFallbackService.calcConfidence(evidence);
            return new GenerationOutcome(answer, retrievalSource + "_generate_llm", confidence, false, "");
        } catch (Exception ex) {
            String fallbackReason = answerFallbackService.sanitizeError(ex.getMessage());
            String answer = answerFallbackService.buildFallbackAnswer(question, evidence);
            return new GenerationOutcome(answer, retrievalSource + "_fallback_template", 0.35, true, fallbackReason);
        }
    }
}

package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.learning.ActiveLearningService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索调度中心：在「用户原句」与「底库检索」之间插入策略层。
 * <p>
 * 当前能力：根据已命中的主动学习片段，把「别名→实名」类事实融入检索问句，
 * 避免「记忆里有别名、结构化检索仍用别名」的矛盾。
 * </p>
 * 后续可在此扩展：检索结果质检、多轮补检索、路由二次决策等。
 */
@Service
public class QaRetrievalOrchestrator {

    private final ActiveLearningService activeLearningService;

    public QaRetrievalOrchestrator(ActiveLearningService activeLearningService) {
        this.activeLearningService = activeLearningService;
    }

    /**
     * @param userQuestion           用户原始输入
     * @param learnedFromUserQuery 基于原句召回的主动学习证据（与检索用语同一轮）
     */
    public RetrievalPlan prepareRetrievalQuestion(String userQuestion, List<ContextChunk> learnedFromUserQuery) {
        String base = userQuestion == null ? "" : userQuestion.trim();
        String augmented = activeLearningService.augmentQuestionForStructuredRetrieval(
                userQuestion,
                learnedFromUserQuery
        );
        if (augmented == null) {
            augmented = base;
        }
        boolean rewritten = !augmented.equals(base);
        return new RetrievalPlan(augmented, rewritten);
    }

    public record RetrievalPlan(String retrievalQuestion, boolean appliedLearningRewrite) {
    }
}

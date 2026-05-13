package com.qa.demo.qa.learning;

import com.qa.demo.qa.answer.QaAnswerFallbackService;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.QaScopes;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动学习 API 响应体拼装（与 HTTP 字段约定一致，供编排层复用）。
 */
@Component
public class LearningResponseBuilder {

    private final QaAssistantProperties properties;
    private final QaAnswerFallbackService answerFallbackService;

    public LearningResponseBuilder(QaAssistantProperties properties, QaAnswerFallbackService answerFallbackService) {
        this.properties = properties;
        this.answerFallbackService = answerFallbackService;
    }

    public Map<String, Object> buildLearningResponse(
            String turnId,
            String question,
            ActiveLearningService.LearningResult result,
            String scope
    ) {
        boolean ok = result.success();
        String answer = ok
                ? "已完成主动学习并持久化：\n"
                + "- MySQL：" + summarizeSink(result.mysql()) + "\n"
                + "- 向量库：" + summarizeSink(result.vector()) + "\n"
                + "- 知识图谱：" + summarizeSink(result.graph())
                : "主动学习失败：" + result.message();
        Map<String, Object> response = new HashMap<>();
        response.put("turnId", turnId);
        response.put("question", question);
        response.put("answer", answer);
        response.put("canAnswer", ok);
        response.put("confidence", ok ? 0.95 : 0.30);
        response.put("route", "active_learning_persist");
        response.put("retrievalSource", "active_learning");
        response.put("intent", "learning");
        response.put("intentConfidence", 1.0);
        response.put("routeReason", "detect_learning_intent");
        response.put("evidence", List.of());
        response.put("degraded", !ok);
        response.put("knowledgeId", result.knowledgeId());
        response.put("learningTitle", result.title());
        response.put("sinkStatus", Map.of(
                "mysql", Map.of("ok", result.mysql().ok(), "detail", result.mysql().detail()),
                "vector", Map.of("ok", result.vector().ok(), "detail", result.vector().detail()),
                "graph", Map.of("ok", result.graph().ok(), "detail", result.graph().detail())
        ));
        response.put("docsDir", properties.getDocsDir());
        response.put("model", properties.getModel());
        response.put("knowledgeDepositTriggered", false);
        response.put("scope", QaScopes.normalize(scope));
        response.put("timestamp", OffsetDateTime.now().toString());
        return response;
    }

    private String summarizeSink(ActiveLearningService.SinkStatus status) {
        return status.ok() ? "成功" : "失败(" + answerFallbackService.sanitizeError(status.detail()) + ")";
    }
}

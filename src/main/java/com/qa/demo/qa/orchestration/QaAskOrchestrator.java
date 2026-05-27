package com.qa.demo.qa.orchestration;

import com.qa.demo.qa.answer.QaAnswerFallbackService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 问答编排入口：同步/流式均委托 {@link QaAskFlowService}，本类仅负责 SSE 包装与异步调度。
 */
@Service
public class QaAskOrchestrator {

    private final QaAskFlowService qaAskFlowService;
    private final QaAnswerFallbackService answerFallbackService;
    private final QaSseStreamSupport sseStreamSupport;

    public QaAskOrchestrator(
            QaAskFlowService qaAskFlowService,
            QaAnswerFallbackService answerFallbackService,
            QaSseStreamSupport sseStreamSupport
    ) {
        this.qaAskFlowService = qaAskFlowService;
        this.answerFallbackService = answerFallbackService;
        this.sseStreamSupport = sseStreamSupport;
    }

    public Map<String, Object> buildAskResponse(String question, String scope, String conversationId, Boolean followUpFlag)
            throws IOException {
        return qaAskFlowService.run(question, scope, conversationId, followUpFlag, QaAskProgress.NOOP);
    }

    public SseEmitter startAskStream(String question, String scope, String conversationId, Boolean followUpFlag) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> runStream(emitter, question, scope, conversationId, followUpFlag));
        return emitter;
    }

    private void runStream(SseEmitter emitter, String question, String scope, String conversationId, Boolean followUpFlag) {
        try {
            sseStreamSupport.emitThinking(emitter, "start", "已接收问题，开始分析。");
            QaAskProgress progress = (phase, message, details) ->
                    sseStreamSupport.emitThinking(emitter, phase, message, details);
            Map<String, Object> response = qaAskFlowService.run(question, scope, conversationId, followUpFlag, progress);
            boolean streamAnswer = !"ask_person_clarification".equals(response.get("route"))
                    && !"ask_company_clarification".equals(response.get("route"));
            sseStreamSupport.sendStreamResponse(emitter, response, streamAnswer);
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("qa_error").data(Map.of(
                        "message", answerFallbackService.sanitizeError(e.getMessage()),
                        "timestamp", OffsetDateTime.now().toString()
                )));
            } catch (Exception ignored) {
                // client disconnected
            }
            emitter.completeWithError(e);
        }
    }
}

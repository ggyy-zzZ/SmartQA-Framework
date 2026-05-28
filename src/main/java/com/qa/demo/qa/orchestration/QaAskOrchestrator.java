package com.qa.demo.qa.orchestration;

import com.qa.demo.qa.answer.QaAnswerFallbackService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 问答编排入口：同步/流式均委托 {@link QaAskFlowService}，本类仅负责 SSE 包装与异步调度。
 */
@Service
public class QaAskOrchestrator {

    private static final ExecutorService SSE_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "qa-sse-worker");
        t.setDaemon(true);
        return t;
    });

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

    public SseEmitter startAskStream(
            String question,
            String scope,
            String conversationId,
            Boolean followUpFlag,
            HttpServletResponse response
    ) {
        SseEmitter emitter = new SseEmitter(300_000L);
        SSE_EXECUTOR.execute(() -> runStream(emitter, response, question, scope, conversationId, followUpFlag));
        return emitter;
    }

    private void runStream(
            SseEmitter emitter,
            HttpServletResponse response,
            String question,
            String scope,
            String conversationId,
            Boolean followUpFlag
    ) {
        try {
            emitThinkingFlush(emitter, response, "start", "已接收问题，开始分析。");
            QaAskProgress progress = (phase, message, details) ->
                    emitThinkingFlush(emitter, response, phase, message, details);
            Map<String, Object> result = qaAskFlowService.run(question, scope, conversationId, followUpFlag, progress);
            boolean streamAnswer = !"ask_person_clarification".equals(result.get("route"))
                    && !"ask_company_clarification".equals(result.get("route"));
            sseStreamSupport.sendStreamResponse(emitter, result, streamAnswer);
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("qa_error").data(Map.of(
                        "message", answerFallbackService.sanitizeError(e.getMessage()),
                        "timestamp", OffsetDateTime.now().toString()
                )));
                flushResponse(response);
            } catch (Exception ignored) {
                // client disconnected
            }
            emitter.completeWithError(e);
        }
    }

    private void emitThinkingFlush(
            SseEmitter emitter,
            HttpServletResponse response,
            String phase,
            String message
    ) {
        emitThinkingFlush(emitter, response, phase, message, null);
    }

    private void emitThinkingFlush(
            SseEmitter emitter,
            HttpServletResponse response,
            String phase,
            String message,
            Map<String, Object> details
    ) {
        sseStreamSupport.emitThinking(emitter, phase, message, details);
        flushResponse(response);
    }

    private static void flushResponse(HttpServletResponse response) {
        if (response == null) {
            return;
        }
        try {
            response.flushBuffer();
        } catch (IOException ignored) {
            // client disconnected
        }
    }
}

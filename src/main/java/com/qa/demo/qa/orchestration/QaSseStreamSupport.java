package com.qa.demo.qa.orchestration;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 流式事件封装，供编排层复用。
 */
@Component
public class QaSseStreamSupport {

    public void sendStreamResponse(SseEmitter emitter, Map<String, Object> response, boolean shouldStreamDelta)
            throws IOException {
        String answer = String.valueOf(response.getOrDefault("answer", ""));
        Map<String, Object> meta = new HashMap<>(response);
        meta.remove("answer");
        emitter.send(SseEmitter.event().name("meta").data(meta));
        emitThinking(emitter, "result", "已生成结果，开始流式返回。");
        if (shouldStreamDelta && !answer.isBlank()) {
            for (String chunk : splitAnswer(answer, 28)) {
                emitter.send(SseEmitter.event().name("delta").data(chunk));
            }
        }
        emitter.send(SseEmitter.event().name("final").data(response));
        emitter.send(SseEmitter.event().name("done").data(Map.of("ok", true)));
        emitter.complete();
    }

    public void emitThinking(SseEmitter emitter, String phase, String message) {
        try {
            emitter.send(SseEmitter.event().name("thinking").data(Map.of(
                    "phase", phase,
                    "message", message,
                    "timestamp", OffsetDateTime.now().toString()
            )));
        } catch (IOException ignored) {
            // Ignore client disconnect during streaming.
        }
    }

    public List<String> splitAnswer(String answer, int chunkSize) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        int size = Math.max(8, chunkSize);
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < answer.length(); start += size) {
            int end = Math.min(answer.length(), start + size);
            chunks.add(answer.substring(start, end));
        }
        return chunks;
    }
}

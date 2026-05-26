package com.qa.demo.qa.orchestration;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 流式事件封装，供编排层复用。
 * <p>
 * 事件约定（Playground / 客户端按 name 分发）：
 * <ul>
 *   <li>{@code thinking} — 编排进度；payload 含 phase、message、timestamp、可选 details</li>
 *   <li>{@code meta} — 除 answer 外的完整响应字段（证据、intent、route 等）</li>
 *   <li>{@code delta} — 答案正文分片（模拟流式，默认约 28 字一块）</li>
 *   <li>{@code final} — 完整响应体（含 answer）</li>
 *   <li>{@code done} — 流结束标记 {@code {ok:true}}</li>
 * </ul>
 */
@Component
public class QaSseStreamSupport {

    /** 发送 meta → thinking(result) → 可选 delta 分片 → final → done，并 complete。 */
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
        emitThinking(emitter, phase, message, null);
    }

    /**
     * 推送编排进度。常见 phase：start、intent_wait、intent_done、retrieval、retrieval_done、
     * evidence、audit、generation、model（模型推理片段）、result。
     *
     * @param details 可选结构化字段，供前端展示可核对摘要（如 intent、queryType、evidenceCount）
     */
    public void emitThinking(SseEmitter emitter, String phase, String message, Map<String, Object> details) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("phase", phase);
            payload.put("message", message == null ? "" : message);
            payload.put("timestamp", OffsetDateTime.now().toString());
            if (details != null && !details.isEmpty()) {
                payload.put("details", details);
            }
            emitter.send(SseEmitter.event().name("thinking").data(payload));
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

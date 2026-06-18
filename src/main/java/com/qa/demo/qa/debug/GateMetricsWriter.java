package com.qa.demo.qa.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.answer.QaAnswerGateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 验证期闸门指标埋点（最薄版）。
 * <p>
 * 每次 /ask 主出口把闸门评估结果落到 {@code data/qa_logs/gate_metrics.jsonl}。
 * 字段：ts / turnId / questionType / intent / evidenceCount / canAnswer /
 * confidence / route / latencyMs。
 * <p>
 * 灰度开关 {@code qa.debug.gate-metrics.enabled}=true 时本服务生效；默认 ON。
 * 写入失败仅记日志，不影响主链路。
 */
@Service
public class GateMetricsWriter {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GateMetricsWriter.class);

    private final ObjectMapper objectMapper;
    private final Path metricsPath;

    public GateMetricsWriter(
            ObjectMapper objectMapper,
            @Value("${qa.debug.gate-metrics.path:data/qa_logs/gate_metrics.jsonl}") String path
    ) {
        this.objectMapper = objectMapper;
        this.metricsPath = Paths.get(path);
    }

    public void record(
            String turnId,
            IntentDecision intent,
            List<ContextChunk> evidence,
            QaAnswerGateService.GateDecision gate,
            String route,
            double confidence,
            boolean canAnswer,
            long latencyMs
    ) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("ts", java.time.OffsetDateTime.now().toString());
            n.put("turnId", turnId == null ? "" : turnId);
            n.put("questionType", intent == null || intent.queryType() == null ? "" : intent.queryType());
            n.put("intent", intent == null ? "" : intent.intent());
            n.put("evidenceCount", evidence == null ? 0 : evidence.size());
            n.put("canAnswer", canAnswer);
            n.put("allowGenerate", gate == null ? false : gate.allowGenerate());
            n.put("confidence", confidence);
            n.put("route", route == null ? "" : route);
            n.put("latencyMs", latencyMs);
            if (gate != null && gate.rejectReason() != null) {
                n.put("rejectReason", gate.rejectReason());
            }
            append(n);
        } catch (Exception e) {
            log.warn("[GateMetricsWriter] failed to record: {}", e.getMessage());
        }
    }

    /**
     * P0 门禁(Step1)：记录 HTTP 500 等主流程未覆盖的异常路径。
     * <p>
     * 与 {@link #record} 共用同一行 schema，新增字段 {@code error500}、{@code errorMessage}，
     * 既有字段（如 {@code route}、{@code latencyMs}）继续保留以保证 baseline CSV 可比对。
     * <p>
     * 调用方约定：{@link com.qa.demo.qa.web.QaController#handleException(Exception)}
     * 必须在最外层 try-catch 内调本方法，且**不抛二次异常**（本方法自身 swallow 一切）。
     */
    public void recordError(
            String turnId,
            String question,
            String route,
            String errorMessage,
            long latencyMs
    ) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("ts", java.time.OffsetDateTime.now().toString());
            n.put("turnId", turnId == null ? "unknown_500" : turnId);
            n.put("questionType", "");
            n.put("intent", "");
            n.put("evidenceCount", 0);
            n.put("canAnswer", false);
            n.put("allowGenerate", false);
            n.put("confidence", 0.0);
            n.put("route", route == null ? "http_500" : route);
            n.put("latencyMs", latencyMs);
            n.put("error500", true);
            if (errorMessage != null && !errorMessage.isBlank()) {
                n.put("errorMessage", errorMessage);
            }
            if (question != null && !question.isBlank()) {
                n.put("question", question.length() > 200 ? question.substring(0, 200) : question);
            }
            append(n);
        } catch (Exception e) {
            log.warn("[GateMetricsWriter] failed to recordError: {}", e.getMessage());
        }
    }

    private void append(ObjectNode n) throws IOException {
        if (metricsPath.getParent() != null) {
            Files.createDirectories(metricsPath.getParent());
        }
        String line = objectMapper.writeValueAsString(n) + "\n";
        Files.writeString(metricsPath, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}

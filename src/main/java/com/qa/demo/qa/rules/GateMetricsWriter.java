package com.qa.demo.qa.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.answer.QaAnswerGateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * 闸门指标写入器（P0-S5）。
 * <p>
 * 把每次 /ask 的闸门评估结果落到 {@code data/qa_logs/gate_metrics.jsonl}，
 * 字段：ts / turnId / questionType / intent / evidenceCount / droppedCount /
 * firedRules / droppedReasons / canAnswer / confidence / route / latencyMs。
 * <p>
 * 灰度开关 {@code qa.rule.gate-metrics.enabled}=true 时本服务生效；默认 OFF。
 * 写入失败仅记日志，不影响主链路。
 */
@Service
@ConditionalOnProperty(name = "qa.rule.gate-metrics.enabled", havingValue = "true", matchIfMissing = true)
public class GateMetricsWriter {

    private static final Logger log = LoggerFactory.getLogger(GateMetricsWriter.class);

    private final ObjectMapper objectMapper;
    private final Path metricsPath;

    public GateMetricsWriter(
            ObjectMapper objectMapper,
            @Value("${qa.rule.gate-metrics.path:data/qa_logs/gate_metrics.jsonl}") String path
    ) {
        this.objectMapper = objectMapper;
        this.metricsPath = Paths.get(path);
    }

    /**
     * 由 {@code QaAskFlowService} 在日志/落盘之后调用。
     * <p>
     * 字段尽力采集；缺失字段（如 firedRules）写空数组，不影响主流程。
     */
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
            n.put("queryTypeEnum", intent == null || intent.queryTypeEnum() == null
                    ? "" : intent.queryTypeEnum().name());
            n.put("intent", intent == null ? "" : intent.intent());
            n.put("evidenceCount", evidence == null ? 0 : evidence.size());
            n.put("maxScore", evidence == null ? 0.0
                    : evidence.stream().mapToDouble(ContextChunk::score).max().orElse(0.0));
            n.put("canAnswer", canAnswer);
            n.put("allowGenerate", gate == null ? false : gate.allowGenerate());
            n.put("confidence", confidence);
            n.put("route", route == null ? "" : route);
            n.put("latencyMs", latencyMs);
            if (gate != null && gate.rejectReason() != null) {
                n.put("rejectReason", gate.rejectReason());
            }
            n.putArray("firedRules"); // 6 个月双写期内为空数组；P1 后由 DRL audit 填
            n.putArray("droppedReasons");
            append(n);
        } catch (Exception e) {
            log.warn("[GateMetricsWriter] failed to record: {}", e.getMessage());
        }
    }

    /**
     * 兜底通道：直接写入一个 map（不依赖 IntentDecision/GateDecision，便于 QaAskFlowService 出错时也写一行）。
     */
    public void recordRaw(Map<String, Object> fields) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("ts", java.time.OffsetDateTime.now().toString());
            if (fields != null) {
                fields.forEach((k, v) -> n.putPOJO(k, v));
            }
            append(n);
        } catch (Exception e) {
            log.warn("[GateMetricsWriter] failed to recordRaw: {}", e.getMessage());
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

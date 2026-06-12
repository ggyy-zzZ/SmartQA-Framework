package com.qa.demo.qa.constraint;

import com.qa.demo.qa.core.ContextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 硬约束闸门：在 rerank 之前 / 之后对 evidence 池跑硬约束过滤。
 * <p>
 * 设计原则（D4）：
 * <ul>
 *   <li>硬约束是"合同"：满足 → 保留；不满足 → 直接丢弃，不填空</li>
 *   <li>gate 仅做"判定"，不做"补全"；避免约束失守</li>
 *   <li>约束为空时 → 闸门是 NO-OP（保持兼容）</li>
 * </ul>
 * 闸门报告（{@link GateReport}）会随 evidence 一起返回，用于可观测性：
 * 哪些 chunk 被 drop、为什么 drop、drop 数量。
 */
@Component
public class HardConstraintGate {

    private static final Logger log = LoggerFactory.getLogger(HardConstraintGate.class);

    private final RegionMatcher regionMatcher;

    public HardConstraintGate(RegionMatcher regionMatcher) {
        this.regionMatcher = regionMatcher;
    }

    /**
     * 应用硬约束闸门：返回 kept + dropped + 报告。
     */
    public GateResult apply(List<ContextChunk> chunks, ConstraintSet constraint) {
        if (chunks == null || chunks.isEmpty()) {
            return new GateResult(List.of(), List.of(), GateReport.empty());
        }
        if (constraint == null || constraint.isEmpty()) {
            return new GateResult(new ArrayList<>(chunks), List.of(), GateReport.empty());
        }
        if (!constraint.hasRegion() && !constraint.hasStatus() && !constraint.hasIndustry()) {
            return new GateResult(new ArrayList<>(chunks), List.of(), GateReport.empty());
        }
        List<ContextChunk> kept = new ArrayList<>();
        List<ContextChunk> dropped = new ArrayList<>();
        List<String> dropReasons = new ArrayList<>();
        for (ContextChunk chunk : chunks) {
            String reason = notSatisfiedReason(chunk, constraint);
            if (reason == null) {
                kept.add(chunk);
            } else {
                dropped.add(chunk);
                if (dropReasons.size() < 5) {
                    dropReasons.add(chunk.anchorId() + "::" + reason);
                }
            }
        }
        GateReport report = new GateReport(
                constraint.hasRegion() ? constraint.regionCodes().size() : 0,
                constraint.hasStatus() ? constraint.statusCodes().size() : 0,
                chunks.size(),
                kept.size(),
                dropped.size(),
                List.copyOf(dropReasons)
        );
        if (!dropped.isEmpty()) {
            log.info("[gate] kept={} dropped={} reasons={}", kept.size(), dropped.size(), dropReasons);
        }
        return new GateResult(kept, dropped, report);
    }

    /**
     * 判定不满足的原因：null 表示满足；非 null 给出"为什么被丢"。
     * 复用 {@link RegionMatcher} 的 region 命中判断；status 走 snippet 文本扫描。
     */
    private String notSatisfiedReason(ContextChunk chunk, ConstraintSet c) {
        if (c.hasRegion() && !regionMatcher.matches(chunk, c)) {
            return "region-mismatch";
        }
        if (c.hasStatus() && !statusMatches(chunk, c)) {
            return "status-mismatch";
        }
        return null;
    }

    /** 状态匹配：从 chunk.snippet 中扫"经营状态: 存续"→ 与 statusCodes 取交 */
    private static final Pattern STATUS_FIELD = Pattern.compile(
            "经营状态[:：]\\s*([\\u4e00-\\u9fa5]+)");

    private boolean statusMatches(ContextChunk chunk, ConstraintSet c) {
        if (chunk.snippet() == null || chunk.snippet().isBlank()) {
            return true; // 缺失时保守通过；闸门不在 snippet 缺失时强制拒
        }
        Matcher m = STATUS_FIELD.matcher(chunk.snippet());
        if (!m.find()) {
            return true;
        }
        String status = m.group(1);
        for (String wanted : c.statusCodes()) {
            if (status.contains(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** 闸门结果。 */
    public record GateResult(List<ContextChunk> kept,
                             List<ContextChunk> dropped,
                             GateReport report) {
    }

    /** 闸门报告：用于可观测与 answer 风险提示。 */
    public record GateReport(
            int regionCodeCount,
            int statusCodeCount,
            int inputSize,
            int keptSize,
            int droppedSize,
            List<String> dropReasons
    ) {
        public boolean hasDrop() {
            return droppedSize > 0;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("regionCodeCount", regionCodeCount);
            m.put("statusCodeCount", statusCodeCount);
            m.put("inputSize", inputSize);
            m.put("keptSize", keptSize);
            m.put("droppedSize", droppedSize);
            m.put("dropReasons", dropReasons);
            return m;
        }

        public static GateReport empty() {
            return new GateReport(0, 0, 0, 0, 0, List.of());
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "GateReport{region=%d,status=%d,input=%d,kept=%d,dropped=%d}",
                    regionCodeCount, statusCodeCount, inputSize, keptSize, droppedSize);
        }
    }
}

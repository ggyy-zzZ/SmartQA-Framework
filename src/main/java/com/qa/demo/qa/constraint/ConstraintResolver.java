package com.qa.demo.qa.constraint;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.retrieval.structured.RegionResolverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 约束解析器：在 orchestrator 调 retrieval 之前从 question + intent 提取结构化硬约束。
 * <p>
 * 解析流程（D1：规则先 + LLM 校准，默认关闭）：
 * <ol>
 *   <li>规则层：{@link RegionResolverService} 抽取 regionCodes / officeRegionCodes</li>
 *   <li>规则层：经营状态（"存续/注销/吊销"）正则抽取 → statusCodes</li>
 *   <li>透传层：intent.companyHints() → companyHints</li>
 *   <li>LLM 校准：默认关闭（qa.constraint.llm-calibration.enabled=false）</li>
 * </ol>
 */
@Service
public class ConstraintResolver {

    private static final Logger log = LoggerFactory.getLogger(ConstraintResolver.class);

    /** 经营状态关键词 → 标准枚举值 */
    private static final Map<String, String> STATUS_KEYWORDS = Map.of(
            "存续", "存续",
            "在业", "存续",
            "开业", "存续",
            "注销", "注销",
            "吊销", "吊销",
            "撤销", "吊销",
            "迁出", "迁出",
            "停业", "停业"
    );

    private static final Pattern STATUS_PATTERN = buildStatusPattern();

    private static Pattern buildStatusPattern() {
        StringBuilder sb = new StringBuilder("(?:");
        boolean first = true;
        for (String k : STATUS_KEYWORDS.keySet()) {
            if (!first) sb.append('|');
            sb.append(Pattern.quote(k));
            first = false;
        }
        sb.append(')');
        return Pattern.compile(sb.toString());
    }

    private final RegionResolverService regionResolver;

    /** 默认关闭；后续可注入 LlmConstraintCalibrator bean 后开启 */
    private volatile boolean llmCalibrationEnabled = false;

    public ConstraintResolver(RegionResolverService regionResolver) {
        this.regionResolver = regionResolver;
    }

    /**
     * 解析入口：从 question + intent 抽取 ConstraintSet。
     */
    public ConstraintSet resolve(String question, IntentDecision intent) {
        if (question == null || question.isBlank()) {
            return ConstraintSet.empty();
        }
        // 1) region：注册地 + 办公地（暂统一为 regionCodes；D3 简化）
        List<String> regionCodes = new ArrayList<>(
                regionResolver.extractRegionCodes(question).codes());
        List<String> officeRegionCodes = new ArrayList<>(regionCodes);

        // 2) status：正则扫"存续/注销/吊销"等
        List<String> statusCodes = extractStatusCodes(question);

        // 3) companyHints：透传 intent
        List<String> companyHints = intent != null && intent.hasCompanyHints()
                ? new ArrayList<>(intent.companyHints())
                : List.of();

        // 4) queryShape：基于 question 关键词推断
        ConstraintSet.QueryShape shape = inferShape(question);

        Map<String, Object> meta = new HashMap<>();
        meta.put("resolverMode", llmCalibrationEnabled ? "rule+llm" : "rule");
        meta.put("regionMatchedCount", regionCodes.size());

        ConstraintSet ruleOnly = new ConstraintSet(
                regionCodes, officeRegionCodes, List.of(), statusCodes,
                companyHints, shape, Map.copyOf(meta));

        if (!llmCalibrationEnabled) {
            return ruleOnly;
        }
        // LLM 校准路径（默认关闭）：由后续 LlmConstraintCalibrator 接管
        log.debug("[constraint] llm-calibration path is enabled but no calibrator wired; "
                + "falling back to rule-only result");
        return ruleOnly;
    }

    private List<String> extractStatusCodes(String question) {
        Matcher m = STATUS_PATTERN.matcher(question);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String std = STATUS_KEYWORDS.get(m.group());
            if (std != null && !out.contains(std)) {
                out.add(std);
            }
        }
        return out;
    }

    private ConstraintSet.QueryShape inferShape(String q) {
        // 列表型：有哪些 / 列出 / 清单 / 名单 / 所有
        if (q.matches(".*(有哪些|列出|清单|名单|所有|全部|多少家|几家).*")) {
            return ConstraintSet.QueryShape.AGGREGATE;
        }
        // 单一事实型：是什么 / 在哪 / 有没有
        if (q.matches(".*(是什么|在哪|有没有|是否|是哪个|叫什么).*")) {
            return ConstraintSet.QueryShape.SINGLE_FACT;
        }
        // 复杂/介绍/对比型
        if (q.contains("介绍") || q.contains("对比") || q.contains("区别") || q.contains("怎么样")) {
            return ConstraintSet.QueryShape.COMPLEX;
        }
        return ConstraintSet.QueryShape.UNKNOWN;
    }

    /**
     * 配置切换：开启 LLM 校准（默认关闭；通过 {@code qa.constraint.llm-calibration.enabled}
     * 注入；仅供运维热切）。
     */
    public void setLlmCalibrationEnabled(boolean enabled) {
        this.llmCalibrationEnabled = enabled;
    }

    public boolean isLlmCalibrationEnabled() {
        return llmCalibrationEnabled;
    }
}

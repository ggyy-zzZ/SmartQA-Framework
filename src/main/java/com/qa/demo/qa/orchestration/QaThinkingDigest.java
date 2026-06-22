package com.qa.demo.qa.orchestration;

import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.retrieval.QaRetrievalOrchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造供 SSE「思考过程」展示的结构化摘要（问题拆解、检索命中），避免仅展示流程阶段名。
 */
public final class QaThinkingDigest {

    private QaThinkingDigest() {
    }

    public static Map<String, Object> analysisPayload(List<Map<String, Object>> sections) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("view", "analysis");
        payload.put("sections", sections);
        return payload;
    }

    public static Map<String, Object> section(String title, List<String> lines) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("title", title);
        section.put("lines", lines == null ? List.of() : lines);
        return section;
    }

    public static Map<String, Object> sectionWithItems(String title, List<String> lines, List<Map<String, String>> items) {
        Map<String, Object> section = section(title, lines);
        if (items != null && !items.isEmpty()) {
            section.put("items", items);
        }
        return section;
    }

    public static Map<String, Object> decompose(
            String userQuestion,
            String retrievalQuestion,
            boolean followUpApplied,
            String modelContextSummary,
            QaRetrievalOrchestrator.RetrievalPlan retrievalPlan
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("用户原问：" + nullToDash(userQuestion));
        if (followUpApplied) {
            lines.add("判定为接续上一轮追问，将结合会话上下文理解指代。");
        }
        if (modelContextSummary != null && !modelContextSummary.isBlank()) {
            for (String row : modelContextSummary.split("\n")) {
                String t = row.trim();
                if (!t.isBlank()) {
                    lines.add(t);
                }
            }
        }
        if (retrievalPlan != null && retrievalPlan.appliedLearningRewrite()) {
            lines.add("检索问句已改写（别名→实名等）：" + nullToDash(retrievalQuestion));
        } else if (retrievalQuestion != null && !retrievalQuestion.equals(userQuestion)) {
            lines.add("检索问句：" + nullToDash(retrievalQuestion));
        } else {
            lines.add("检索问句与用户原问一致。");
        }
        return analysisPayload(List.of(section("1. 问题理解", lines)));
    }

    public static Map<String, Object> route(IntentDecision intent, String routingSource) {
        List<String> lines = new ArrayList<>();
        if (intent == null) {
            lines.add("尚未完成意图识别。");
            return analysisPayload(List.of(section("2. 意图与实体", lines)));
        }
        lines.add("检索通道 intent=" + nullToDash(intent.intent()) + "（来源：" + nullToDash(routingSource) + "）");
        if (intent.hasRetrievalStrategy()) {
            lines.add("检索策略 retrievalStrategy=" + intent.retrievalStrategy());
        }
        if (intent.hasPersonFocus()) {
            String personLine = "人物 personName=" + intent.personName();
            if (intent.hasPersonEmployeeId()) {
                personLine += "，锚点 employeeId=" + intent.personEmployeeId();
            }
            lines.add(personLine);
        }
        if (intent.hasCompanyHints()) {
            lines.add("公司 hints=" + String.join("、", intent.companyHints()));
        }
        if (intent.roleFocus() != null && !intent.roleFocus().isBlank() && !"any".equalsIgnoreCase(intent.roleFocus())) {
            lines.add("任职焦点 roleFocus=" + intent.roleFocus());
        }
        if (intent.reason() != null && !intent.reason().isBlank()) {
            lines.add("说明：" + intent.reason());
        }
        return analysisPayload(List.of(section("2. 意图与实体", lines)));
    }

    public static Map<String, Object> bootstrapRecall(
            List<ContextChunk> canonical,
            List<ContextChunk> activeLearning
    ) {
        List<String> lines = new ArrayList<>();
        long canon = canonical == null ? 0 : canonical.size();
        long learned = activeLearning == null ? 0 : activeLearning.size();
        lines.add("企业常识命中 " + canon + " 条，主动学习命中 " + learned + " 条。");
        List<Map<String, String>> items = new ArrayList<>();
        appendChunkItems(items, canonical, 6);
        appendChunkItems(items, activeLearning, 4);
        return analysisPayload(List.of(sectionWithItems("3. 常识与记忆（检索前注入）", lines, items)));
    }

    public static Map<String, Object> retrieval(
            String retrievalSource,
            List<ContextChunk> evidence
    ) {
        List<String> lines = new ArrayList<>();
        int count = evidence == null ? 0 : evidence.size();
        lines.add("召回通路：" + nullToDash(retrievalSource));
        lines.add("合并后证据 " + count + " 条（以下为送入模型的片段摘要）。");
        List<Map<String, String>> items = new ArrayList<>();
        appendChunkItems(items, evidence, 8);
        return analysisPayload(List.of(sectionWithItems("4. 知识检索", lines, items)));
    }

    public static Map<String, Object> gate(
            int evidenceCount,
            boolean allowGenerate,
            boolean canAnswer,
            String rejectReason
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("证据条数：" + evidenceCount);
        lines.add("允许生成：" + (allowGenerate ? "是" : "否") + "，可作答：" + (canAnswer ? "是" : "否"));
        if (rejectReason != null && !rejectReason.isBlank()) {
            lines.add("未通过原因：" + rejectReason);
        }
        return analysisPayload(List.of(section("5. 证据质检", lines)));
    }

    private static void appendChunkItems(List<Map<String, String>> items, List<ContextChunk> chunks, int max) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        int n = 0;
        for (ContextChunk c : chunks) {
            if (c == null || n >= max) {
                break;
            }
            Map<String, String> item = new LinkedHashMap<>();
            String source = c.source() == null ? "" : c.source();
            if (EnterpriseCanonicalFactsRegistry.SOURCE.equals(source)) {
                source = "企业常识";
            } else if (source.endsWith("+rerank")) {
                source = source.replace("+rerank", "（重排）");
            }
            item.put("head", "[" + source + "] " + nullToDash(c.displayLabel())
                    + (c.anchorId() != null && !c.anchorId().isBlank() ? " · 锚点 " + c.anchorId() : ""));
            String snippet = c.snippet() == null ? "" : c.snippet().trim();
            if (snippet.length() > 200) {
                snippet = snippet.substring(0, 200) + "…";
            }
            item.put("body", snippet.isBlank() ? "（无片段文本）" : snippet);
            items.add(item);
            n++;
        }
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }
}

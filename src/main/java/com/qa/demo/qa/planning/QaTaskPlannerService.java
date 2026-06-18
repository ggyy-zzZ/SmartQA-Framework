package com.qa.demo.qa.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Planner：判断是否需要多步；复杂问句拆解为检索/统计/文档/综合子任务。
 */
@Service
public class QaTaskPlannerService {

    private static final Pattern COMPARE_AND = Pattern.compile(
            "对比\\s*(.+?)\\s*和\\s*(.+?)(?:的|在|上|之间|$)"
    );
    private static final Pattern COMPARE_TWO = Pattern.compile(
            "(.+?)\\s*和\\s*(.+?)\\s*(?:哪个|谁|哪).*(?:更高|更大|更多|更少|更好)"
    );

    private final QaAssistantProperties properties;
    private final MiniMaxClient miniMaxClient;
    private final ObjectMapper objectMapper;

    public QaTaskPlannerService(
            QaAssistantProperties properties,
            MiniMaxClient miniMaxClient,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.miniMaxClient = miniMaxClient;
        this.objectMapper = objectMapper;
    }

    public AgentTaskPlan plan(
            String question,
            IntentDecision intentDecision,
            InformationNeed informationNeed
    ) {
        if (!properties.isAgentMultiStepEnabled() || question == null || question.isBlank()) {
            return AgentTaskPlan.single(question, "disabled");
        }
        if (!needsMultiStep(question, intentDecision, informationNeed)) {
            return AgentTaskPlan.single(question, "single_shot");
        }
        if (properties.isIntentLlmEnabled() && hasApiKey()) {
            AgentTaskPlan llmPlan = planWithLlm(question);
            if (llmPlan != null && llmPlan.requiresMultiStepExecution()) {
                return llmPlan;
            }
        }
        AgentTaskPlan heuristic = planHeuristicCompare(question);
        if (heuristic != null) {
            return heuristic;
        }
        return AgentTaskPlan.single(question, "fallback_single");
    }

    public boolean needsMultiStep(
            String question,
            IntentDecision intentDecision,
            InformationNeed informationNeed
    ) {
        String q = question == null ? "" : question.trim();
        if (q.isBlank()) {
            return false;
        }
        if (informationNeed != null && informationNeed.isAggregate()) {
            return containsAny(q, "对比", "比较", "分别", "和", "与", "占比", "毛利率", "相差", "差异");
        }
        if (containsAny(q, "对比", "比较", "分别查询", "分别统计", "跨", "合并计算")) {
            return true;
        }
        if (containsAny(q, "毛利率", "占比", "差额", "相差", "高于", "低于", "哪个更")) {
            return true;
        }
        if (countClauses(q) >= 2 && containsAny(q, "和", "与", "及", "以及")) {
            return true;
        }
        return intentDecision != null
                && intentDecision.hasCompanyHints()
                && intentDecision.companyHints().size() >= 2
                && containsAny(q, "对比", "比较", "哪个", "分别");
    }

    private AgentTaskPlan planHeuristicCompare(String question) {
        Matcher m = COMPARE_AND.matcher(question);
        if (m.find()) {
            String left = m.group(1).trim();
            String right = m.group(2).trim();
            String metric = extractTrailingMetric(question);
            List<AgentTaskStep> steps = new ArrayList<>();
            steps.add(retrieveStep("1", left, metric));
            steps.add(retrieveStep("2", right, metric));
            steps.add(new AgentTaskStep("3", AgentTool.SYNTHESIZE, question, List.of("1", "2")));
            return new AgentTaskPlan(true, List.copyOf(steps), "heuristic_compare");
        }
        m = COMPARE_TWO.matcher(question);
        if (m.find()) {
            String left = m.group(1).trim();
            String right = m.group(2).trim();
            List<AgentTaskStep> steps = List.of(
                    retrieveStep("1", left, question),
                    retrieveStep("2", right, question),
                    new AgentTaskStep("3", AgentTool.SYNTHESIZE, question, List.of("1", "2"))
            );
            return new AgentTaskPlan(true, steps, "heuristic_compare_rank");
        }
        return null;
    }

    private static AgentTaskStep retrieveStep(String id, String subject, String context) {
        String subQ = subject;
        if (context != null && !context.isBlank() && !subject.contains("注册") && !subject.contains("资本")) {
            if (context.contains("注册资本") || context.contains("资本")) {
                subQ = subject + "的注册资本是多少";
            } else if (context.contains("法人") || context.contains("法定代表人")) {
                subQ = subject + "的法定代表人是谁";
            } else if (context.contains("证照")) {
                subQ = subject + "有哪些证照";
            }
        }
        return new AgentTaskStep(id, AgentTool.STRUCTURED_RETRIEVE, subQ, List.of());
    }

    private static String extractTrailingMetric(String question) {
        if (question == null) {
            return "";
        }
        for (String metric : List.of("注册资本", "法人", "法定代表人", "证照", "毛利率", "收入", "成本")) {
            if (question.contains(metric)) {
                return metric;
            }
        }
        return "";
    }

    private AgentTaskPlan planWithLlm(String question) {
        try {
            String system = """
                    你是企业知识库问答的任务规划器。将复杂问题拆成 2-5 个子任务。
                    可用工具（tool）：
                    - structured_retrieve：查业务库结构化事实（公司/人员/证照/任职等）
                    - aggregate_count：统计数量（多少家、几个）
                    - document_retrieve：查企业上传文档/制度（语义检索）
                    - synthesize：综合前面步骤结果，完成对比/计算/结论（必须放最后一步）
                    规则：
                    1) 对比/计算类问题：先分别 retrieve/count，最后 synthesize
                    2) 子问题 question 必须自洽、可独立检索，不要写「同上」
                    3) 不要编造库中不存在的实体名；用用户问句中的主体
                    仅输出单行 JSON：
                    {"multiStep":true,"steps":[{"id":"1","tool":"structured_retrieve","question":"...","dependsOn":[]},...]}
                    若单步即可：{"multiStep":false,"steps":[{"id":"1","tool":"structured_retrieve","question":"原问","dependsOn":[]}]}
                    """;
            String raw = miniMaxClient.completeChat(system, "用户问题:\n" + question);
            return parsePlan(raw, question);
        } catch (Exception ignored) {
            return null;
        }
    }

    private AgentTaskPlan parsePlan(String raw, String fallbackQuestion) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String json = extractJsonObject(raw);
            JsonNode root = objectMapper.readTree(json);
            boolean multi = root.path("multiStep").asBoolean(false);
            List<AgentTaskStep> steps = new ArrayList<>();
            for (JsonNode node : root.path("steps")) {
                String id = node.path("id").asText(String.valueOf(steps.size() + 1));
                AgentTool tool = AgentTool.parse(node.path("tool").asText(""));
                String subQ = node.path("question").asText(fallbackQuestion);
                List<String> deps = new ArrayList<>();
                node.path("dependsOn").forEach(d -> {
                    if (d.isTextual() && !d.asText().isBlank()) {
                        deps.add(d.asText());
                    }
                });
                steps.add(new AgentTaskStep(id, tool, subQ, deps));
            }
            if (steps.isEmpty()) {
                return null;
            }
            if (multi && steps.stream().noneMatch(s -> s.tool() == AgentTool.SYNTHESIZE)) {
                List<String> depIds = steps.stream().map(AgentTaskStep::id).toList();
                steps.add(new AgentTaskStep(
                        String.valueOf(steps.size() + 1),
                        AgentTool.SYNTHESIZE,
                        fallbackQuestion,
                        depIds
                ));
            }
            return new AgentTaskPlan(multi, List.copyOf(steps), "llm_planner");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw.trim();
    }

    private boolean hasApiKey() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    private static boolean containsAny(String text, String... markers) {
        if (text == null) {
            return false;
        }
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static int countClauses(String q) {
        String[] parts = q.split("[，,；;]");
        int count = 0;
        for (String part : parts) {
            if (part != null && part.trim().length() > 4) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    public List<String> planDigestLines(AgentTaskPlan plan) {
        if (plan == null || plan.steps() == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("规划来源: " + plan.plannerSource() + "，多步: " + plan.multiStep());
        for (AgentTaskStep step : plan.steps()) {
            lines.add(step.id() + " " + step.tool().wireName() + " → " + step.question());
        }
        return List.copyOf(lines);
    }
}

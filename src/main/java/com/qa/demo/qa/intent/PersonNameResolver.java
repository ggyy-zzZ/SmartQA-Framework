package com.qa.demo.qa.intent;

import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.domain.PersonAliasIdentityParser;
import com.qa.demo.qa.domain.PersonNameParser;
import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.retrieval.EmployeeBaseKnowledgeService;
import com.qa.demo.qa.retrieval.GraphContextService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将问句/LLM 槽位中的敬称、别名解析为规范姓名，并绑定员工唯一标识。
 * <p>
 * 解析策略（按优先级）：
 * <ol>
 *   <li>主动学习记录中已知的别名映射</li>
 *   <li>员工基础表中精确匹配（花名/别名 → 规范姓名）</li>
 *   <li>规则匹配：敬称剥离 + 图谱前缀匹配</li>
 *   <li>LLM 辅助解析（当规则匹配结果不唯一或为空时）</li>
 * </ol>
 */
@Component
public class PersonNameResolver {

    private static final String LLM_SYSTEM_PROMPT = """
            你是一个人物身份解析助手。
            用户会给出一个问句和问句中提到的人物指代（如"戴先生"、"张总"、"李经理"等）。
            你的任务是从公司员工数据库中找到这个指代对应的真实姓名。

            已知员工库包含以下姓氏的员工：戴、张、李、王、刘、陈、杨、黄、赵、周、吴、徐、孙、马、朱、胡、郭、何

            规则：
            1. "X先生"、"X女士"、"X小姐" 通常指姓X的人
            2. "X总" 通常指姓X的管理层
            3. "X经理"、"X总监" 等职位称呼通常指姓X的人
            4. 如果问句上下文明确指向某个具体的人，返回该人的规范姓名
            5. 如果无法确定，返回空字符串

            返回格式：只返回姓名， 不要多余解释。
            """;
    private static final String SLOT_GUARD_SYSTEM_PROMPT = """
            你是槽位校验器。判断“候选词”在“用户问句”中是否是人物指代。
            只返回 JSON：{"isPerson":true|false,"reason":"短语"}
            规则：
            1) 条件短语、规则短语、连接词（例如“只要不”“如果”“其他”“不是”等）应判 false。
            2) 表示人物称谓/姓名/花名/别名可判 true。
            3) 不确定时返回 false。
            不要输出额外文本。
            """;

    private final EmployeeBaseKnowledgeService employeeBaseKnowledge;
    private final ActiveLearningService activeLearningService;
    private final GraphContextService graphContextService;
    private final MiniMaxClient miniMaxClient;
    private final QaAssistantProperties properties;

    public PersonNameResolver(
            EmployeeBaseKnowledgeService employeeBaseKnowledge,
            ActiveLearningService activeLearningService,
            GraphContextService graphContextService,
            MiniMaxClient miniMaxClient,
            QaAssistantProperties properties
    ) {
        this.employeeBaseKnowledge = employeeBaseKnowledge;
        this.activeLearningService = activeLearningService;
        this.graphContextService = graphContextService;
        this.miniMaxClient = miniMaxClient;
        this.properties = properties;
    }

    public String resolve(String rawPersonName, List<ContextChunk> learned) {
        return resolve(rawPersonName, learned, "any").canonicalName();
    }

    public PersonNameResolution resolve(String rawPersonName, List<ContextChunk> learned, String roleFocus) {
        return resolve(rawPersonName, learned, roleFocus, null);
    }

    public PersonNameResolution resolve(
            String rawPersonName,
            List<ContextChunk> learned,
            String roleFocus,
            String question
    ) {
        String seed = rawPersonName == null ? "" : rawPersonName.trim();
        if (question != null && !question.isBlank()) {
            String fromIdentity = PersonAliasIdentityParser.resolveCanonicalPerson(question, employeeBaseKnowledge);
            if (!fromIdentity.isBlank()) {
                seed = fromIdentity;
            }
        }
        if (seed.isBlank()) {
            return PersonNameResolution.resolved("");
        }
        String trimmed = seed;

        // 1. 主动学习别名映射
        String fromLearning = activeLearningService.resolvePersonAlias(trimmed, learned);
        if (!fromLearning.equals(trimmed)) {
            return withEmployeeId(fromLearning);
        }

        // 2. 员工表精确匹配
        String fromEmployee = employeeBaseKnowledge.resolveCanonicalName(trimmed);
        if (!fromEmployee.equals(trimmed) && !PersonNameParser.hasHonorificSuffix(fromEmployee)) {
            return withEmployeeId(fromEmployee);
        }

        // 3. 规则匹配：敬称剥离 + 前缀匹配
        String searchCore = PersonNameParser.stripHonorific(trimmed);
        if (searchCore.isBlank()) {
            searchCore = trimmed;
        }

        String role = roleFocus == null || roleFocus.isBlank() ? "any" : roleFocus;
        List<String> graphNames = graphContextService.listPersonNamesByHintAndRole(searchCore, role, 12);

        if (graphNames.size() == 1) {
            // 唯一匹配
            return withEmployeeId(graphNames.get(0));
        }

        if (graphNames.isEmpty()) {
            // 4. 规则无匹配，尝试 LLM 辅助
            if (question != null && !question.isBlank()) {
                String llmResolved = resolveByLlm(trimmed, question);
                if (!llmResolved.isBlank()) {
                    return withEmployeeId(llmResolved);
                }
            }
            return withEmployeeId(trimmed);
        }

        // 多个候选人，尝试 LLM 消歧
        if (question != null && !question.isBlank()) {
            String llmResolved = resolveByLlm(trimmed, question);
            if (!llmResolved.isBlank() && graphNames.contains(llmResolved)) {
                return withEmployeeId(llmResolved);
            }
        }

        // LLM 无法消歧，返回多候选人
        return PersonNameResolution.ambiguous(trimmed, graphNames);
    }

    /**
     * 使用 LLM 辅助解析人物指代。
     */
    private String resolveByLlm(String personHint, String question) {
        if (personHint == null || personHint.isBlank()) {
            return "";
        }
        String hint = personHint.trim();
        if (IntentSlots.sanitizePersonName(hint).isBlank() && !PersonNameParser.hasHonorificSuffix(hint)) {
            return "";
        }
        try {
            String userMessage = String.format("问句：%s\n人物指代：%s\n请找出这个指代对应的员工姓名。", question, hint);
            String response = miniMaxClient.completeChat(LLM_SYSTEM_PROMPT, userMessage);
            if (response != null && !response.isBlank()) {
                String cleaned = response.trim();
                cleaned = cleaned.replaceAll("^\"|\"$", "");
                cleaned = cleaned.replaceAll("^「|」$", "");
                cleaned = cleaned.trim();
                String sanitized = IntentSlots.sanitizePersonName(cleaned);
                if (!sanitized.isBlank() && !isRejectedResolution(sanitized)) {
                    return sanitized;
                }
                if (PersonNameParser.hasHonorificSuffix(cleaned) && cleaned.length() <= 12) {
                    return cleaned;
                }
            }
        } catch (Exception ignored) {
            // LLM 调用失败，忽略
        }
        return "";
    }

    private PersonNameResolution withEmployeeId(String canonicalName) {
        if (canonicalName == null || canonicalName.isBlank()) {
            return PersonNameResolution.resolved("");
        }
        Integer id = employeeBaseKnowledge.resolveToEmployeeId(canonicalName);
        return PersonNameResolution.resolved(canonicalName, id);
    }

    public boolean needsClarification(PersonNameResolution resolution) {
        return resolution != null && resolution.needsClarification();
    }

    public boolean stillHonorific(String personName) {
        return PersonNameParser.hasHonorificSuffix(personName);
    }

    private static boolean isRejectedResolution(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String n = name.trim();
        return n.contains("无法") || n.contains("不确定") || n.contains("未知") || n.contains("查不到");
    }

    /**
     * 对候选 personName 做通用守卫：员工索引命中优先通过；其余由 LLM 判定是否为人物指代。
     */
    public String guardPersonSlotCandidate(String candidate, String question) {
        String normalized = IntentSlots.sanitizePersonName(candidate);
        if (normalized.isBlank()) {
            return "";
        }
        Integer employeeId = employeeBaseKnowledge.resolveToEmployeeId(normalized);
        if (employeeId != null) {
            EmployeeBaseKnowledgeService.EmployeeRecord record = employeeBaseKnowledge.getEmployeeById(employeeId);
            if (record != null && record.name() != null && !record.name().isBlank()) {
                return record.name().trim();
            }
            return normalized;
        }
        if (!properties.isIntentLlmEnabled() || question == null || question.isBlank()) {
            return normalized;
        }
        if (llmRejectsPersonCandidate(normalized, question)) {
            return "";
        }
        return normalized;
    }

    private boolean llmRejectsPersonCandidate(String candidate, String question) {
        try {
            String userMessage = "问句：" + question + "\n候选词：" + candidate;
            String raw = miniMaxClient.completeChat(SLOT_GUARD_SYSTEM_PROMPT, userMessage);
            if (raw == null || raw.isBlank()) {
                return false;
            }
            String lower = raw.toLowerCase();
            if (lower.contains("\"isperson\":true") || lower.contains("\"is_person\":true")) {
                return false;
            }
            if (lower.contains("\"isperson\":false") || lower.contains("\"is_person\":false")) {
                return true;
            }
            // 兜底：若模型未按 JSON 返回，按关键词保守拦截
            return lower.contains("false") || lower.contains("否") || lower.contains("不是人物");
        } catch (Exception ignored) {
            return false;
        }
    }
}
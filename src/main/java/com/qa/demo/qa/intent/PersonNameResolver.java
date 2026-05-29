package com.qa.demo.qa.intent;

import com.qa.demo.qa.answer.MiniMaxClient;
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

    private final EmployeeBaseKnowledgeService employeeBaseKnowledge;
    private final ActiveLearningService activeLearningService;
    private final GraphContextService graphContextService;
    private final MiniMaxClient miniMaxClient;

    public PersonNameResolver(
            EmployeeBaseKnowledgeService employeeBaseKnowledge,
            ActiveLearningService activeLearningService,
            GraphContextService graphContextService,
            MiniMaxClient miniMaxClient
    ) {
        this.employeeBaseKnowledge = employeeBaseKnowledge;
        this.activeLearningService = activeLearningService;
        this.graphContextService = graphContextService;
        this.miniMaxClient = miniMaxClient;
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
                if (!sanitized.isBlank()) {
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
}
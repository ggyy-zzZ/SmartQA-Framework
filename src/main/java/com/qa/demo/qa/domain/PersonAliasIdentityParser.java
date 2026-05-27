package com.qa.demo.qa.domain;

import com.qa.demo.qa.retrieval.EmployeeBaseKnowledgeService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从问句中识别「别名/花名 ↔ 姓名」同一性表述（如「李晓峰是老布」），并借助员工索引归一到规范姓名。
 */
public final class PersonAliasIdentityParser {

    private static final Pattern IDENTITY_STATEMENT = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4})\\s*(?:是|就是|即为|叫做|叫)\\s*([\\u4e00-\\u9fa5]{2,4})"
    );

    private PersonAliasIdentityParser() {
    }

    /**
     * 若问句声明 A 与 B 为同一人且均能在员工库命中为同一 employee_id，返回规范姓名。
     */
    public static String resolveCanonicalPerson(String question, EmployeeBaseKnowledgeService employeeBase) {
        if (question == null || question.isBlank() || employeeBase == null || employeeBase.size() == 0) {
            return "";
        }
        Matcher matcher = IDENTITY_STATEMENT.matcher(question.trim());
        while (matcher.find()) {
            String left = matcher.group(1);
            String right = matcher.group(2);
            Integer idLeft = employeeBase.resolveToEmployeeId(left);
            Integer idRight = employeeBase.resolveToEmployeeId(right);
            if (idLeft != null && idRight != null && idLeft.equals(idRight)) {
                EmployeeBaseKnowledgeService.EmployeeRecord record = employeeBase.getEmployeeById(idLeft);
                if (record != null && record.name() != null && !record.name().isBlank()) {
                    return record.name().trim();
                }
            }
            if (idLeft != null) {
                EmployeeBaseKnowledgeService.EmployeeRecord record = employeeBase.getEmployeeById(idLeft);
                if (record != null && record.name() != null && !record.name().isBlank()) {
                    return record.name().trim();
                }
            }
            if (idRight != null) {
                EmployeeBaseKnowledgeService.EmployeeRecord record = employeeBase.getEmployeeById(idRight);
                if (record != null && record.name() != null && !record.name().isBlank()) {
                    return record.name().trim();
                }
            }
        }
        return "";
    }

    /**
     * 提取身份表述中的双方指称，供检索阶段追加员工身份证据。
     */
    public static List<String> extractMentionedPersonTokens(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = IDENTITY_STATEMENT.matcher(question.trim());
        while (matcher.find()) {
            tokens.add(matcher.group(1));
            tokens.add(matcher.group(2));
        }
        return new ArrayList<>(tokens);
    }
}

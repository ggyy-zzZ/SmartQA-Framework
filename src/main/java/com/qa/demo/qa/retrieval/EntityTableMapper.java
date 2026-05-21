package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 实体类型检测与表映射服务。
 * <p>
 * 根据问题内容检测实体类型（如"员工"），并返回对应的 supplemental tables。
 * 实体类型 → 表名的映射关系配置在 qa.assistant.entity-table-mapping 中。
 */
@Service
public class EntityTableMapper {

    private final QaAssistantProperties properties;

    public EntityTableMapper(QaAssistantProperties properties) {
        this.properties = properties;
    }

    /**
     * 检测问题中涉及的实体类型。
     */
    public List<String> detectEntityTypes(String question) {
        List<String> detected = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return detected;
        }
        String lower = question.toLowerCase(Locale.ROOT);

        // 员工相关关键词
        if (containsEmployeeKeywords(lower)) {
            detected.add("employee");
        }

        return detected;
    }

    /**
     * 根据实体类型获取需要追加查询的表名列表。
     */
    public List<String> getSupplementalTables(String entityType) {
        return properties.getTablesForEntityType(entityType);
    }

    /**
     * 根据问题获取所有需要追加查询的表名。
     */
    public List<String> getSupplementalTablesForQuestion(String question) {
        List<String> tables = new ArrayList<>();
        for (String entityType : detectEntityTypes(question)) {
            tables.addAll(getSupplementalTables(entityType));
        }
        return tables;
    }

    private boolean containsEmployeeKeywords(String lower) {
        return lower.contains("员工")
                || lower.contains("工号")
                || lower.contains("员工号")
                || lower.contains("employee")
                || lower.contains("手机号")
                || lower.contains("电话号码")
                || lower.contains("邮箱")
                || lower.contains("email")
                || lower.contains("入职")
                || lower.contains("离职")
                || lower.contains("在职")
                || lower.contains("职位")
                || lower.contains("岗位")
                || lower.contains("部门")
                || lower.contains("编制")
                || lower.contains("转正")
                || lower.contains("试用期")
                || lower.contains("员工状态")
                || lower.contains("正式员工")
                || lower.contains("合同")
                || lower.contains("姓名")
                || lower.contains("name");
    }
}
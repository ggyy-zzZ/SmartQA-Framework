package com.qa.demo.qa.cdc.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.cdc.CdcEntityIdResolver;
import com.qa.demo.qa.cdc.CdcTdcompFields;
import com.qa.demo.qa.domain.SqlRoleColumnCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按 sql-role-columns 配置从 CDC 行提取人员角色绑定（图谱与向量文档共用）。
 */
@Component
public class CdcPersonRoleBindingExtractor {

    private final SqlRoleColumnCatalog roleColumnCatalog;

    public CdcPersonRoleBindingExtractor(SqlRoleColumnCatalog roleColumnCatalog) {
        this.roleColumnCatalog = roleColumnCatalog;
    }

    public List<CdcPersonRoleBinding> fromCompanyRow(JsonNode row) {
        List<CdcPersonRoleBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, String> entry : roleColumnCatalog.columnLabels().entrySet()) {
            String column = entry.getKey();
            if (!CdcRowFieldReader.hasColumn(row, column)) {
                continue;
            }
            String personId = CdcRowFieldReader.textAt(row, column);
            String roleLabel = entry.getValue();
            bindings.add(new CdcPersonRoleBinding(
                    column,
                    roleLabel,
                    personId,
                    personKeyFromEmployeeId(personId)
            ));
        }
        return bindings;
    }

    public String personKeyFromEmployeeRow(JsonNode row) {
        String personId = CdcEntityIdResolver.resolveEntityId("employee", row);
        if (personId != null && !personId.isBlank()) {
            return personId;
        }
        String name = CdcTdcompFields.employeeName(row);
        return "NAME::" + (name != null ? name : "UNKNOWN");
    }

    public static String personKeyFromEmployeeId(String personId) {
        if (personId == null || personId.isBlank()) {
            return null;
        }
        return personId.trim();
    }

    public boolean matchesRoleLabel(CdcPersonRoleBinding binding, List<String> roleLabels) {
        if (binding == null || roleLabels == null || roleLabels.isEmpty()) {
            return false;
        }
        for (String pattern : roleLabels) {
            if ("*".equals(pattern)) {
                return binding.personId() != null && !binding.personId().isBlank();
            }
            if (pattern != null && pattern.equals(binding.roleLabel())) {
                return binding.personId() != null && !binding.personId().isBlank();
            }
        }
        return false;
    }

    public String formatBinding(CdcPersonRoleBinding binding, String format) {
        String tpl = format == null || format.isBlank() ? "{role}:{personId}" : format;
        return tpl
                .replace("{role}", nullToEmpty(binding.roleLabel()))
                .replace("{personId}", nullToEmpty(binding.personId()))
                .replace("{column}", nullToEmpty(binding.sourceColumn()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

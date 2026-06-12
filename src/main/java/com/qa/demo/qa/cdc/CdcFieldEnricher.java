package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.cdc.graph.CdcPersonRoleBinding;
import com.qa.demo.qa.domain.EnterpriseEnumLabelService;
import org.springframework.stereotype.Component;

/**
 * CDC 图谱/向量写入：枚举码 → 中文标签，人员 id → 姓名/花名。
 */
@Component
public class CdcFieldEnricher {

    private final EnterpriseEnumLabelService enumLabels;
    private final CdcPersonDisplayResolver personDisplayResolver;

    public CdcFieldEnricher(
            EnterpriseEnumLabelService enumLabels,
            CdcPersonDisplayResolver personDisplayResolver
    ) {
        this.enumLabels = enumLabels;
        this.personDisplayResolver = personDisplayResolver;
    }

    public String operatingStatusLabel(JsonNode row) {
        return enumLabels.label("operatingStatus", CdcTdcompFields.operatingStatus(row));
    }

    public String mainTypeLabel(JsonNode row) {
        return enumLabels.label("mainType", CdcTdcompFields.entityType(row));
    }

    public String mainClassTypeLabel(JsonNode row) {
        return enumLabels.label("mainClassType", CdcTdcompFields.entityCategory(row));
    }

    public String branchStatusLabel(JsonNode row) {
        return enumLabels.label("operatingStatus", CdcTdcompFields.operatingStatus(row));
    }

    /**
     * 通用入口：按 dictKey 在 enterprise-enums.json 查找 rawCode 的中文标签。
     * dictKey 为 null/空时直接返回 rawCode 不做翻译。
     */
    public String enumLabel(String dictKey, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return "";
        }
        if (dictKey == null || dictKey.isBlank()) {
            return rawCode;
        }
        return enumLabels.label(dictKey, rawCode);
    }

    public String formatRoleBinding(CdcPersonRoleBinding binding, String format) {
        String tpl = format == null || format.isBlank() ? "{role}:{personDisplay}" : format;
        CdcPersonDisplay person = personDisplayResolver.fromPersonId(binding.personId());
        return tpl
                .replace("{role}", nullToEmpty(binding.roleLabel()))
                .replace("{personDisplay}", person.displayName())
                .replace("{personId}", nullToEmpty(binding.personId()))
                .replace("{column}", nullToEmpty(binding.sourceColumn()));
    }

    public void appendCompanyDocument(StringBuilder sb, JsonNode row) {
        appendLine(sb, "公司ID", CdcTdcompFields.companyId(row));
        appendLine(sb, "公司名", CdcTdcompFields.firstText(row, "company_name", "name"));
        appendLine(sb, "简称", CdcTdcompFields.firstText(row, "company_short_name", "short_name"));
        appendLine(sb, "统一社会信用代码", CdcTdcompFields.creditCode(row));
        appendLine(sb, "经营状态", operatingStatusLabel(row));
        appendLine(sb, "主体类型", mainTypeLabel(row));
        appendLine(sb, "主体分类", mainClassTypeLabel(row));
        appendLine(sb, "成立日期", CdcTdcompFields.establishedDate(row));
        appendLine(sb, "注册地区", CdcTdcompFields.registeredArea(row));
        appendLine(sb, "母公司ID", CdcTdcompFields.parentCompanyId(row));
        appendLine(sb, "注册地址", CdcTdcompFields.registeredAddress(row));
        appendLine(sb, "办公地址", CdcTdcompFields.officeAddress(row));
        appendLine(sb, "经营范围", CdcTdcompFields.businessScope(row));
    }

    public CdcPersonDisplay personFromEmployeeRow(JsonNode row) {
        return personDisplayResolver.fromEmployeeRow(row);
    }

    public void appendEmployeeDocument(StringBuilder sb, JsonNode row) {
        CdcPersonDisplay person = personDisplayResolver.fromEmployeeRow(row);
        appendLine(sb, "员工ID", person.personId());
        appendLine(sb, "姓名", person.name());
        appendLine(sb, "花名", person.anotherName());
        appendLine(sb, "展示名", person.displayName());
        appendLine(sb, "所属公司ID", CdcTdcompFields.employeeCompanyId(row));
        appendLine(sb, "邮箱", CdcTdcompFields.firstText(row, "email"));
        appendLine(sb, "手机", CdcTdcompFields.firstText(row, "mobilephone", "telphone"));
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        sb.append(label).append(": ").append(value != null ? value : "").append('\n');
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

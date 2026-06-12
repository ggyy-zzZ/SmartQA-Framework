package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * tdcomp 物理列名 → 规范字段读取（与 scripts/enterprise_pipeline/schema_field_maps.py 对齐）。
 *
 * <p>本类只服务于 CDC 的 {@code company / employee} 节点增量修正；子表
 * （bank_account / certificate_management / seal_management 等）入图由
 * Python 离线灌库完成，不在 CDC 实时路径中。</p>
 */
public final class CdcTdcompFields {

    private CdcTdcompFields() {
    }

    public static String firstText(JsonNode row, String... fields) {
        if (!CdcEntityIdResolver.hasRowData(row)) {
            return null;
        }
        for (String field : fields) {
            JsonNode node = row.path(field);
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            String text = node.asText(null);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    static String creditCode(JsonNode row) {
        return firstText(row, "social_credit_code", "credit_code", "unified_social_credit_code");
    }

    static String operatingStatus(JsonNode row) {
        return firstText(row, "operating_status", "status", "company_status");
    }

    static String entityType(JsonNode row) {
        return firstText(row, "main_type", "entity_type", "company_type");
    }

    static String entityCategory(JsonNode row) {
        return firstText(row, "main_class_type", "entity_category", "company_category");
    }

    static String registeredArea(JsonNode row) {
        return firstText(row, "reg_province_region", "registered_area", "register_region");
    }

    static String registeredAddress(JsonNode row) {
        return firstText(row, "registered_address", "register_address", "address");
    }

    static String officeAddress(JsonNode row) {
        return firstText(row, "actual_office_address", "office_address", "work_address");
    }

    static String businessScope(JsonNode row) {
        return firstText(row, "business_scope_info", "business_scope", "scope");
    }

    static String establishedDate(JsonNode row) {
        return firstText(row, "establishment_date", "established_date", "setup_date");
    }

    static String parentCompanyId(JsonNode row) {
        return firstText(row, "head_office_company_id", "parent_company_id", "parent_id");
    }

    public static String employeeName(JsonNode row) {
        return firstText(row, "name", "employee_name", "emp_name");
    }

    public static String employeeAnotherName(JsonNode row) {
        return firstText(row, "another_name", "nickname", "alias_name");
    }

    static String companyId(JsonNode row) {
        return CdcEntityIdResolver.resolveEntityId("company", row);
    }

    static String employeeCompanyId(JsonNode row) {
        return firstText(row, "sign_company_id", "company_id");
    }

    static String qdrantEntityType(String table) {
        return switch (table) {
            case "employee" -> "Person";
            case "branch" -> "Branch";
            case "partner" -> "Partner";
            default -> "Company";
        };
    }

    static String buildDocument(String table, JsonNode row) {
        return switch (table) {
            case "employee" -> buildEmployeeDocument(row);
            case "branch" -> buildBranchDocument(row);
            case "partner" -> buildPartnerDocument(row);
            default -> buildCompanyDocument(row);
        };
    }

    private static String buildCompanyDocument(JsonNode row) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "公司ID", companyId(row));
        appendLine(sb, "公司名", firstText(row, "company_name", "name"));
        appendLine(sb, "简称", firstText(row, "company_short_name", "short_name"));
        appendLine(sb, "统一社会信用代码", creditCode(row));
        appendLine(sb, "经营状态", operatingStatus(row));
        appendLine(sb, "主体类型", entityType(row));
        appendLine(sb, "主体分类", entityCategory(row));
        appendLine(sb, "成立日期", establishedDate(row));
        appendLine(sb, "注册地区", registeredArea(row));
        appendLine(sb, "母公司ID", parentCompanyId(row));
        appendLine(sb, "注册地址", registeredAddress(row));
        appendLine(sb, "办公地址", officeAddress(row));
        appendLine(sb, "经营范围", businessScope(row));
        return sb.toString();
    }

    private static String buildEmployeeDocument(JsonNode row) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "员工ID", CdcEntityIdResolver.resolveEntityId("employee", row));
        appendLine(sb, "姓名", employeeName(row));
        appendLine(sb, "所属公司ID", employeeCompanyId(row));
        appendLine(sb, "邮箱", firstText(row, "email"));
        appendLine(sb, "手机", firstText(row, "mobilephone", "telphone"));
        return sb.toString();
    }

    private static String buildBranchDocument(JsonNode row) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "分支ID", CdcEntityIdResolver.resolveEntityId("branch", row));
        appendLine(sb, "名称", firstText(row, "branch_name", "name"));
        appendLine(sb, "所属公司ID", firstText(row, "company_id", "sign_company_id"));
        return sb.toString();
    }

    private static String buildPartnerDocument(JsonNode row) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "合作方ID", CdcEntityIdResolver.resolveEntityId("partner", row));
        appendLine(sb, "名称", firstText(row, "partner_name", "name"));
        appendLine(sb, "所属公司ID", firstText(row, "company_id", "sign_company_id"));
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        sb.append(label).append(": ").append(value != null ? value : "").append('\n');
    }
}

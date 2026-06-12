"""
tdcomp / 企业库字段别名与枚举标签（与 build_knowledge_from_mysql 共用）。
列名候选按优先级排列；导出时使用 canonical（规范）字段名写入 JSONL。
"""

from __future__ import annotations

from typing import Any

# canonical -> 物理列候选
COMPANY_SCALAR_ALIASES: dict[str, list[str]] = {
    "company_code": ["company_code"],
    "credit_code": ["social_credit_code", "credit_code", "unified_social_credit_code"],
    "status": ["operating_status", "status", "company_status"],
    "entity_type": ["main_type", "entity_type", "company_type"],
    "entity_category": ["main_class_type", "entity_category", "company_category"],
    "currency": ["currency_type", "currency"],
    "registered_area": ["reg_province_region", "registered_area", "register_region"],
    "registered_address": ["registered_address", "register_address", "address"],
    "office_address": ["actual_office_address", "office_address", "work_address"],
    "business_scope": ["business_scope_info", "business_scope", "scope"],
    "established_date": ["establishment_date", "established_date", "setup_date"],
    "parent_company_id": ["head_office_company_id", "parent_company_id", "parent_id"],
    "tax_registered": ["has_tax_registration"],
    "bank_account_opened": ["has_bank_account"],
    "social_security_opened": ["has_social_sec_account"],
    "housing_fund_opened": ["has_provident_account"],
}

# 人员角色：规范列名 -> 展示角色名（列须在 company 表存在才会导出）
PERSON_ROLE_COLUMNS: dict[str, str] = {
    "legal_rep_id": "法定代表人",
    "company_supervisor_id": "公司监事",
    "assigned_accountant_id": "会计",
    "assigned_cashier_id": "出纳",
    "accounting_supervisor_id": "会计主管",
    "financial_manager_id": "财务负责人",
    "tax_handler_id": "办税人",
    "ticket_purchaser_id": "购票人",
    "manager_id": "经理",
    "company_contact_id": "企业联络人",
    "chairman_exec_director_id": "董事长/执行董事",
    "ssc_payroll_manager_id": "SSC薪资负责人",
    "limited_partner_id": "有限合伙人代表",
}

LEGAL_REP_COLUMN_CANDIDATES = [
    "legal_rep_id",
    "legal_representative_id",
    "legal_person_id",
    "representative_id",
]

# 不作为「人员 ID」自动扫描的 company 表列
COMPANY_ID_COLUMN_EXCLUDE = {
    "id",
    "tenant",
    "head_office_company_id",
    "parent_company_id",
    "parent_id",
    "esign_seal_id",
    "entity_creating_request_id",
    "creator_id",
    "modifier_id",
    "charter_partnership_file_ids",
    "application_materials_file_ids",
    "domicile_proof_file_ids",
    "other_attachments_file_ids",
    "other_attachments_ids",
}

OPERATING_STATUS_LABELS: dict[str, str] = {
    "0": "设立中",
    "1": "存续",
    "2": "迁出",
    "3": "注销",
    "4": "吊销",
    "5": "停业",
}

MAIN_TYPE_LABELS: dict[str, str] = {
    "1": "有限责任公司",
    "2": "股份有限公司",
    "3": "合伙企业",
    "4": "分支机构",
    "5": "个体工商户",
}

MAIN_CLASS_TYPE_LABELS: dict[str, str] = {
    "1": "运营主体",
    "2": "控股主体",
    "3": "参股企业",
    "4": "参股企业",
}

# 来自业务库 company_product_line 统计与清洗样本对照（未知码回退为 模块{n}/线{m}）
MODULE_KIND_LABELS: dict[int, str] = {
    1: "中台",
    2: "外包",
    3: "猎聘",
    4: "其他",
    5: "创新",
}

PRODUCT_LINE_LABELS: dict[int, str] = {
    1: "总部",
    2: "分部",
    3: "战投",
    4: "核心",
    5: "勋厚",
    6: "勋厚",
    7: "灵活用工",
    8: "外包",
    9: "BPO",
    10: "交付",
    11: "国际",
    12: "区域",
    13: "孵化",
    14: "合资",
    15: "新业务",
    16: "战略",
    17: "平台",
    18: "生态",
    19: "海外",
}

SHAREHOLDER_TYPE_LABELS: dict[str, str] = {
    "1": "公司",
    "2": "自然人",
}

MEMBER_TYPE_LABELS: dict[str, str] = {
    "1": "董事",
    "2": "监事",
    "3": "高管",
    "4": "独立董事",
    "5": "职工董事",
}

# 证照类型（certificate_management.certificate_type），与业务枚举 CertificateType 一致
CERTIFICATE_TYPE_LABELS: dict[str, str] = {
    "1": "营业执照-独立法人",
    "2": "营业执照-独立法人分公司",
    "3": "营业执照-合伙企业",
    "4": "人力资源服务许可证",
    "5": "劳务派遣经营许可证",
    "6": "增值电信业务经营许可证",
    "7": "高新技术企业证书",
    "8": "广播电视节目制作许可证",
    "9": "ICP备案",
    "10": "开户许可证",
    "11": "机构信用代码证",
    "12": "外商投资企业批准证书",
    "13": "ISO27001",
    "14": "网络安全等级保护-3级",
    "15": "ISO14001",
    "16": "ISO45001",
    "17": "ISO9001",
    "18": "cmmi",
    "19": "数据出境评估备案",
    "20": "人力资源服务备案",
    "21": "软件产品证书和测试报告",
    "22": "人力资源服务机构等级证书",
    "23": "对外劳务合作经营资格证书",
    "business_license_independent_legal_person": "营业执照-独立法人",
    "business_license_independent_legal_person_branch": "营业执照-独立法人分公司",
    "business_license_partnership": "营业执照-合伙企业",
    "human_resources_service_license": "人力资源服务许可证",
    "labor_dispatch_operation_license": "劳务派遣经营许可证",
    "value_added_telecom_business_license": "增值电信业务经营许可证",
    "high_tech_enterprise_certificate": "高新技术企业证书",
    "radio_tv_program_production_license": "广播电视节目制作许可证",
    "icp_filing": "ICP备案",
    "bank_account_opening_permit": "开户许可证",
    "institution_credit_code_certificate": "机构信用代码证",
    "foreign_invested_enterprise_approval_certificate": "外商投资企业批准证书",
    "iso27001": "ISO27001",
    "cybersecurity_mlps_level_3": "网络安全等级保护-3级",
    "iso14001": "ISO14001",
    "iso45001": "ISO45001",
    "iso9001": "ISO9001",
    "cmmi": "cmmi",
    "data_export_assessment_filing": "数据出境评估备案",
    "human_resources_service_filing": "人力资源服务备案",
    "software_product_certificate_and_test_report": "软件产品证书和测试报告",
    "human_resources_service_org_grade_certificate": "人力资源服务机构等级证书",
    "foreign_labor_cooperation_operation_qualification_certificate": "对外劳务合作经营资格证书",
}

# 印章类型（seal_management.seal_type），与业务枚举 SealType 一致
SEAL_TYPE_LABELS: dict[str, str] = {
    "1": "法定名称章",
    "2": "财务专用章",
    "3": "发票专用章",
    "4": "合同专用章",
    "5": "人力资源专用章",
    "6": "法人手签章",
    "7": "法人方章",
    "8": "党章",
    "9": "社保专用章",
    "10": "钢印章",
    "11": "合同专用章2",
    "legal_name": "法定名称章",
    "finance": "财务专用章",
    "invoice": "发票专用章",
    "contract": "合同专用章",
    "hr": "人力资源专用章",
    "legal_signature": "法人手签章",
    "legal_square": "法人方章",
    "party": "党章",
    "social_security": "社保专用章",
    "steel": "钢印章",
    "contract_2": "合同专用章2",
}

SEAL_CATEGORY_LABELS: dict[str, str] = {
    "0": "电子章",
    "1": "鲜章",
    "oa_0": "电子章",
    "oa_1": "鲜章",
    "code_0": "电子章",
    "code_1": "鲜章",
    "physical": "鲜章",
    "electronic": "电子章",
}

SEAL_STATUS_LABELS: dict[str, str] = {
    "0": "生效中",
    "1": "已失效",
    "enabled": "生效中",
    "disabled": "已失效",
}

BANK_ACCOUNT_TYPE_LABELS: dict[str, str] = {
    "0": "基本户",
    "1": "一般户",
    "code_0": "基本户",
    "code_1": "一般户",
    "basic": "基本户",
    "general": "一般户",
}

BANK_ACCOUNT_STATUS_LABELS: dict[str, str] = {
    "0": "有效",
    "1": "无效",
    "2": "冻结",
}

CERT_STATUS_LABELS: dict[str, str] = {
    "0": "有效",
    "1": "无效",
    "2": "冻结",
    "有效": "有效",
    "无效": "无效",
    "冻结": "冻结",
}


def format_id_label(code: str, label: str) -> str:
    """将码值与中文描述合并为 id(描述) 展示串。"""
    code = (code or "").strip()
    label = (label or "").strip()
    if not code:
        return label
    if not label or code == label:
        return code
    return f"{code}({label})"


def coded_field(value: Any, mapping: dict[str, str], fallback_prefix: str = "") -> dict[str, str]:
    """解析枚举/码表字段，返回 code、label、display。"""
    raw = "" if value is None else str(value).strip()
    if not raw:
        return {"code": "", "label": "", "display": ""}
    label = mapping.get(raw, "")
    if not label and raw.isdigit():
        label = mapping.get(str(int(raw)), "")
    if not label:
        label = f"{fallback_prefix}{raw}" if fallback_prefix else raw
    return {"code": raw, "label": label, "display": format_id_label(raw, label)}


def person_profile(person_id: str, name: str, another_name: str = "") -> dict[str, str]:
    """人员展示：姓名 + 花名，person_id(展示名)。"""
    name = (name or "").strip()
    another = (another_name or "").strip()
    display = name
    if another and another != name:
        display = f"{name}（{another}）"
    pid = (person_id or "").strip()
    return {
        "name": name,
        "another_name": another,
        "display": format_id_label(pid, display) if pid else display,
    }

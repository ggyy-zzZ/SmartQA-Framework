"""
tdcomp / 企业库字段别名与枚举标签（与 build_knowledge_from_mysql 共用）。
列名候选按优先级排列；导出时使用 canonical（规范）字段名写入 JSONL。
"""

from __future__ import annotations

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

CERTIFICATE_TYPE_LABELS: dict[str, str] = {
    "1": "营业执照",
    "2": "组织机构代码证",
    "3": "税务登记证",
    "4": "开户许可证",
    "5": "社保登记证",
    "6": "公积金登记证",
    "7": "食品经营许可证",
    "8": "卫生许可证",
    "9": "ICP许可证",
    "10": "人力资源服务许可证",
}

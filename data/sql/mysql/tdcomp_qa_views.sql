-- tdcomp 问答视图层（实验路径 /qa/docvec 专用，只读）
-- 枚举标签与 scripts/enterprise_pipeline/schema_field_maps.py 对齐

USE tdcomp;

DROP VIEW IF EXISTS v_person_company_roles;
DROP VIEW IF EXISTS v_company_profile;

CREATE VIEW v_company_profile AS
SELECT
    c.id AS company_id,
    c.company_name AS 公司名称,
    c.company_short_name AS 公司简称,
    c.social_credit_code AS 统一社会信用代码,
    CASE c.operating_status
        WHEN 0 THEN '设立中'
        WHEN 1 THEN '存续'
        WHEN 2 THEN '迁出'
        WHEN 3 THEN '注销'
        WHEN 4 THEN '吊销'
        WHEN 5 THEN '停业'
        ELSE CONCAT('未知状态(', c.operating_status, ')')
    END AS 经营状态,
    CASE c.main_type
        WHEN 1 THEN '有限责任公司'
        WHEN 2 THEN '股份有限公司'
        WHEN 3 THEN '合伙企业'
        WHEN 4 THEN '分支机构'
        WHEN 5 THEN '个体工商户'
        ELSE CONCAT('未知类型(', c.main_type, ')')
    END AS 主体类型,
    CASE c.main_class_type
        WHEN 1 THEN '运营主体'
        WHEN 2 THEN '控股主体'
        WHEN 3 THEN '参股企业'
        WHEN 4 THEN '参股企业'
        ELSE CONCAT('未知分类(', c.main_class_type, ')')
    END AS 主体分类,
    CASE
        WHEN c.establishment_date IS NULL OR c.establishment_date = '' THEN NULL
        ELSE CONCAT(
            SUBSTRING(c.establishment_date, 1, 4), '-',
            SUBSTRING(c.establishment_date, 5, 2), '-',
            SUBSTRING(c.establishment_date, 7, 2)
        )
    END AS 成立日期,
    NULLIF(c.reg_province_region, '') AS 注册地区,
    NULLIF(c.registered_address, '') AS 注册地址,
    NULLIF(c.actual_office_address, '') AS 实际办公地址,
    NULLIF(lr.name, '') AS 法定代表人,
    NULLIF(p.company_name, '') AS 母公司名称
FROM company c
LEFT JOIN employee lr ON c.legal_rep_id = lr.id
LEFT JOIN company p ON c.head_office_company_id = p.id AND p.deleteflag = 0
WHERE c.deleteflag = 0;

CREATE VIEW v_person_company_roles AS
SELECT e.name AS 人员姓名, '法定代表人' AS 角色, c.company_name AS 公司名称,
       CASE c.operating_status WHEN 0 THEN '设立中' WHEN 1 THEN '存续' WHEN 2 THEN '迁出'
           WHEN 3 THEN '注销' WHEN 4 THEN '吊销' WHEN 5 THEN '停业'
           ELSE CONCAT('未知状态(', c.operating_status, ')') END AS 经营状态,
       c.id AS company_id
FROM company c
INNER JOIN employee e ON c.legal_rep_id = e.id
WHERE c.deleteflag = 0 AND c.legal_rep_id > 0 AND e.name <> ''
UNION ALL
SELECT e.name, '经理', c.company_name,
       CASE c.operating_status WHEN 0 THEN '设立中' WHEN 1 THEN '存续' WHEN 2 THEN '迁出'
           WHEN 3 THEN '注销' WHEN 4 THEN '吊销' WHEN 5 THEN '停业'
           ELSE CONCAT('未知状态(', c.operating_status, ')') END,
       c.id
FROM company c
INNER JOIN employee e ON c.manager_id = e.id
WHERE c.deleteflag = 0 AND c.manager_id > 0 AND e.name <> ''
UNION ALL
SELECT e.name, '公司监事', c.company_name,
       CASE c.operating_status WHEN 0 THEN '设立中' WHEN 1 THEN '存续' WHEN 2 THEN '迁出'
           WHEN 3 THEN '注销' WHEN 4 THEN '吊销' WHEN 5 THEN '停业'
           ELSE CONCAT('未知状态(', c.operating_status, ')') END,
       c.id
FROM company c
INNER JOIN employee e ON c.company_supervisor_id = e.id
WHERE c.deleteflag = 0 AND c.company_supervisor_id > 0 AND e.name <> ''
UNION ALL
SELECT e.name, '董事长/执行董事', c.company_name,
       CASE c.operating_status WHEN 0 THEN '设立中' WHEN 1 THEN '存续' WHEN 2 THEN '迁出'
           WHEN 3 THEN '注销' WHEN 4 THEN '吊销' WHEN 5 THEN '停业'
           ELSE CONCAT('未知状态(', c.operating_status, ')') END,
       c.id
FROM company c
INNER JOIN employee e ON c.chairman_exec_director_id = e.id
WHERE c.deleteflag = 0 AND c.chairman_exec_director_id > 0 AND e.name <> ''
UNION ALL
SELECT e.name, '财务负责人', c.company_name,
       CASE c.operating_status WHEN 0 THEN '设立中' WHEN 1 THEN '存续' WHEN 2 THEN '迁出'
           WHEN 3 THEN '注销' WHEN 4 THEN '吊销' WHEN 5 THEN '停业'
           ELSE CONCAT('未知状态(', c.operating_status, ')') END,
       c.id
FROM company c
INNER JOIN employee e ON c.financial_manager_id = e.id
WHERE c.deleteflag = 0 AND c.financial_manager_id > 0 AND e.name <> '';

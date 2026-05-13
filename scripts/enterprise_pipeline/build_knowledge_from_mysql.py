#!/usr/bin/env python3
"""
Build normalized enterprise knowledge documents from MySQL structured data.

Outputs:
1) enterprise_mysql_clean.jsonl  -> for Neo4j / Qdrant initialization
2) enterprise_mysql_compiled.txt -> for document fallback retrieval
3) enterprise_mysql_stats.json   -> build statistics
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

import pymysql
from pymysql.cursors import DictCursor


def row_value(row: dict[str, Any], key: str, default: Any = None) -> Any:
    if key in row:
        return row[key]
    key_lower = key.lower()
    for k, v in row.items():
        if str(k).lower() == key_lower:
            return v
    return default


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build normalized enterprise knowledge from MySQL")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--username", default="root")
    parser.add_argument("--password", default="root")
    parser.add_argument("--schema", default="tdcomp")
    parser.add_argument("--output-dir", default="data/knowledge")
    parser.add_argument("--limit", type=int, default=0, help="Limit companies for debugging")
    return parser.parse_args()


def open_conn(args: argparse.Namespace):
    return pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.username,
        password=args.password,
        database=args.schema,
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=True,
    )


def table_exists(conn, schema: str, table: str) -> bool:
    sql = """
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema=%s AND table_name=%s
    LIMIT 1
    """
    with conn.cursor() as cur:
        cur.execute(sql, (schema, table))
        return cur.fetchone() is not None


def table_columns(conn, schema: str, table: str) -> set[str]:
    sql = """
    SELECT column_name
    FROM information_schema.columns
    WHERE table_schema=%s AND table_name=%s
    """
    with conn.cursor() as cur:
        cur.execute(sql, (schema, table))
        return {str(row_value(row, "column_name", "")).strip() for row in cur.fetchall() if row_value(row, "column_name")}


def choose_company_table(conn, schema: str) -> str | None:
    preferred = [
        "company",
        "company_info",
        "company_base_info",
        "company_base",
    ]
    for name in preferred:
        if table_exists(conn, schema, name):
            return name

    sql = """
    SELECT table_name
    FROM information_schema.columns
    WHERE table_schema=%s
      AND column_name IN ('company_name', 'name')
    GROUP BY table_name
    ORDER BY table_name
    """
    with conn.cursor() as cur:
        cur.execute(sql, (schema,))
        rows = cur.fetchall()
    return str(row_value(rows[0], "table_name")) if rows else None


def choose_company_id_column(cols: set[str]) -> str | None:
    for name in ("id", "company_id", "corp_id", "enterprise_id"):
        if name in cols:
            return name
    return None


def choose_company_name_column(cols: set[str]) -> str | None:
    for name in ("company_name", "name", "corp_name", "enterprise_name"):
        if name in cols:
            return name
    return None


def choose_first(cols: set[str], candidates: list[str]) -> str | None:
    for name in candidates:
        if name in cols:
            return name
    return None


def query_companies(conn, schema: str, limit: int) -> list[dict[str, Any]]:
    table = choose_company_table(conn, schema)
    if table is None:
        return []
    cols = table_columns(conn, schema, table)
    id_col = choose_company_id_column(cols)
    name_col = choose_company_name_column(cols)
    if id_col is None or name_col is None:
        return []

    status_col = choose_first(cols, ["status", "company_status"])
    short_name_col = choose_first(cols, ["short_name", "company_short_name"])
    entity_type_col = choose_first(cols, ["entity_type", "company_type"])
    entity_category_col = choose_first(cols, ["entity_category", "company_category"])
    reg_addr_col = choose_first(cols, ["registered_address", "register_address", "address"])
    office_addr_col = choose_first(cols, ["office_address", "work_address"])
    business_scope_col = choose_first(cols, ["business_scope", "scope"])
    parent_col = choose_first(cols, ["parent_company", "parent_company_name"])

    selected = [
        f"`{id_col}` AS company_id",
        f"`{name_col}` AS company_name",
    ]
    if short_name_col:
        selected.append(f"`{short_name_col}` AS company_short_name")
    if status_col:
        selected.append(f"`{status_col}` AS status")
    if entity_type_col:
        selected.append(f"`{entity_type_col}` AS entity_type")
    if entity_category_col:
        selected.append(f"`{entity_category_col}` AS entity_category")
    if reg_addr_col:
        selected.append(f"`{reg_addr_col}` AS registered_address")
    if office_addr_col:
        selected.append(f"`{office_addr_col}` AS office_address")
    if business_scope_col:
        selected.append(f"`{business_scope_col}` AS business_scope")
    if parent_col:
        selected.append(f"`{parent_col}` AS parent_company")

    delete_flag_sql = ""
    if "deleteflag" in cols:
        delete_flag_sql = "WHERE deleteflag = 0"

    limit_sql = f"LIMIT {int(limit)}" if limit > 0 else ""
    sql = f"""
    SELECT {", ".join(selected)}
    FROM `{schema}`.`{table}`
    {delete_flag_sql}
    {limit_sql}
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def query_bank_accounts(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "bank_account"):
        return []
    sql = """
    SELECT
      id,
      account_name,
      account_type,
      company_id,
      bank_name,
      account_number,
      bank_subject_code,
      bank_contact_id,
      submit_key_holder_id,
      auth_key_holder_id,
      supervisor_id,
      account_set_code,
      status,
      remark
    FROM `bank_account`
    WHERE deleteflag = 0
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def parse_id_list(value: Any) -> list[int]:
    if value is None:
        return []
    text = str(value).strip().strip(",")
    if not text:
        return []
    result: list[int] = []
    for part in text.split(","):
        item = part.strip()
        if not item:
            continue
        try:
            pid = int(item)
        except Exception:
            continue
        if pid > 0:
            result.append(pid)
    return result


def query_directors_supervisors(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "company_directors_supervisors"):
        return []
    sql = """
    SELECT
      id,
      company_id,
      member_type,
      member_id,
      mobile
    FROM `company_directors_supervisors`
    WHERE deleteflag = 0
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def query_certificate_management(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "certificate_management"):
        return []
    sql = """
    SELECT
      id,
      company_id,
      certificate_type,
      issue_date,
      valid_from,
      valid_to,
      annual_inspection_date,
      supervisors,
      certification_keepers,
      executors,
      status
    FROM `certificate_management`
    WHERE deleteflag = 0
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def query_seal_management(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "seal_management"):
        return []
    sql = """
    SELECT
      id,
      company_id,
      seal_type,
      seal_category,
      custody_department,
      supplier,
      supplier_seal_code,
      status
    FROM `seal_management`
    WHERE deleteflag = 0
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def query_seal_person_detail(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "seal_person_detail"):
        return []
    sql = """
    SELECT
      id,
      seal_id,
      role_type,
      user_id,
      account_name
    FROM `seal_person_detail`
    WHERE deleteflag = 0
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def query_employee_map(conn, schema: str) -> dict[int, str]:
    if not table_exists(conn, schema, "employee"):
        return {}
    cols = table_columns(conn, schema, "employee")
    id_col = "id" if "id" in cols else None
    if id_col is None:
        return {}
    name_col = choose_first(cols, ["name", "employee_name", "real_name", "nickname"])
    if name_col is None:
        return {}

    selected = [f"`{id_col}` AS id", f"`{name_col}` AS name"]
    where_parts = []
    if "deleteflag" in cols:
        where_parts.append("deleteflag = 0")
    where_sql = f"WHERE {' AND '.join(where_parts)}" if where_parts else ""
    sql = f"SELECT {', '.join(selected)} FROM `employee` {where_sql}"
    with conn.cursor() as cur:
        cur.execute(sql)
        rows = cur.fetchall()
    result: dict[int, str] = {}
    for row in rows:
        try:
            key = int(row_value(row, "id", 0))
        except Exception:
            continue
        name = str(row_value(row, "name", "") or "").strip()
        if name:
            result[key] = name
    return result


def normalize_status(value: Any) -> str:
    if value is None:
        return ""
    text = str(value).strip()
    mapping = {
        "0": "有效",
        "1": "无效",
        "2": "冻结",
    }
    return mapping.get(text, text)


def role_person(role: str, emp_id: Any, emp_map: dict[int, str]) -> dict[str, Any] | None:
    try:
        pid = int(emp_id or 0)
    except Exception:
        pid = 0
    if pid <= 0:
        return None
    return {
        "role": role,
        "name": emp_map.get(pid, f"员工#{pid}"),
        "person_id": str(pid),
    }


def member_type_name(value: Any) -> str:
    text = str(value or "").strip()
    mapping = {
        "1": "董事",
        "2": "监事",
        "3": "高管",
    }
    return mapping.get(text, f"董监高({text or '未知'})")


def build_company_rows(
    companies: list[dict[str, Any]],
    bank_accounts: list[dict[str, Any]],
    directors_supervisors: list[dict[str, Any]],
    certificate_management: list[dict[str, Any]],
    seal_management: list[dict[str, Any]],
    seal_person_detail: list[dict[str, Any]],
    employee_map: dict[int, str],
) -> list[dict[str, Any]]:
    by_company_accounts: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in bank_accounts:
        by_company_accounts[str(row.get("company_id") or "")].append(row)

    by_company_directors: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in directors_supervisors:
        by_company_directors[str(row.get("company_id") or "")].append(row)

    by_company_certs: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in certificate_management:
        by_company_certs[str(row.get("company_id") or "")].append(row)

    by_company_seals: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in seal_management:
        by_company_seals[str(row.get("company_id") or "")].append(row)

    seal_persons_by_seal_id: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in seal_person_detail:
        seal_id = str(row.get("seal_id") or "")
        if seal_id:
            seal_persons_by_seal_id[seal_id].append(row)

    rows: list[dict[str, Any]] = []
    for c in companies:
        company_id = str(c.get("company_id") or "").strip()
        if not company_id:
            continue
        company_name = str(c.get("company_name") or "").strip() or f"公司#{company_id}"
        account_rows = by_company_accounts.get(company_id, [])
        directors_rows = by_company_directors.get(company_id, [])
        cert_rows = by_company_certs.get(company_id, [])
        seal_rows = by_company_seals.get(company_id, [])

        key_people: list[dict[str, Any]] = []
        directors_supervisors_payload: list[dict[str, Any]] = []
        certificates_payload: list[dict[str, Any]] = []
        seals_payload: list[dict[str, Any]] = []
        bank_payloads: list[dict[str, Any]] = []
        person_dedup: set[tuple[str, str]] = set()

        for role, col in (
            ("法定代表人", "legal_rep_id"),
            ("公司监事", "company_supervisor_id"),
            ("会计", "assigned_accountant_id"),
            ("出纳", "assigned_cashier_id"),
            ("会计主管", "accounting_supervisor_id"),
            ("财务负责人", "financial_manager_id"),
            ("办税人", "tax_handler_id"),
            ("购票人", "ticket_purchaser_id"),
            ("经理", "manager_id"),
            ("企业联络人", "company_contact_id"),
            ("董事长/执行董事", "chairman_exec_director_id"),
            ("SSC薪资负责人", "ssc_payroll_manager_id"),
            ("有限合伙人代表", "limited_partner_id"),
        ):
            person = role_person(role, c.get(col), employee_map)
            if not person:
                continue
            key = (person["role"], person["person_id"])
            if key in person_dedup:
                continue
            person_dedup.add(key)
            key_people.append(person)

        for pid in parse_id_list(c.get("assigned_it_ids")):
            person = role_person("IT负责人", pid, employee_map)
            if not person:
                continue
            key = (person["role"], person["person_id"])
            if key in person_dedup:
                continue
            person_dedup.add(key)
            key_people.append(person)

        for ba in account_rows:
            for role, col in (
                ("银行联系人", "bank_contact_id"),
                ("提交盾持有人", "submit_key_holder_id"),
                ("授权盾持有人", "auth_key_holder_id"),
                ("监管人", "supervisor_id"),
            ):
                person = role_person(role, ba.get(col), employee_map)
                if not person:
                    continue
                key = (person["role"], person["person_id"])
                if key in person_dedup:
                    continue
                person_dedup.add(key)
                key_people.append(person)

            bank_payloads.append(
                {
                    "account_id": str(ba.get("id") or ""),
                    "account_name": str(ba.get("account_name") or ""),
                    "account_type": "基本户" if str(ba.get("account_type") or "0") == "0" else "一般户",
                    "bank_name": str(ba.get("bank_name") or ""),
                    "account_number": str(ba.get("account_number") or ""),
                    "bank_subject_code": str(ba.get("bank_subject_code") or ""),
                    "account_set_code": str(ba.get("account_set_code") or ""),
                    "status": normalize_status(ba.get("status")),
                    "remark": str(ba.get("remark") or ""),
                }
            )

        for ds in directors_rows:
            role_name = member_type_name(ds.get("member_type"))
            pid = str(ds.get("member_id") or "").strip()
            member_name = ""
            if pid:
                try:
                    member_name = employee_map.get(int(pid), f"员工#{pid}")
                except Exception:
                    member_name = f"员工#{pid}"
            directors_supervisors_payload.append(
                {
                    "member_type": role_name,
                    "member_id": pid,
                    "member_name": member_name,
                    "mobile": str(ds.get("mobile") or ""),
                }
            )
            if pid:
                key = (f"董监高-{role_name}", pid)
                if key not in person_dedup:
                    person_dedup.add(key)
                    key_people.append(
                        {
                            "role": f"董监高-{role_name}",
                            "name": member_name or f"员工#{pid}",
                            "person_id": pid,
                        }
                    )

        for cert in cert_rows:
            supervisors = parse_id_list(cert.get("supervisors"))
            keepers = parse_id_list(cert.get("certification_keepers"))
            executors = parse_id_list(cert.get("executors"))
            certificates_payload.append(
                {
                    "cert_type": str(cert.get("certificate_type") or ""),
                    "status": normalize_status(cert.get("status")),
                    "code": "",
                    "issue_date": str(cert.get("issue_date") or ""),
                    "expire_date": str(cert.get("valid_to") or ""),
                    "valid_from": str(cert.get("valid_from") or ""),
                    "annual_inspection_date": str(cert.get("annual_inspection_date") or ""),
                    "supervisors": [employee_map.get(pid, f"员工#{pid}") for pid in supervisors],
                    "certification_keepers": [employee_map.get(pid, f"员工#{pid}") for pid in keepers],
                    "executors": [employee_map.get(pid, f"员工#{pid}") for pid in executors],
                }
            )
            for role, ids in (("证照监管人", supervisors), ("证照保管人", keepers), ("证照执行人", executors)):
                for pid in ids:
                    person = role_person(role, pid, employee_map)
                    if not person:
                        continue
                    key = (person["role"], person["person_id"])
                    if key in person_dedup:
                        continue
                    person_dedup.add(key)
                    key_people.append(person)

        for seal in seal_rows:
            seal_id = str(seal.get("id") or "")
            persons = seal_persons_by_seal_id.get(seal_id, [])
            person_items = []
            for p in persons:
                uid = str(p.get("user_id") or "").strip()
                account_name = str(p.get("account_name") or "").strip()
                if uid:
                    try:
                        account_name = employee_map.get(int(uid), account_name or f"员工#{uid}")
                    except Exception:
                        account_name = account_name or f"员工#{uid}"
                role_type = str(p.get("role_type") or "")
                person_items.append(
                    {
                        "role_type": role_type,
                        "user_id": uid,
                        "account_name": account_name,
                    }
                )
                if uid:
                    key = (f"印章角色-{role_type or '未知'}", uid)
                    if key not in person_dedup:
                        person_dedup.add(key)
                        key_people.append(
                            {
                                "role": f"印章角色-{role_type or '未知'}",
                                "name": account_name,
                                "person_id": uid,
                            }
                        )
            seals_payload.append(
                {
                    "seal_id": seal_id,
                    "seal_type": str(seal.get("seal_type") or ""),
                    "seal_category": str(seal.get("seal_category") or ""),
                    "custody_department": str(seal.get("custody_department") or ""),
                    "supplier": str(seal.get("supplier") or ""),
                    "supplier_seal_code": str(seal.get("supplier_seal_code") or ""),
                    "status": normalize_status(seal.get("status")),
                    "persons": person_items,
                }
            )

        row = {
            "company_id": company_id,
            "company_name": company_name,
            "company_short_name": str(c.get("company_short_name") or ""),
            "status": normalize_status(c.get("status")),
            "entity_type": str(c.get("entity_type") or ""),
            "entity_category": str(c.get("entity_category") or ""),
            "registered_address": str(c.get("registered_address") or ""),
            "office_address": str(c.get("office_address") or ""),
            "business_scope": str(c.get("business_scope") or ""),
            "parent_company": str(c.get("parent_company") or ""),
            "product_lines": [],
            "shareholders": [],
            "certificates": certificates_payload,
            "directors_supervisors": directors_supervisors_payload,
            "seals": seals_payload,
            "key_people": key_people,
            "bank_accounts": bank_payloads,
            "source_file": "mysql.tdcomp",
        }
        rows.append(row)
    return rows


def build_compiled_text(rows: list[dict[str, Any]]) -> str:
    blocks: list[str] = []
    for row in rows:
        people = "；".join(f"{p.get('role')}:{p.get('name')}(ID:{p.get('person_id')})" for p in row.get("key_people", []))
        banks = "；".join(
            f"{x.get('account_type')}-{x.get('bank_name')}[{x.get('account_name')}] 状态:{x.get('status')}"
            for x in row.get("bank_accounts", [])
        )
        directors = "；".join(
            f"{x.get('member_type')}:{x.get('member_name')}(ID:{x.get('member_id')})"
            for x in row.get("directors_supervisors", [])
        )
        certs = "；".join(
            f"类型:{x.get('cert_type')} 状态:{x.get('status')} 保管人:{','.join(x.get('certification_keepers', []))}"
            for x in row.get("certificates", [])
        )
        seals = "；".join(
            f"印章类型:{x.get('seal_type')} 分类:{x.get('seal_category')} 保管部门:{x.get('custody_department')}"
            for x in row.get("seals", [])
        )
        summary = f"来源=mysql.tdcomp; 银行账户数={len(row.get('bank_accounts', []))}; 关键人数量={len(row.get('key_people', []))}"
        block = (
            f"{{companyId={row.get('company_id')}, companyName={row.get('company_name')}, summary={summary}}}\n"
            f"公司名称：{row.get('company_name')}\n"
            f"经营状态：{row.get('status')}\n"
            f"主体类型：{row.get('entity_type')}\n"
            f"主体分类：{row.get('entity_category')}\n"
            f"注册地址：{row.get('registered_address')}\n"
            f"实际办公地址：{row.get('office_address')}\n"
            f"经营范围：{row.get('business_scope')}\n"
            f"关键人员：{people}\n"
            f"董监高：{directors}\n"
            f"证照信息：{certs}\n"
            f"印章信息：{seals}\n"
            f"银行账户：{banks}\n"
        )
        blocks.append(block)
    return "\n".join(blocks)


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    with open_conn(args) as conn:
        companies = query_companies(conn, args.schema, args.limit)
        bank_accounts = query_bank_accounts(conn, args.schema)
        directors_supervisors = query_directors_supervisors(conn, args.schema)
        certificate_management = query_certificate_management(conn, args.schema)
        seal_management = query_seal_management(conn, args.schema)
        seal_person_detail = query_seal_person_detail(conn, args.schema)
        employee_map = query_employee_map(conn, args.schema)

    rows = build_company_rows(
        companies,
        bank_accounts,
        directors_supervisors,
        certificate_management,
        seal_management,
        seal_person_detail,
        employee_map,
    )
    if not rows:
        raise SystemExit("No normalized rows built from MySQL. Please check company table / permissions.")

    jsonl_path = output_dir / "enterprise_mysql_clean.jsonl"
    txt_path = output_dir / "enterprise_mysql_compiled.txt"
    stats_path = output_dir / "enterprise_mysql_stats.json"

    write_jsonl(jsonl_path, rows)
    txt_path.write_text(build_compiled_text(rows), encoding="utf-8")

    stats = {
        "source": f"mysql://{args.host}:{args.port}/{args.schema}",
        "companies": len(rows),
        "bank_accounts": sum(len(x.get("bank_accounts", [])) for x in rows),
        "key_people": sum(len(x.get("key_people", [])) for x in rows),
        "directors_supervisors": sum(len(x.get("directors_supervisors", [])) for x in rows),
        "certificates": sum(len(x.get("certificates", [])) for x in rows),
        "seals": sum(len(x.get("seals", [])) for x in rows),
        "output_jsonl": str(jsonl_path),
        "output_text": str(txt_path),
    }
    stats_path.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")

    print("Build finished.")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

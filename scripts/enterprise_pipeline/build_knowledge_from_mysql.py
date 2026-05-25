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

from schema_field_maps import (
    CERTIFICATE_TYPE_LABELS,
    COMPANY_ID_COLUMN_EXCLUDE,
    COMPANY_SCALAR_ALIASES,
    LEGAL_REP_COLUMN_CANDIDATES,
    MAIN_CLASS_TYPE_LABELS,
    MAIN_TYPE_LABELS,
    MEMBER_TYPE_LABELS,
    MODULE_KIND_LABELS,
    OPERATING_STATUS_LABELS,
    PERSON_ROLE_COLUMNS,
    PRODUCT_LINE_LABELS,
    SHAREHOLDER_TYPE_LABELS,
)


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


def enum_label(value: Any, mapping: dict[str, str], fallback_prefix: str = "") -> str:
    if value is None:
        return ""
    text = str(value).strip()
    if not text:
        return ""
    if text in mapping:
        return mapping[text]
    if text.isdigit() and text in mapping:
        return mapping[text]
    try:
        key = str(int(text))
        if key in mapping:
            return mapping[key]
    except Exception:
        pass
    return f"{fallback_prefix}{text}" if fallback_prefix else text


def yes_no_flag(value: Any) -> str:
    if value is None:
        return ""
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "y"}:
        return "是"
    if text in {"0", "false", "no", "n"}:
        return "否"
    return text


def append_select_alias(selected: list[str], cols: set[str], canonical: str, candidates: list[str]) -> None:
    physical = choose_first(cols, candidates)
    if physical and canonical not in {s.split(" AS ")[-1].strip() for s in selected if " AS " in s}:
        selected.append(f"`{physical}` AS {canonical}")


def query_companies(conn, schema: str, limit: int) -> list[dict[str, Any]]:
    table = choose_company_table(conn, schema)
    if table is None:
        return []
    cols = table_columns(conn, schema, table)
    id_col = choose_company_id_column(cols)
    name_col = choose_company_name_column(cols)
    if id_col is None or name_col is None:
        return []

    short_name_col = choose_first(cols, ["short_name", "company_short_name"])

    selected = [
        f"`{id_col}` AS company_id",
        f"`{name_col}` AS company_name",
    ]
    if short_name_col:
        selected.append(f"`{short_name_col}` AS company_short_name")

    for canonical, candidates in COMPANY_SCALAR_ALIASES.items():
        append_select_alias(selected, cols, canonical, candidates)

    parent_name_col = choose_first(cols, ["parent_company", "parent_company_name"])
    if parent_name_col:
        selected.append(f"`{parent_name_col}` AS parent_company")

    legal_rep_col = choose_first(cols, LEGAL_REP_COLUMN_CANDIDATES)
    if legal_rep_col:
        selected.append(f"`{legal_rep_col}` AS legal_rep_id")

    selected_canonical = {s.split(" AS ")[-1].strip() for s in selected if " AS " in s}
    for col, _role in PERSON_ROLE_COLUMNS.items():
        if col in cols and col not in selected_canonical:
            selected.append(f"`{col}` AS {col}")
    if "assigned_it_ids" in cols and "assigned_it_ids" not in selected_canonical:
        selected.append("`assigned_it_ids` AS assigned_it_ids")

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


def query_company_product_lines(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "company_product_line"):
        return []
    sql = """
    SELECT company_id, module_kind, product_line
    FROM `company_product_line`
    WHERE deleteflag = 0
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def query_company_shareholders(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "company_shareholder_info"):
        return []
    sql = """
    SELECT
      company_id,
      shareholder_type,
      shareholder_id,
      paid_in_capital,
      subscribed_capital
    FROM `company_shareholder_info`
    WHERE deleteflag = 0
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


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


def query_employee_map(conn, schema: str) -> tuple[dict[int, str], dict[str, int]]:
    """返回 employee_id->主姓名、别名(含英文名/曾用名)->employee_id。"""
    if not table_exists(conn, schema, "employee"):
        return {}, {}
    cols = table_columns(conn, schema, "employee")
    id_col = "id" if "id" in cols else None
    if id_col is None:
        return {}, {}
    name_col = choose_first(cols, ["name", "employee_name", "real_name", "nickname"])
    if name_col is None:
        return {}, {}

    alias_cols = [
        c
        for c in ["another_name", "english_name", "name_spell", "another_name_spell"]
        if c in cols
    ]
    selected = [f"`{id_col}` AS id", f"`{name_col}` AS name"] + [f"`{c}` AS {c}" for c in alias_cols]
    where_parts = []
    if "deleteflag" in cols:
        where_parts.append("deleteflag = 0")
    where_sql = f"WHERE {' AND '.join(where_parts)}" if where_parts else ""
    sql = f"SELECT {', '.join(selected)} FROM `employee` {where_sql}"
    with conn.cursor() as cur:
        cur.execute(sql)
        rows = cur.fetchall()
    result: dict[int, str] = {}
    alias_to_id: dict[str, int] = {}
    for row in rows:
        try:
            key = int(row_value(row, "id", 0))
        except Exception:
            continue
        name = str(row_value(row, "name", "") or "").strip()
        if name:
            result[key] = name
            alias_to_id[name] = key
        for col in alias_cols:
            alias = str(row_value(row, col, "") or "").strip()
            if alias and len(alias) >= 2:
                alias_to_id[alias] = key
    return result, alias_to_id


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
    return enum_label(text, MEMBER_TYPE_LABELS, "董监高-")


def product_line_label(module_kind: Any, product_line: Any) -> tuple[str, str]:
    try:
        mk = int(module_kind)
    except Exception:
        mk = 0
    try:
        pl = int(product_line)
    except Exception:
        pl = 0
    module = MODULE_KIND_LABELS.get(mk, f"模块{mk}")
    line = PRODUCT_LINE_LABELS.get(pl, f"线{pl}")
    return module, line


def build_company_rows(
    companies: list[dict[str, Any]],
    bank_accounts: list[dict[str, Any]],
    directors_supervisors: list[dict[str, Any]],
    certificate_management: list[dict[str, Any]],
    seal_management: list[dict[str, Any]],
    seal_person_detail: list[dict[str, Any]],
    product_lines: list[dict[str, Any]],
    shareholders: list[dict[str, Any]],
    employee_map: dict[int, str],
    company_name_by_id: dict[str, str],
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

    by_company_product_lines: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in product_lines:
        by_company_product_lines[str(row.get("company_id") or "")].append(row)

    by_company_shareholders: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in shareholders:
        by_company_shareholders[str(row.get("company_id") or "")].append(row)

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

        for col, role in PERSON_ROLE_COLUMNS.items():
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
                    "cert_type": enum_label(
                        cert.get("certificate_type"), CERTIFICATE_TYPE_LABELS, "证照类型"
                    ),
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

        product_lines_payload: list[dict[str, Any]] = []
        for pl in by_company_product_lines.get(company_id, []):
            module, line = product_line_label(pl.get("module_kind"), pl.get("product_line"))
            product_lines_payload.append(
                {"module": module, "line": line, "relation": "关联"}
            )

        shareholder_rows = by_company_shareholders.get(company_id, [])
        total_subscribed = 0.0
        for sh in shareholder_rows:
            try:
                total_subscribed += float(sh.get("subscribed_capital") or 0)
            except Exception:
                pass
        shareholders_payload: list[dict[str, Any]] = []
        for sh in shareholder_rows:
            holder_type = enum_label(sh.get("shareholder_type"), SHAREHOLDER_TYPE_LABELS, "股东类型")
            sid = sh.get("shareholder_id")
            holder_name = ""
            try:
                sid_int = int(sid or 0)
            except Exception:
                sid_int = 0
            if sid_int > 0:
                if holder_type == "公司":
                    holder_name = company_name_by_id.get(str(sid_int), f"公司#{sid_int}")
                else:
                    holder_name = employee_map.get(sid_int, f"自然人#{sid_int}")
            ratio = ""
            try:
                sub = float(sh.get("subscribed_capital") or 0)
                if total_subscribed > 0 and sub > 0:
                    ratio = f"{sub / total_subscribed * 100:.2f}%"
                elif sub > 0:
                    ratio = f"认缴{sub:g}"
            except Exception:
                ratio = ""
            shareholders_payload.append(
                {
                    "holder_type": holder_type,
                    "holder_name": holder_name,
                    "ratio": ratio,
                    "subscribed_capital": str(sh.get("subscribed_capital") or ""),
                    "paid_in_capital": str(sh.get("paid_in_capital") or ""),
                }
            )

        parent_company = str(c.get("parent_company") or "").strip()
        parent_id = str(c.get("parent_company_id") or "").strip()
        if not parent_company and parent_id:
            parent_name = company_name_by_id.get(parent_id, "")
            parent_company = f"{parent_name}（ID {parent_id}）" if parent_name else f"总公司ID {parent_id}"

        row = {
            "company_id": company_id,
            "company_name": company_name,
            "company_short_name": str(c.get("company_short_name") or ""),
            "company_code": str(c.get("company_code") or ""),
            "credit_code": str(c.get("credit_code") or ""),
            "status": enum_label(c.get("status"), OPERATING_STATUS_LABELS) or normalize_status(c.get("status")),
            "entity_type": enum_label(c.get("entity_type"), MAIN_TYPE_LABELS, "主体类型"),
            "entity_category": enum_label(c.get("entity_category"), MAIN_CLASS_TYPE_LABELS, "主体分类"),
            "currency": str(c.get("currency") or ""),
            "registered_area": str(c.get("registered_area") or ""),
            "registered_address": str(c.get("registered_address") or ""),
            "office_address": str(c.get("office_address") or ""),
            "established_date": str(c.get("established_date") or ""),
            "business_scope": str(c.get("business_scope") or ""),
            "parent_company": parent_company,
            "tax_registered": yes_no_flag(c.get("tax_registered")),
            "bank_account_opened": yes_no_flag(c.get("bank_account_opened")),
            "social_security_opened": yes_no_flag(c.get("social_security_opened")),
            "housing_fund_opened": yes_no_flag(c.get("housing_fund_opened")),
            "product_lines": product_lines_payload,
            "shareholders": shareholders_payload,
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
        product_lines = "；".join(
            f"{x.get('module')}/{x.get('line')}/{x.get('relation')}" for x in row.get("product_lines", [])
        )
        shareholders = "；".join(
            f"{x.get('holder_type')}:{x.get('holder_name')}({x.get('ratio', '')})"
            for x in row.get("shareholders", [])
        )
        block = (
            f"{{companyId={row.get('company_id')}, companyName={row.get('company_name')}, summary={summary}}}\n"
            f"公司名称：{row.get('company_name')}\n"
            f"统一社会信用代码：{row.get('credit_code')}\n"
            f"经营状态：{row.get('status')}\n"
            f"主体类型：{row.get('entity_type')}\n"
            f"主体分类：{row.get('entity_category')}\n"
            f"注册地区：{row.get('registered_area')}\n"
            f"母公司：{row.get('parent_company')}\n"
            f"注册地址：{row.get('registered_address')}\n"
            f"实际办公地址：{row.get('office_address')}\n"
            f"经营范围：{row.get('business_scope')}\n"
            f"产品线：{product_lines}\n"
            f"股东：{shareholders}\n"
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
    if not output_dir.is_absolute():
        root = Path(__file__).resolve().parents[2]
        output_dir = root / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    with open_conn(args) as conn:
        companies = query_companies(conn, args.schema, args.limit)
        bank_accounts = query_bank_accounts(conn, args.schema)
        directors_supervisors = query_directors_supervisors(conn, args.schema)
        certificate_management = query_certificate_management(conn, args.schema)
        seal_management = query_seal_management(conn, args.schema)
        seal_person_detail = query_seal_person_detail(conn, args.schema)
        product_line_rows = query_company_product_lines(conn, args.schema)
        shareholder_rows = query_company_shareholders(conn, args.schema)
        employee_map, _employee_aliases = query_employee_map(conn, args.schema)

    company_name_by_id = {
        str(c.get("company_id") or "").strip(): str(c.get("company_name") or "").strip()
        for c in companies
        if str(c.get("company_id") or "").strip()
    }

    rows = build_company_rows(
        companies,
        bank_accounts,
        directors_supervisors,
        certificate_management,
        seal_management,
        seal_person_detail,
        product_line_rows,
        shareholder_rows,
        employee_map,
        company_name_by_id,
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
        "product_lines": sum(len(x.get("product_lines", [])) for x in rows),
        "shareholders": sum(len(x.get("shareholders", [])) for x in rows),
        "legal_rep_roles": sum(
            1
            for x in rows
            for p in x.get("key_people", [])
            if p.get("role") == "法定代表人"
        ),
        "output_jsonl": str(jsonl_path),
        "output_text": str(txt_path),
    }
    stats_path.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")

    print("Build finished.")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

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

from graph_export_util import (
    COMPANY_JSONL_NESTED_KEYS,
    attach_graph_props,
    build_graph_props,
    list_exportable_column_names,
    prepare_jsonl_row_for_neo4j,
)
from graph_node_definitions import GraphNodeDefinitions
from schema_field_maps import (
    BANK_ACCOUNT_STATUS_LABELS,
    BANK_ACCOUNT_TYPE_LABELS,
    CERTIFICATE_TYPE_LABELS,
    CERT_STATUS_LABELS,
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
    SEAL_CATEGORY_LABELS,
    SEAL_STATUS_LABELS,
    SEAL_TYPE_LABELS,
    SHAREHOLDER_TYPE_LABELS,
    coded_field,
    format_id_label,
    person_profile,
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
    parser.add_argument(
        "--since",
        default="",
        help="Incremental watermark (datetime/date). Uses modifytime/updated_at on company table.",
    )
    parser.add_argument(
        "--company-ids",
        default="",
        help="Comma-separated company ids to restrict scope (optional)",
    )
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


def query_exportable_rows(
    conn,
    schema: str,
    table: str,
    where_clause: str | None = None,
) -> list[dict[str, Any]]:
    """导出单表全部可灌图列（排除长文本）。"""
    cols = list_exportable_column_names(conn, schema, table)
    if not cols:
        return []
    if where_clause is None:
        where_clause = "deleteflag = 0" if "deleteflag" in table_columns(conn, schema, table) else "1=1"
    sql = f"SELECT {', '.join(f'`{c}`' for c in cols)} FROM `{table}` WHERE {where_clause}"
    with conn.cursor() as cur:
        cur.execute(sql)
        return cur.fetchall()


def parse_company_ids(raw: str) -> list[str]:
    if not raw or not raw.strip():
        return []
    return [x.strip() for x in raw.split(",") if x.strip()]


def query_companies(
    conn,
    schema: str,
    limit: int,
    since: str = "",
    company_ids: list[str] | None = None,
) -> list[dict[str, Any]]:
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

    exportable_cols = list_exportable_column_names(conn, schema, table)
    selected_physical = {s.split(" AS ")[-1].strip() for s in selected if " AS " in s}
    for col in exportable_cols:
        if col not in selected_physical:
            selected.append(f"`{col}`")

    where_parts: list[str] = []
    params: list[Any] = []
    if "deleteflag" in cols:
        where_parts.append("deleteflag = 0")
    if since and since.strip():
        updated_col = choose_first(
            cols,
            [
                "modifytime",
                "modifyTime",
                "updated_at",
                "update_time",
                "gmt_modified",
                "modify_time",
            ],
        )
        if updated_col:
            where_parts.append(f"`{updated_col}` > %s")
            params.append(since.strip())
    if company_ids:
        placeholders = ", ".join(["%s"] * len(company_ids))
        where_parts.append(f"`{id_col}` IN ({placeholders})")
        params.extend(company_ids)

    where_sql = f"WHERE {' AND '.join(where_parts)}" if where_parts else ""
    limit_sql = f"LIMIT {int(limit)}" if limit > 0 else ""
    sql = f"""
    SELECT {", ".join(selected)}
    FROM `{schema}`.`{table}`
    {where_sql}
    {limit_sql}
    """
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchall()


def query_bank_accounts(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "bank_account"):
        return []
    return query_exportable_rows(conn, schema, "bank_account")


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
    return query_exportable_rows(conn, schema, "company_product_line")


def query_company_shareholders(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "company_shareholder_info"):
        return []
    return query_exportable_rows(conn, schema, "company_shareholder_info")


def query_directors_supervisors(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "company_directors_supervisors"):
        return []
    return query_exportable_rows(conn, schema, "company_directors_supervisors")


def query_certificate_management(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "certificate_management"):
        return []
    rows = query_exportable_rows(conn, schema, "certificate_management")
    cols = table_columns(conn, schema, "certificate_management")
    code_col = choose_first(
        cols,
        [
            "certificate_no",
            "cert_no",
            "certificate_code",
            "license_no",
            "licence_no",
            "cert_number",
        ],
    )
    if code_col:
        for row in rows:
            row["certificate_code"] = row_value(row, code_col, "")
    return rows


def query_seal_management(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "seal_management"):
        return []
    return query_exportable_rows(conn, schema, "seal_management")


def query_seal_person_detail(conn, schema: str) -> list[dict[str, Any]]:
    if not table_exists(conn, schema, "seal_person_detail"):
        return []
    return query_exportable_rows(conn, schema, "seal_person_detail")


def query_certificate_attachment(conn, schema: str) -> list[dict[str, Any]]:
    """证照附件（辅助表 1）—— 按 company_id 关联企业。"""
    if not table_exists(conn, schema, "certificate_attachment"):
        return []
    return query_exportable_rows(conn, schema, "certificate_attachment")


def query_certificate_person_detail(conn, schema: str) -> list[dict[str, Any]]:
    """证照关联人员（辅助表 2）。"""
    if not table_exists(conn, schema, "certificate_person_detail"):
        return []
    return query_exportable_rows(conn, schema, "certificate_person_detail")


def query_company_change_log(conn, schema: str) -> list[dict[str, Any]]:
    """公司变更事件（辅助表 3）—— 同一公司内多行事件。"""
    if not table_exists(conn, schema, "company_change_log"):
        return []
    return query_exportable_rows(conn, schema, "company_change_log")


def query_employee_map(
    conn, schema: str
) -> tuple[dict[int, str], dict[str, int], dict[int, dict[str, str]]]:
    """返回 employee_id->主姓名、别名->employee_id、employee_id->人员展示档案。"""
    if not table_exists(conn, schema, "employee"):
        return {}, {}, {}
    cols = table_columns(conn, schema, "employee")
    id_col = "id" if "id" in cols else None
    if id_col is None:
        return {}, {}, {}
    name_col = choose_first(cols, ["name", "employee_name", "real_name", "nickname"])
    if name_col is None:
        return {}, {}, {}

    alias_cols = [
        c
        for c in ["another_name", "english_name", "name_spell", "another_name_spell"]
        if c in cols
    ]
    selected = [f"`{id_col}` AS id", f"`{name_col}` AS name"] + [f"`{c}` AS {c}" for c in alias_cols]
    selected_aliases = {s.split(" AS ")[-1].strip() for s in selected if " AS " in s}
    for col in list_exportable_column_names(conn, schema, "employee"):
        if col not in selected_aliases:
            selected.append(f"`{col}` AS {col}")
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
    profiles: dict[int, dict[str, str]] = {}
    for row in rows:
        try:
            key = int(row_value(row, "id", 0))
        except Exception:
            continue
        name = str(row_value(row, "name", "") or "").strip()
        another = ""
        if "another_name" in cols:
            another = str(row_value(row, "another_name", "") or "").strip()
        profile = person_profile(str(key), name, another)
        profiles[key] = profile
        if name:
            result[key] = name
            alias_to_id[name] = key
        for col in alias_cols:
            alias = str(row_value(row, col, "") or "").strip()
            if alias and len(alias) >= 2:
                alias_to_id[alias] = key
    return result, alias_to_id, profiles


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


def person_display_for(
    pid: int, emp_map: dict[int, str], profiles: dict[int, dict[str, str]]
) -> dict[str, str]:
    prof = profiles.get(pid, {})
    name = prof.get("name") or emp_map.get(pid, f"员工#{pid}")
    another = prof.get("another_name", "")
    display = prof.get("display") or format_id_label(str(pid), name)
    return {"name": name, "another_name": another, "display": display}


def _mysql_row_payload(row: dict[str, Any], overrides: dict[str, Any] | None = None) -> dict[str, Any]:
    """保留 MySQL 行全部短字段，并生成 graph_props 供 Neo4j 灌入。"""
    payload: dict[str, Any] = {}
    for key, value in row.items():
        if value is None:
            continue
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            payload[str(key)] = value
        else:
            payload[str(key)] = str(value)
    if overrides:
        payload.update(overrides)
    attach_graph_props(payload)
    return payload


def role_person(
    role: str,
    emp_id: Any,
    emp_map: dict[int, str],
    profiles: dict[int, dict[str, str]] | None = None,
) -> dict[str, Any] | None:
    try:
        pid = int(emp_id or 0)
    except Exception:
        pid = 0
    if pid <= 0:
        return None
    person = person_display_for(pid, emp_map, profiles or {})
    return {
        "role": role,
        "role_display": role,
        "name": person["name"],
        "another_name": person["another_name"],
        "person_id": str(pid),
        "person_display": person["display"],
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
    employee_profiles: dict[int, dict[str, str]],
    certificate_attachments: list[dict[str, Any]] | None = None,
    certificate_person_details: list[dict[str, Any]] | None = None,
    company_change_logs: list[dict[str, Any]] | None = None,
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

    by_company_attachments: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in certificate_attachments or []:
        by_company_attachments[str(row.get("company_id") or "")].append(row)

    by_company_cert_persons: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in certificate_person_details or []:
        by_company_cert_persons[str(row.get("company_id") or "")].append(row)

    by_company_change_events: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in company_change_logs or []:
        by_company_change_events[str(row.get("company_id") or "")].append(row)

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
            person = role_person(role, c.get(col), employee_map, employee_profiles)
            if not person:
                continue
            key = (person["role"], person["person_id"])
            if key in person_dedup:
                continue
            person_dedup.add(key)
            key_people.append(person)

        for pid in parse_id_list(c.get("assigned_it_ids")):
            person = role_person("IT负责人", pid, employee_map, employee_profiles)
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
                person = role_person(role, ba.get(col), employee_map, employee_profiles)
                if not person:
                    continue
                key = (person["role"], person["person_id"])
                if key in person_dedup:
                    continue
                person_dedup.add(key)
                key_people.append(person)

            acct_type = coded_field(ba.get("account_type"), BANK_ACCOUNT_TYPE_LABELS, "账户类型")
            acct_status = coded_field(ba.get("status"), BANK_ACCOUNT_STATUS_LABELS, "")
            bank_payloads.append(
                _mysql_row_payload(
                    ba,
                    {
                        "account_id": str(ba.get("id") or ""),
                        "account_type": acct_type["label"] or acct_type["code"],
                        "account_type_code": acct_type["code"],
                        "account_type_display": acct_type["display"],
                        "status": acct_status["label"] or normalize_status(ba.get("status")),
                        "status_code": acct_status["code"],
                        "status_display": acct_status["display"],
                    },
                )
            )

        for ds in directors_rows:
            member_type = coded_field(ds.get("member_type"), MEMBER_TYPE_LABELS, "董监高-")
            role_name = member_type["label"] or member_type_name(ds.get("member_type"))
            pid = str(ds.get("member_id") or "").strip()
            member_name = ""
            member_display = ""
            if pid:
                try:
                    person = person_display_for(int(pid), employee_map, employee_profiles)
                    member_name = person["name"]
                    member_display = person["display"]
                except Exception:
                    member_name = f"员工#{pid}"
                    member_display = format_id_label(pid, member_name)
            directors_supervisors_payload.append(
                _mysql_row_payload(
                    ds,
                    {
                        "member_type": role_name,
                        "member_type_code": member_type["code"],
                        "member_type_display": member_type["display"],
                        "member_id": pid,
                        "member_name": member_name,
                        "member_display": member_display,
                    },
                )
            )
            if pid:
                key = (f"董监高-{role_name}", pid)
                if key not in person_dedup:
                    person_dedup.add(key)
                    key_people.append(
                        {
                            "role": f"董监高-{role_name}",
                            "role_display": member_type["display"] or f"董监高-{role_name}",
                            "name": member_name or f"员工#{pid}",
                            "person_id": pid,
                            "person_display": member_display or format_id_label(pid, member_name),
                        }
                    )

        for cert in cert_rows:
            supervisors = parse_id_list(cert.get("supervisors"))
            keepers = parse_id_list(cert.get("certification_keepers"))
            executors = parse_id_list(cert.get("executors"))
            cert_code = str(
                cert.get("certificate_code")
                or cert.get("certificate_no")
                or cert.get("cert_no")
                or ""
            ).strip()
            cert_type = coded_field(cert.get("certificate_type"), CERTIFICATE_TYPE_LABELS, "证照类型")
            cert_status = coded_field(cert.get("status"), CERT_STATUS_LABELS, "")
            cert_id = str(cert.get("id") or "").strip()

            def _person_refs(ids: list[int]) -> list[str]:
                return [
                    person_display_for(pid, employee_map, employee_profiles)["display"]
                    for pid in ids
                ]

            certificates_payload.append(
                _mysql_row_payload(
                    cert,
                    {
                        "certificate_id": cert_id,
                        "cert_type": cert_type["label"] or cert_type["code"],
                        "cert_type_code": cert_type["code"],
                        "cert_type_display": cert_type["display"],
                        "status": cert_status["label"] or normalize_status(cert.get("status")),
                        "status_code": cert_status["code"],
                        "status_display": cert_status["display"],
                        "code": cert_code,
                        "expire_date": str(cert.get("valid_to") or ""),
                        "supervisors": _person_refs(supervisors),
                        "certification_keepers": _person_refs(keepers),
                        "executors": _person_refs(executors),
                    },
                )
            )
            for role, ids in (("证照监管人", supervisors), ("证照保管人", keepers), ("证照执行人", executors)):
                for pid in ids:
                    person = role_person(role, pid, employee_map, employee_profiles)
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
                person_display = account_name
                if uid:
                    try:
                        person = person_display_for(int(uid), employee_map, employee_profiles)
                        account_name = person["name"] or account_name or f"员工#{uid}"
                        person_display = person["display"]
                    except Exception:
                        account_name = account_name or f"员工#{uid}"
                        person_display = format_id_label(uid, account_name)
                role_type = str(p.get("role_type") or "")
                person_items.append(
                    {
                        "role_type": role_type,
                        "user_id": uid,
                        "account_name": account_name,
                        "person_display": person_display,
                    }
                )
                if uid:
                    key = (f"印章角色-{role_type or '未知'}", uid)
                    if key not in person_dedup:
                        person_dedup.add(key)
                        key_people.append(
                            {
                                "role": f"印章角色-{role_type or '未知'}",
                                "role_display": f"印章角色-{role_type or '未知'}",
                                "name": account_name,
                                "person_id": uid,
                                "person_display": person_display,
                            }
                        )
            seal_type = coded_field(seal.get("seal_type"), SEAL_TYPE_LABELS, "印章类型")
            seal_category = coded_field(seal.get("seal_category"), SEAL_CATEGORY_LABELS, "印章分类")
            seal_status = coded_field(seal.get("status"), SEAL_STATUS_LABELS, "")
            seals_payload.append(
                _mysql_row_payload(
                    seal,
                    {
                        "seal_id": seal_id,
                        "seal_type": seal_type["label"] or seal_type["code"],
                        "seal_type_code": seal_type["code"],
                        "seal_type_display": seal_type["display"],
                        "seal_category": seal_category["label"] or seal_category["code"],
                        "seal_category_code": seal_category["code"],
                        "seal_category_display": seal_category["display"],
                        "status": seal_status["label"] or normalize_status(seal.get("status")),
                        "status_code": seal_status["code"],
                        "status_display": seal_status["display"],
                        "persons": person_items,
                    },
                )
            )

        product_lines_payload: list[dict[str, Any]] = []
        module_map = {str(k): v for k, v in MODULE_KIND_LABELS.items()}
        line_map = {str(k): v for k, v in PRODUCT_LINE_LABELS.items()}
        for pl in by_company_product_lines.get(company_id, []):
            module_f = coded_field(pl.get("module_kind"), module_map, "模块")
            line_f = coded_field(pl.get("product_line"), line_map, "线")
            product_lines_payload.append(
                _mysql_row_payload(
                    pl,
                    {
                        "module": module_f["label"] or module_f["code"],
                        "module_code": module_f["code"],
                        "module_display": module_f["display"],
                        "line": line_f["label"] or line_f["code"],
                        "line_code": line_f["code"],
                        "line_display": line_f["display"],
                        "relation": "关联",
                    },
                )
            )

        # 3 类辅助表：证照附件 / 证照关联人员 / 公司变更事件
        attachments_payload: list[dict[str, Any]] = []
        for att in by_company_attachments.get(company_id, []):
            file_id = str(att.get("file_id") or att.get("id") or "").strip()
            if not file_id:
                continue
            attachments_payload.append(
                _mysql_row_payload(
                    att,
                    {
                        "file_id": file_id,
                        "attachment_id": str(att.get("attachment_id") or att.get("id") or file_id),
                        "certificate_id": str(att.get("certificate_id") or ""),
                        "company_id": company_id,
                    },
                )
            )

        certificate_persons_payload: list[dict[str, Any]] = []
        for cp in by_company_cert_persons.get(company_id, []):
            person_id = str(cp.get("person_id") or "").strip()
            certificate_id = str(cp.get("certificate_id") or "").strip()
            person_detail_id = str(cp.get("id") or cp.get("person_detail_id") or "").strip()
            if not (person_id and certificate_id) and not person_detail_id:
                continue
            role_f = coded_field(cp.get("role_type"), MEMBER_TYPE_LABELS, "证照角色")
            certificate_persons_payload.append(
                _mysql_row_payload(
                    cp,
                    {
                        "person_id": person_id,
                        "certificate_id": certificate_id,
                        "person_detail_id": person_detail_id,
                        "role_type": role_f["label"] or role_f["code"],
                        "role_type_code": role_f["code"],
                        "company_id": company_id,
                    },
                )
            )

        change_events_payload: list[dict[str, Any]] = []
        for ev in by_company_change_events.get(company_id, []):
            event_id = str(ev.get("id") or ev.get("event_id") or "").strip()
            if not event_id:
                continue
            change_type_f = coded_field(ev.get("change_type"), {}, "变更类型")
            change_events_payload.append(
                _mysql_row_payload(
                    ev,
                    {
                        "event_id": event_id,
                        "change_type": change_type_f["label"] or str(ev.get("change_type") or ""),
                        "change_type_code": change_type_f["code"],
                        "company_id": company_id,
                    },
                )
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
            holder_type_f = coded_field(sh.get("shareholder_type"), SHAREHOLDER_TYPE_LABELS, "股东类型")
            holder_type = holder_type_f["label"] or holder_type_f["code"]
            sid = sh.get("shareholder_id")
            holder_name = ""
            holder_display = ""
            try:
                sid_int = int(sid or 0)
            except Exception:
                sid_int = 0
            if sid_int > 0:
                if holder_type == "公司":
                    holder_name = company_name_by_id.get(str(sid_int), f"公司#{sid_int}")
                else:
                    holder_name = employee_map.get(sid_int, f"自然人#{sid_int}")
                holder_display = format_id_label(str(sid_int), holder_name)
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
                _mysql_row_payload(
                    sh,
                    {
                        "shareholder_id": str(sid or "").strip(),
                        "holder_type": holder_type,
                        "holder_type_code": holder_type_f["code"],
                        "holder_type_display": holder_type_f["display"],
                        "holder_name": holder_name,
                        "holder_display": holder_display,
                        "ratio": ratio,
                    },
                )
            )

        parent_company = str(c.get("parent_company") or "").strip()
        parent_id = str(c.get("parent_company_id") or "").strip()
        if not parent_company and parent_id:
            parent_name = company_name_by_id.get(parent_id, "")
            parent_company = f"{parent_name}（ID {parent_id}）" if parent_name else f"总公司ID {parent_id}"

        status_f = coded_field(c.get("status"), OPERATING_STATUS_LABELS, "")
        entity_type_f = coded_field(c.get("entity_type"), MAIN_TYPE_LABELS, "主体类型")
        entity_category_f = coded_field(c.get("entity_category"), MAIN_CLASS_TYPE_LABELS, "主体分类")

        row = {
            "domain": "org_master",
            "company_id": company_id,
            "company_name": company_name,
            "company_short_name": str(c.get("company_short_name") or ""),
            "company_code": str(c.get("company_code") or ""),
            "credit_code": str(c.get("credit_code") or ""),
            "status": status_f["label"] or enum_label(c.get("status"), OPERATING_STATUS_LABELS) or normalize_status(c.get("status")),
            "status_code": status_f["code"],
            "status_display": status_f["display"],
            "entity_type": entity_type_f["label"] or entity_type_f["code"],
            "entity_type_code": entity_type_f["code"],
            "entity_type_display": entity_type_f["display"],
            "entity_category": entity_category_f["label"] or entity_category_f["code"],
            "entity_category_code": entity_category_f["code"],
            "entity_category_display": entity_category_f["display"],
            "currency": str(c.get("currency") or ""),
            "registered_area": str(c.get("registered_area") or ""),
            "registered_address": str(c.get("registered_address") or ""),
            "office_address": str(c.get("office_address") or ""),
            "established_date": str(c.get("established_date") or ""),
            "business_scope": str(c.get("business_scope") or ""),
            "parent_company": parent_company,
            "parent_company_id": parent_id,
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
            "attachments": attachments_payload,
            "certificate_persons": certificate_persons_payload,
            "change_events": change_events_payload,
            "source_file": "mysql.tdcomp",
        }
        overlay = {
            k: v
            for k, v in row.items()
            if k not in COMPANY_JSONL_NESTED_KEYS
            and k != "domain"
            and not isinstance(v, (list, dict))
        }
        row["company_graph_props"] = build_graph_props(
            {**c, **overlay},
            exclude_keys=frozenset({"domain", "source_file"}),
        )
        rows.append(row)
    return rows


def _format_certificate_for_text(cert: dict[str, Any]) -> str:
    parts = [
        f"类型:{cert.get('cert_type_display') or cert.get('cert_type')}",
        f"状态:{cert.get('status_display') or cert.get('status')}",
    ]
    if cert.get("certificate_id"):
        parts.insert(0, f"证照ID:{cert.get('certificate_id')}")
    if cert.get("code"):
        parts.append(f"编号:{cert.get('code')}")
    if cert.get("valid_from") or cert.get("expire_date"):
        parts.append(f"有效期:{cert.get('valid_from') or '?'}-{cert.get('expire_date') or '?'}")
    keepers = ",".join(cert.get("certification_keepers", []) or [])
    supervisors = ",".join(cert.get("supervisors", []) or [])
    executors = ",".join(cert.get("executors", []) or [])
    if supervisors:
        parts.append(f"监管人:{supervisors}")
    if keepers:
        parts.append(f"保管人:{keepers}")
    if executors:
        parts.append(f"执行人:{executors}")
    return " ".join(parts)


def _format_seal_for_text(seal: dict[str, Any]) -> str:
    parts = [
        f"印章类型:{seal.get('seal_type_display') or seal.get('seal_type')}",
        f"分类:{seal.get('seal_category_display') or seal.get('seal_category')}",
        f"保管部门:{seal.get('custody_department')}",
        f"状态:{seal.get('status')}",
    ]
    persons = seal.get("persons") or []
    if persons:
        role_names = ",".join(
            f"{p.get('role_type') or '角色'}:{p.get('account_name') or p.get('user_id')}"
            for p in persons
        )
        parts.append(f"相关人员:{role_names}")
    return " ".join(p for p in parts if p and not p.endswith(":"))


def build_enum_dictionary_block() -> str:
    """业务证照/印章类型枚举字典，写入编译文本供文档召回与问答对照。"""
    cert_types = [CERTIFICATE_TYPE_LABELS[k] for k in CERTIFICATE_TYPE_LABELS if str(k).isdigit()]
    seal_types = [SEAL_TYPE_LABELS[k] for k in SEAL_TYPE_LABELS if str(k).isdigit()]
    cert_line = "；".join(f"类型:{t}" for t in cert_types)
    seal_line = "；".join(f"印章类型:{t}" for t in seal_types)
    return (
        "{companyId=enum-dict, companyName=企业证照与印章类型字典, summary=来源=业务枚举CertificateType/SealType}\n"
        "公司名称：企业证照与印章类型字典\n"
        f"证照信息：{cert_line}\n"
        f"印章信息：{seal_line}\n"
    )


def build_compiled_text(rows: list[dict[str, Any]]) -> str:
    blocks: list[str] = [build_enum_dictionary_block()]
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
            _format_certificate_for_text(x) for x in row.get("certificates", [])
        )
        seals = "；".join(_format_seal_for_text(x) for x in row.get("seals", []))
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
            f.write(json.dumps(prepare_jsonl_row_for_neo4j(row), ensure_ascii=False) + "\n")


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        root = Path(__file__).resolve().parents[2]
        output_dir = root / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    company_id_filter = parse_company_ids(args.company_ids)

    with open_conn(args) as conn:
        companies = query_companies(
            conn,
            args.schema,
            args.limit,
            since=args.since,
            company_ids=company_id_filter or None,
        )
        bank_accounts = query_bank_accounts(conn, args.schema)
        directors_supervisors = query_directors_supervisors(conn, args.schema)
        certificate_management = query_certificate_management(conn, args.schema)
        seal_management = query_seal_management(conn, args.schema)
        seal_person_detail = query_seal_person_detail(conn, args.schema)
        product_line_rows = query_company_product_lines(conn, args.schema)
        shareholder_rows = query_company_shareholders(conn, args.schema)
        certificate_attachments = query_certificate_attachment(conn, args.schema)
        certificate_person_details = query_certificate_person_detail(conn, args.schema)
        company_change_logs = query_company_change_log(conn, args.schema)
        employee_map, _employee_aliases, employee_profiles = query_employee_map(conn, args.schema)

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
        employee_profiles,
        certificate_attachments=certificate_attachments,
        certificate_person_details=certificate_person_details,
        company_change_logs=company_change_logs,
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
        "attachments": sum(len(x.get("attachments", [])) for x in rows),
        "certificate_persons": sum(len(x.get("certificate_persons", [])) for x in rows),
        "change_events": sum(len(x.get("change_events", [])) for x in rows),
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

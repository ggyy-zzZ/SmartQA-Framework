#!/usr/bin/env python3
"""
对照业务库 information_schema，检查 build_knowledge_from_mysql 是否覆盖主要字段/关联表。

用法:
  python scripts/enterprise_pipeline/audit_mysql_schema_coverage.py --schema tdcomp
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import pymysql
from pymysql.cursors import DictCursor

from schema_field_maps import COMPANY_SCALAR_ALIASES, PERSON_ROLE_COLUMNS

ROOT = Path(__file__).resolve().parents[2]

EXPORTED_TABLES = {
    "company",
    "employee",
    "bank_account",
    "company_directors_supervisors",
    "certificate_management",
    "seal_management",
    "seal_person_detail",
    "company_product_line",
    "company_shareholder_info",
}

OPTIONAL_TABLES = {
    "certificate_person_detail",
    "certificate_attachment",
    "company_change_log",
}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Audit MySQL schema coverage for knowledge export")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=3306)
    p.add_argument("--username", default="root")
    p.add_argument("--password", default="root")
    p.add_argument("--schema", default="tdcomp")
    p.add_argument("--output", default="data/knowledge/schema_coverage_report.json")
    return p.parse_args()


def row_val(row: dict, key: str) -> str:
    for k, v in row.items():
        if str(k).lower() == key.lower():
            return str(v).strip()
    return ""


def table_columns(conn, schema: str, table: str) -> set[str]:
    sql = """
    SELECT column_name
    FROM information_schema.columns
    WHERE table_schema=%s AND table_name=%s
    """
    with conn.cursor() as cur:
        cur.execute(sql, (schema, table))
        return {row_val(r, "column_name") for r in cur.fetchall() if row_val(r, "column_name")}


def table_exists(conn, schema: str, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT 1 FROM information_schema.tables
            WHERE table_schema=%s AND table_name=%s LIMIT 1
            """,
            (schema, table),
        )
        return cur.fetchone() is not None


def choose_first(cols: set[str], candidates: list[str]) -> str | None:
    for name in candidates:
        if name in cols:
            return name
    return None


def main() -> None:
    args = parse_args()
    conn = pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.username,
        password=args.password,
        database=args.schema,
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=True,
    )

    company_table = "company"
    if not table_exists(conn, args.schema, company_table):
        raise SystemExit(f"Missing table {company_table} in {args.schema}")

    company_cols = table_columns(conn, args.schema, company_table)
    mapped_scalar: list[str] = []
    missing_scalar: list[dict[str, str]] = []
    for canonical, candidates in COMPANY_SCALAR_ALIASES.items():
        physical = choose_first(company_cols, candidates)
        if physical:
            mapped_scalar.append(f"{canonical}<-{physical}")
        else:
            missing_scalar.append({"canonical": canonical, "candidates": ",".join(candidates)})

    mapped_person: list[str] = []
    missing_person: list[str] = []
    for col in PERSON_ROLE_COLUMNS:
        if col in company_cols:
            mapped_person.append(col)
        else:
            missing_person.append(col)

    unmapped_company_cols = sorted(
        c
        for c in company_cols
        if c not in {"id", "createtime", "modifytime", "deleteflag", "tenant", "remarks", "hidden_remarks"}
        and c not in mapped_person
        and not any(c in COMPANY_SCALAR_ALIASES.get(can, []) for can in COMPANY_SCALAR_ALIASES)
        and not any(c == choose_first(company_cols, COMPANY_SCALAR_ALIASES[can]) for can in COMPANY_SCALAR_ALIASES)
    )
    # refine: exclude columns already used as physical alias
    used_physical = set()
    for candidates in COMPANY_SCALAR_ALIASES.values():
        p = choose_first(company_cols, candidates)
        if p:
            used_physical.add(p)
    used_physical.update(mapped_person)
    used_physical.update(
        {
            "company_name",
            "company_short_name",
            "assigned_it_ids",
        }
    )
    unmapped_company_cols = sorted(c for c in company_cols if c not in used_physical and c not in {
        "id", "createtime", "modifytime", "deleteflag", "tenant", "remarks", "hidden_remarks",
        "legal_rep_mobile", "financial_manager_mobile", "manager_mobile", "company_contact_mobile",
        "chairman_exec_director_mobile",
    })

    tables_report: dict[str, str] = {}
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema=%s ORDER BY table_name
            """,
            (args.schema,),
        )
        all_tables = [row_val(r, "table_name") for r in cur.fetchall() if row_val(r, "table_name")]

    for t in all_tables:
        if t in EXPORTED_TABLES:
            tables_report[t] = "exported"
        elif t in OPTIONAL_TABLES:
            tables_report[t] = "optional_not_exported"
        elif t.startswith("qa_"):
            tables_report[t] = "qa_system"
        else:
            tables_report[t] = "not_exported"

    report = {
        "schema": args.schema,
        "company_table": company_table,
        "mapped_scalar_fields": mapped_scalar,
        "missing_scalar_candidates": missing_scalar,
        "mapped_person_id_columns": mapped_person,
        "missing_person_id_columns": missing_person,
        "unmapped_company_columns_hint": unmapped_company_cols[:40],
        "tables": tables_report,
    }

    out = ROOT / args.output
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nReport written: {out}")
    conn.close()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Sync cleaned enterprise JSONL into Neo4j knowledge graph.

约束/索引由 ``qa/graph-node-definitions.json`` 驱动生成；
3 类辅助节点（CertificateAttachment / CertificatePersonDetail /
CompanyChangeEvent）由本脚本补齐；CDC 仍只写 company/employee。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Iterable

from neo4j import GraphDatabase

from graph_export_util import prepare_jsonl_row_for_neo4j
from graph_node_definitions import GraphNodeDefinitions


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sync enterprise jsonl to Neo4j")
    parser.add_argument(
        "--input",
        default="data/knowledge/enterprise_mysql_clean.jsonl",
        help="Path to cleaned JSONL file",
    )
    parser.add_argument("--uri", default="bolt://localhost:7687", help="Neo4j bolt URI")
    parser.add_argument("--username", default="", help="Neo4j username, empty for no auth")
    parser.add_argument("--password", default="", help="Neo4j password")
    parser.add_argument("--limit", type=int, default=0, help="Limit rows for debug, 0 means all")
    parser.add_argument("--wipe", action="store_true", help="Delete existing graph labels before sync")
    parser.add_argument(
        "--slim",
        action="store_true",
        help="Slim graph: company/person nodes keep only id/name/status; skip certificate/seal/bank rich nodes",
    )
    parser.add_argument(
        "--truncate",
        type=int,
        default=0,
        help="Override global maxChars for TEXT columns (0 = read from graph-node-definitions.json)",
    )
    parser.add_argument(
        "--skip-truncate",
        action="store_true",
        help="Disable truncation entirely (use raw values)",
    )
    return parser.parse_args()


def read_jsonl(path: Path, limit: int = 0) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
            if limit > 0 and len(rows) >= limit:
                break
    return rows


def chunks(items: list[dict[str, Any]], size: int) -> Iterable[list[dict[str, Any]]]:
    for i in range(0, len(items), size):
        yield items[i : i + size]


def run_query(session, query: str, params: dict[str, Any] | None = None) -> None:
    session.run(query, params or {})


# ----------------------------------------------------------------------
# 约束/索引：读 defs 循环生成
# ----------------------------------------------------------------------
def apply_definitions_constraints_and_indexes(session, definitions: GraphNodeDefinitions) -> None:
    """按 graph-node-definitions.json 声明生成约束与索引（同名则 IF NOT EXISTS 跳过）。"""
    for c in definitions.constraints:
        name = c.get("name")
        label = c.get("label")
        prop = c.get("property")
        if not name or not label or not prop:
            continue
        run_query(
            session,
            (
                f"CREATE CONSTRAINT {name} IF NOT EXISTS "
                f"FOR (n:{label}) REQUIRE n.{prop} IS UNIQUE"
            ),
        )

    for i in definitions.indexes:
        name = i.get("name")
        label = i.get("label")
        props = i.get("properties") or []
        idx_type = (i.get("type") or "").upper()
        if not name or not label or not props:
            continue
        # Neo4j 索引名必须全小写、字母数字下划线
        safe_name = name
        if idx_type == "FULLTEXT":
            run_query(
                session,
                (
                    f"CREATE FULLTEXT INDEX {safe_name}_ft IF NOT EXISTS "
                    f"FOR (n:{label}) ON EACH [n.{props[0]}]"
                ),
            )
        else:
            run_query(
                session,
                (
                    f"CREATE INDEX {safe_name}_idx IF NOT EXISTS "
                    f"FOR (n:{label}) ON (n.{props[0]})"
                ),
            )


# ----------------------------------------------------------------------
# Wipe
# ----------------------------------------------------------------------
def wipe_graph(session, definitions: GraphNodeDefinitions) -> None:
    """删除 defs 声明的全部节点 Label；与 cdc-write-scope.json 互不重叠。"""
    labels = definitions.labels() or [
        "Company",
        "Person",
        "Certificate",
        "Seal",
        "BankAccount",
        "Shareholder",
        "ProductLine",
        "DirectorSupervisor",
    ]
    label_union = " OR ".join(f"n:{lbl}" for lbl in labels)
    run_query(
        session,
        f"MATCH (n) WHERE {label_union} DETACH DELETE n",
    )


# ----------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------
def _nested_array(row: dict[str, Any], key: str) -> list[dict[str, Any]]:
    v = row.get(key)
    if isinstance(v, list):
        return [x for x in v if isinstance(x, dict)]
    return []


def main() -> None:
    args = parse_args()
    input_path = Path(args.input)
    if not input_path.exists():
        raise SystemExit(f"Input JSONL not found: {input_path}")

    definitions = GraphNodeDefinitions.load()

    # 截断参数透传：step6 build_knowledge_from_mysql.py 仍可在写入前用同名 CLI；
    # sync_neo4j 这一层不重复截断（截断已由 prepare_jsonl_row_for_neo4j 完成）。
    # 仅在 --skip-truncate 模式下，把 graph_props 内的 *Truncated=true 全部清空。
    data = [prepare_jsonl_row_for_neo4j(row, definitions) for row in read_jsonl(input_path, args.limit)]
    if args.skip_truncate:
        for row in data:
            for k in list(row.keys()):
                v = row[k]
                if isinstance(v, dict):
                    row[k] = _strip_truncation_marks(v)
            cg = row.get("company_graph_props")
            if isinstance(cg, dict):
                row["company_graph_props"] = _strip_truncation_marks(cg)
    if not data:
        raise SystemExit("No records to sync.")

    auth = (args.username, args.password) if args.username else None
    driver = GraphDatabase.driver(args.uri, auth=auth)

    with driver.session() as session:
        if args.wipe:
            wipe_graph(session, definitions)

        apply_definitions_constraints_and_indexes(session, definitions)

        slim = args.slim
        for batch in chunks(data, 200):
            if slim:
                run_query(
                    session,
                    """
                    UNWIND $rows AS row
                    MERGE (c:Company {companyId: row.company_id})
                    SET c.name = row.company_name,
                        c.status = row.status
                    """,
                    {"rows": batch},
                )
            else:
                run_query(
                    session,
                    """
                    UNWIND $rows AS row
                    MERGE (c:Company {companyId: row.company_id})
                    SET c += coalesce(row.company_graph_props, {}),
                        c.companyId = row.company_id,
                        c.name = coalesce(nullif(trim(row.company_name), ''), c.name),
                        c.sourceFile = coalesce(row.source_file, c.sourceFile)
                    """,
                    {"rows": batch},
                )

            if slim:
                run_query(
                    session,
                    """
                    UNWIND $rows AS row
                    UNWIND coalesce(row.key_people, []) AS person
                    WITH row, person
                    WHERE person.name IS NOT NULL AND trim(person.name) <> ''
                    MATCH (c:Company {companyId: row.company_id})
                    MERGE (p:Person {
                        personKey: coalesce(person.person_id, 'NAME::' + person.name)
                    })
                    SET p.name = person.name
                    MERGE (p)-[r:HAS_ROLE_IN {role: person.role}]->(c)
                    """,
                    {"rows": batch},
                )
                continue

            # ---- BankAccount ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.bank_accounts, []) AS ba
                WITH row, ba
                WHERE ba.account_id IS NOT NULL AND trim(ba.account_id) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (b:BankAccount {accountKey: row.company_id + '|' + ba.account_id})
                SET b += coalesce(ba.graph_props, {}),
                    b.accountId = coalesce(ba.account_id, b.accountId)
                MERGE (c)-[:HAS_BANK_ACCOUNT]->(b)
                """,
                {"rows": batch},
            )

            # ---- Parent (via parent_company_id / parent_company) ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                WITH row
                WHERE row.parent_company_id IS NOT NULL AND trim(row.parent_company_id) <> ''
                MATCH (child:Company {companyId: row.company_id})
                MERGE (parent:Company {companyId: row.parent_company_id})
                SET parent.name = coalesce(nullif(trim(row.parent_company), ''), parent.name)
                MERGE (parent)-[:PARENT_OF]->(child)
                """,
                {"rows": batch},
            )
            run_query(
                session,
                """
                UNWIND $rows AS row
                WITH row
                WHERE (row.parent_company_id IS NULL OR trim(row.parent_company_id) = '')
                  AND row.parent_company IS NOT NULL AND trim(row.parent_company) <> ''
                MATCH (child:Company {companyId: row.company_id})
                MERGE (parent:Company {companyId: 'PARENT_NAME::' + row.parent_company})
                SET parent.name = row.parent_company
                MERGE (parent)-[:PARENT_OF]->(child)
                """,
                {"rows": batch},
            )

            # ---- Person / HAS_ROLE_IN ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.key_people, []) AS person
                WITH row, person
                WHERE person.name IS NOT NULL AND trim(person.name) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (p:Person {
                    personKey: coalesce(person.person_id, 'NAME::' + person.name)
                })
                SET p += coalesce(person.graph_props, {}),
                    p.name = coalesce(person.name, p.name),
                    p.personId = coalesce(person.person_id, p.personId),
                    p.displayName = coalesce(person.person_display, person.name, p.displayName)
                MERGE (p)-[r:HAS_ROLE_IN {role: person.role}]->(c)
                SET r.roleDisplay = coalesce(person.role_display, person.role, r.roleDisplay),
                    r.personDisplay = coalesce(person.person_display, person.name, r.personDisplay)
                """,
                {"rows": batch},
            )

            # ---- ProductLine ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.product_lines, []) AS pl
                WITH row, pl
                WHERE pl.module IS NOT NULL AND pl.line IS NOT NULL
                MATCH (c:Company {companyId: row.company_id})
                MERGE (p:ProductLine {productKey: coalesce(pl.module_code, pl.module) + '|' + coalesce(pl.line_code, pl.line)})
                SET p += coalesce(pl.graph_props, {}),
                    p.module = coalesce(pl.module, p.module),
                    p.line = coalesce(pl.line, p.line)
                MERGE (c)-[r:BELONGS_TO_PRODUCT]->(p)
                SET r.relation = coalesce(pl.relation, r.relation)
                """,
                {"rows": batch},
            )

            # ---- DirectorSupervisor ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.directors_supervisors, []) AS ds
                WITH row, ds
                WHERE ds.member_id IS NOT NULL AND trim(toString(ds.member_id)) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (d:DirectorSupervisor {
                    directorKey: row.company_id + '|' + toString(ds.member_id) + '|' + coalesce(ds.member_type_code, ds.member_type, '')
                })
                SET d += coalesce(ds.graph_props, {}),
                    d.memberId = coalesce(ds.member_id, d.memberId),
                    d.memberName = coalesce(ds.member_name, d.memberName),
                    d.memberDisplay = coalesce(ds.member_display, d.memberDisplay)
                MERGE (c)-[:HAS_DIRECTOR_SUPERVISOR]->(d)
                """,
                {"rows": batch},
            )

            # ---- Shareholder ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.shareholders, []) AS sh
                WITH row, sh
                WHERE sh.holder_name IS NOT NULL AND trim(sh.holder_name) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (s:Shareholder {
                    shareholderKey: coalesce(sh.shareholder_id, sh.holder_type + '|' + sh.holder_name)
                })
                SET s += coalesce(sh.graph_props, {}),
                    s.shareholderId = coalesce(sh.shareholder_id, s.shareholderId),
                    s.name = coalesce(sh.holder_name, s.name),
                    s.holderDisplay = coalesce(sh.holder_display, s.holderDisplay)
                MERGE (s)-[r:HOLDS_SHARES_IN]->(c)
                SET r.ratio = coalesce(sh.ratio, r.ratio)
                """,
                {"rows": batch},
            )

            # ---- Certificate ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.certificates, []) AS cert
                WITH row, cert
                WHERE cert.cert_type IS NOT NULL AND trim(cert.cert_type) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (ct:Certificate {
                    certKey: row.company_id + '|' + coalesce(cert.certificate_id, cert.cert_type_code, cert.cert_type, '') + '|' + coalesce(cert.code, '')
                })
                SET ct += coalesce(cert.graph_props, {}),
                    ct.certificateId = coalesce(cert.certificate_id, ct.certificateId),
                    ct.certType = coalesce(cert.cert_type, ct.certType),
                    ct.code = coalesce(cert.code, ct.code)
                MERGE (c)-[:HAS_CERTIFICATE]->(ct)
                """,
                {"rows": batch},
            )

            # ---- Seal ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.seals, []) AS seal
                WITH row, seal
                WHERE seal.seal_id IS NOT NULL AND trim(seal.seal_id) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (s:Seal {sealKey: row.company_id + '|' + seal.seal_id})
                SET s += coalesce(seal.graph_props, {}),
                    s.sealId = coalesce(seal.seal_id, s.sealId)
                MERGE (c)-[:HAS_SEAL]->(s)
                """,
                {"rows": batch},
            )

            # ---- CertificateAttachment (新增 3 类辅助节点) ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.attachments, []) AS a
                WITH row, a
                WHERE a.file_id IS NOT NULL AND trim(toString(a.file_id)) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (att:CertificateAttachment {
                    attachmentKey: row.company_id + '|' +
                                   coalesce(toString(a.certificate_id), '') + '|' +
                                   toString(a.file_id)
                })
                SET att += coalesce(a.graph_props, {}),
                    att.attachmentId = coalesce(a.attachment_id, att.attachmentId),
                    att.fileId = coalesce(a.file_id, att.fileId)
                MERGE (c)-[:HAS_ATTACHMENT]->(att)
                """,
                {"rows": batch},
            )

            # ---- CertificatePersonDetail ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.certificate_persons, []) AS cp
                WITH row, cp
                WHERE cp.person_detail_id IS NOT NULL
                   OR (cp.person_id IS NOT NULL AND cp.certificate_id IS NOT NULL)
                MATCH (c:Company {companyId: row.company_id})
                MERGE (pd:CertificatePersonDetail {
                    personDetailKey: row.company_id + '|' +
                                     coalesce(toString(cp.certificate_id), '') + '|' +
                                     coalesce(toString(cp.person_id), '') + '|' +
                                     coalesce(cp.role_type_code, cp.role_type, '')
                })
                SET pd += coalesce(cp.graph_props, {}),
                    pd.personDetailId = coalesce(cp.person_detail_id, pd.personDetailId),
                    pd.certificateId = coalesce(cp.certificate_id, pd.certificateId),
                    pd.personId = coalesce(cp.person_id, pd.personId)
                MERGE (c)-[:HAS_CERTIFICATE_PERSON]->(pd)
                """,
                {"rows": batch},
            )

            # ---- CompanyChangeEvent ----
            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.change_events, []) AS ev
                WITH row, ev
                WHERE ev.event_id IS NOT NULL AND trim(toString(ev.event_id)) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (ce:CompanyChangeEvent {
                    eventKey: row.company_id + '|' + toString(ev.event_id)
                })
                SET ce += coalesce(ev.graph_props, {}),
                    ce.eventId = coalesce(ev.event_id, ce.eventId),
                    ce.changeType = coalesce(ev.change_type, ce.changeType)
                MERGE (c)-[:HAS_CHANGE_EVENT]->(ce)
                """,
                {"rows": batch},
            )

        counts = session.run(
            """
            RETURN
              count { MATCH (:Company) } AS companies,
              count { MATCH (:Person) } AS people,
              count { MATCH (:ProductLine) } AS products,
              count { MATCH (:Shareholder) } AS shareholders,
              count { MATCH (:Certificate) } AS certificates,
              count { MATCH (:Seal) } AS seals,
              count { MATCH (:BankAccount) } AS bankAccounts,
              count { MATCH (:DirectorSupervisor) } AS directors,
              count { MATCH (:CertificateAttachment) } AS attachments,
              count { MATCH (:CertificatePersonDetail) } AS certPersons,
              count { MATCH (:CompanyChangeEvent) } AS changeEvents
            """
        ).single()

    driver.close()

    print("Sync finished.")
    print(
        json.dumps(
            {
                "input": str(input_path),
                "synced_rows": len(data),
                "uri": args.uri,
                "counts": {
                    "companies": counts["companies"],
                    "people": counts["people"],
                    "products": counts["products"],
                    "shareholders": counts["shareholders"],
                    "certificates": counts["certificates"],
                    "seals": counts["seals"],
                    "bankAccounts": counts["bankAccounts"],
                    "directors": counts["directors"],
                    "attachments": counts["attachments"],
                    "certificatePersons": counts["certPersons"],
                    "changeEvents": counts["changeEvents"],
                },
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def _strip_truncation_marks(props: dict[str, Any]) -> dict[str, Any]:
    """
    移除 ``*Truncated=true`` / ``*CharCount=N`` 标记属性。
    仅在 ``--skip-truncate`` 模式下调用。
    """
    return {
        k: v
        for k, v in props.items()
        if not (k.endswith("Truncated") or k.endswith("CharCount"))
    }


if __name__ == "__main__":
    main()

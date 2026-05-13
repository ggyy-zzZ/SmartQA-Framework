#!/usr/bin/env python3
"""
Sync cleaned enterprise JSONL into Neo4j knowledge graph.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Iterable

from neo4j import GraphDatabase


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


def main() -> None:
    args = parse_args()
    input_path = Path(args.input)
    if not input_path.exists():
        raise SystemExit(f"Input JSONL not found: {input_path}")

    data = read_jsonl(input_path, args.limit)
    if not data:
        raise SystemExit("No records to sync.")

    auth = (args.username, args.password) if args.username else None
    driver = GraphDatabase.driver(args.uri, auth=auth)

    with driver.session() as session:
        if args.wipe:
            run_query(
                session,
                """
                MATCH (n)
                WHERE n:Company OR n:Person OR n:Shareholder OR n:Certificate OR n:ProductLine OR n:BankAccount
                DETACH DELETE n
                """,
            )

        run_query(session, "CREATE CONSTRAINT company_id IF NOT EXISTS FOR (c:Company) REQUIRE c.companyId IS UNIQUE")
        run_query(session, "CREATE CONSTRAINT person_key IF NOT EXISTS FOR (p:Person) REQUIRE p.personKey IS UNIQUE")
        run_query(
            session,
            "CREATE CONSTRAINT product_key IF NOT EXISTS FOR (p:ProductLine) REQUIRE p.productKey IS UNIQUE",
        )
        run_query(
            session,
            "CREATE CONSTRAINT cert_key IF NOT EXISTS FOR (c:Certificate) REQUIRE c.certKey IS UNIQUE",
        )
        run_query(
            session,
            "CREATE CONSTRAINT shareholder_key IF NOT EXISTS FOR (s:Shareholder) REQUIRE s.shareholderKey IS UNIQUE",
        )
        run_query(
            session,
            "CREATE CONSTRAINT bank_account_key IF NOT EXISTS FOR (b:BankAccount) REQUIRE b.accountKey IS UNIQUE",
        )

        for batch in chunks(data, 200):
            run_query(
                session,
                """
                UNWIND $rows AS row
                MERGE (c:Company {companyId: row.company_id})
                SET c.name = row.company_name,
                    c.shortName = row.company_short_name,
                    c.companyCode = row.company_code,
                    c.creditCode = row.credit_code,
                    c.status = row.status,
                    c.entityType = row.entity_type,
                    c.entityCategory = row.entity_category,
                    c.currency = row.currency,
                    c.registeredArea = row.registered_area,
                    c.registeredAddress = row.registered_address,
                    c.officeAddress = row.office_address,
                    c.establishedDate = row.established_date,
                    c.businessScope = row.business_scope,
                    c.taxRegistered = row.tax_registered,
                    c.bankAccountOpened = row.bank_account_opened,
                    c.socialSecurityOpened = row.social_security_opened,
                    c.housingFundOpened = row.housing_fund_opened,
                    c.sourceFile = row.source_file
                """,
                {"rows": batch},
            )

            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.bank_accounts, []) AS ba
                WITH row, ba
                WHERE ba.account_id IS NOT NULL AND trim(ba.account_id) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (b:BankAccount {accountKey: row.company_id + '|' + ba.account_id})
                SET b.accountId = ba.account_id,
                    b.accountName = ba.account_name,
                    b.accountType = ba.account_type,
                    b.bankName = ba.bank_name,
                    b.accountNumber = ba.account_number,
                    b.bankSubjectCode = ba.bank_subject_code,
                    b.accountSetCode = ba.account_set_code,
                    b.status = ba.status,
                    b.remark = ba.remark
                MERGE (c)-[:HAS_BANK_ACCOUNT]->(b)
                """,
                {"rows": batch},
            )

            run_query(
                session,
                """
                UNWIND $rows AS row
                WITH row
                WHERE row.parent_company IS NOT NULL AND trim(row.parent_company) <> ''
                MATCH (child:Company {companyId: row.company_id})
                MERGE (parent:Company {companyId: 'PARENT_NAME::' + row.parent_company})
                SET parent.name = row.parent_company
                MERGE (parent)-[:PARENT_OF]->(child)
                """,
                {"rows": batch},
            )

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
                SET p.name = person.name,
                    p.personId = person.person_id
                MERGE (p)-[r:HAS_ROLE_IN]->(c)
                SET r.role = person.role
                """,
                {"rows": batch},
            )

            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.product_lines, []) AS pl
                WITH row, pl
                WHERE pl.module IS NOT NULL AND pl.line IS NOT NULL
                MATCH (c:Company {companyId: row.company_id})
                MERGE (p:ProductLine {productKey: pl.module + '|' + pl.line})
                SET p.module = pl.module,
                    p.line = pl.line
                MERGE (c)-[r:BELONGS_TO_PRODUCT]->(p)
                SET r.relation = pl.relation
                """,
                {"rows": batch},
            )

            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.shareholders, []) AS sh
                WITH row, sh
                WHERE sh.holder_name IS NOT NULL AND trim(sh.holder_name) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (s:Shareholder {shareholderKey: sh.holder_type + '|' + sh.holder_name})
                SET s.name = sh.holder_name,
                    s.holderType = sh.holder_type
                MERGE (s)-[r:HOLDS_SHARES_IN]->(c)
                SET r.ratio = sh.ratio
                """,
                {"rows": batch},
            )

            run_query(
                session,
                """
                UNWIND $rows AS row
                UNWIND coalesce(row.certificates, []) AS cert
                WITH row, cert
                WHERE cert.cert_type IS NOT NULL AND trim(cert.cert_type) <> ''
                MATCH (c:Company {companyId: row.company_id})
                MERGE (ct:Certificate {
                    certKey: row.company_id + '|' + cert.cert_type + '|' + coalesce(cert.code, '')
                })
                SET ct.certType = cert.cert_type,
                    ct.status = cert.status,
                    ct.code = cert.code,
                    ct.issueDate = cert.issue_date,
                    ct.expireDate = cert.expire_date
                MERGE (c)-[:HAS_CERTIFICATE]->(ct)
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
              count { MATCH (:BankAccount) } AS bankAccounts
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
                    "bankAccounts": counts["bankAccounts"],
                },
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

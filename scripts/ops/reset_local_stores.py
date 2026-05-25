#!/usr/bin/env python3
"""
清空本地验证环境：Qdrant 向量集合、Neo4j 业务/学习子图、MySQL assistant 数据表、可选 qa 日志 jsonl。

用法:
  python scripts/ops/reset_local_stores.py
  python scripts/ops/reset_local_stores.py --skip-mysql --skip-neo4j
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]

QDRANT_COLLECTIONS = [
    "enterprise_knowledge_v1",
    "enterprise_knowledge_v2",
    "enterprise_active_learning_v1",
    "enterprise_active_learning_v2",
]

NEO4J_WIPE_CYPHER = """
MATCH (n)
WHERE n:Company OR n:Person OR n:Shareholder OR n:Certificate OR n:ProductLine
   OR n:BankAccount OR n:LearnedKnowledge OR n:LearnedKeyword
DETACH DELETE n
""".strip()


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Reset local Qdrant, Neo4j, MySQL assistant, optional logs")
    p.add_argument("--qdrant-host", default="localhost")
    p.add_argument("--qdrant-port", type=int, default=6333)
    p.add_argument("--neo4j-uri", default="bolt://localhost:7687")
    p.add_argument("--neo4j-username", default="")
    p.add_argument("--neo4j-password", default="")
    p.add_argument("--mysql-host", default="localhost")
    p.add_argument("--mysql-port", type=int, default=3306)
    p.add_argument("--mysql-username", default="root")
    p.add_argument("--mysql-password", default="root")
    p.add_argument("--mysql-database", default="assistant")
    p.add_argument("--skip-qdrant", action="store_true")
    p.add_argument("--skip-neo4j", action="store_true")
    p.add_argument("--skip-mysql", action="store_true")
    p.add_argument("--clear-logs", action="store_true", help="Truncate data/qa_logs/*.jsonl")
    return p.parse_args()


def reset_qdrant(host: str, port: int) -> None:
    base = f"http://{host}:{port}"
    for name in QDRANT_COLLECTIONS:
        url = f"{base}/collections/{name}"
        try:
            resp = requests.delete(url, timeout=30)
            if resp.status_code in (200, 202):
                print(f"[qdrant] deleted collection: {name}")
            elif resp.status_code == 404:
                print(f"[qdrant] collection not found (skip): {name}")
            else:
                print(f"[qdrant] delete {name}: HTTP {resp.status_code} {resp.text[:200]}")
        except requests.RequestException as e:
            print(f"[qdrant] failed {name}: {e}")


def reset_neo4j(uri: str, username: str, password: str) -> None:
    try:
        from neo4j import GraphDatabase
    except ImportError:
        print("[neo4j] skip: pip install neo4j")
        return
    auth = (username, password) if username else None
    driver = GraphDatabase.driver(uri, auth=auth)
    with driver.session() as session:
        result = session.run(NEO4J_WIPE_CYPHER)
        summary = result.consume()
        print(f"[neo4j] wiped nodes (counters): {summary.counters}")
    driver.close()


def run_mysql_script(host: str, port: int, user: str, password: str, database: str, sql_file: Path, label: str) -> bool:
    base = (
        f'mysql -h{host} -P{port} -u{user} -p{password} '
        f'--default-character-set=utf8mb4 {database}'
    )
    try:
        # 文件重定向避免 Windows 下 stdin 中文编码问题
        proc = subprocess.run(
            f'{base} < "{sql_file}"',
            shell=True,
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        print("[mysql] skip: mysql client not in PATH")
        return False
    if proc.returncode != 0:
        print(f"[mysql] {label} failed:\n{proc.stderr}")
        return False
    print(f"[mysql] {label} ok")
    return True


def reset_mysql(host: str, port: int, user: str, password: str, database: str) -> None:
    bootstrap = ROOT / "data/sql/mysql/assistant_bootstrap.sql"
    reset_data = ROOT / "data/sql/mysql/assistant_reset_data.sql"
    if not bootstrap.exists() or not reset_data.exists():
        raise SystemExit("MySQL SQL files missing under data/sql/mysql/")
    run_mysql_script(host, port, user, password, database, bootstrap, "bootstrap schema")
    run_mysql_script(host, port, user, password, database, reset_data, "truncate qa_* and empty business tables")


def clear_logs() -> None:
    log_dir = ROOT / "data/qa_logs"
    if not log_dir.exists():
        return
    for path in log_dir.glob("*.jsonl"):
        path.write_text("", encoding="utf-8")
        print(f"[logs] truncated {path.relative_to(ROOT)}")


def main() -> None:
    args = parse_args()
    print("=== reset local knowledge stores ===")
    if not args.skip_qdrant:
        reset_qdrant(args.qdrant_host, args.qdrant_port)
    if not args.skip_neo4j:
        reset_neo4j(args.neo4j_uri, args.neo4j_username, args.neo4j_password)
    if not args.skip_mysql:
        reset_mysql(
            args.mysql_host,
            args.mysql_port,
            args.mysql_username,
            args.mysql_password,
            args.mysql_database,
        )
    if args.clear_logs:
        clear_logs()
    print("=== reset done ===")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Orchestrate incremental knowledge sync: extract → Neo4j upsert → Qdrant upsert.

No --wipe / --recreate by default. See docs/local-validation-mvp-roadmap.md
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PIPELINE = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run incremental enterprise knowledge sync")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--username", default="root")
    parser.add_argument("--password", default="root")
    parser.add_argument("--schema", default="tdcomp")
    parser.add_argument("--output-dir", default="data/knowledge")
    parser.add_argument("--since", default="", help="ISO datetime or date; default last 24h")
    parser.add_argument("--company-ids", default="", help="Comma-separated company ids (optional scope)")
    parser.add_argument("--neo4j-uri", default="bolt://localhost:7687")
    parser.add_argument("--qdrant-host", default="localhost")
    parser.add_argument("--qdrant-port", type=int, default=6333)
    parser.add_argument("--collection", default="enterprise_knowledge_v2")
    parser.add_argument("--skip-neo4j", action="store_true")
    parser.add_argument("--skip-qdrant", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def default_since() -> str:
    dt = datetime.now(timezone.utc) - timedelta(hours=24)
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def run_step(cmd: list[str], dry_run: bool) -> None:
    print("[incremental]", " ".join(cmd))
    if dry_run:
        return
    subprocess.run(cmd, cwd=ROOT, check=True)


def main() -> None:
    args = parse_args()
    since = args.since.strip() or default_since()
    py = sys.executable

    build_cmd = [
        py,
        str(PIPELINE / "build_knowledge_from_mysql.py"),
        "--host",
        args.host,
        "--port",
        str(args.port),
        "--username",
        args.username,
        "--password",
        args.password,
        "--schema",
        args.schema,
        "--output-dir",
        args.output_dir,
        "--since",
        since,
    ]
    if args.company_ids.strip():
        build_cmd.extend(["--company-ids", args.company_ids.strip()])
    run_step(build_cmd, args.dry_run)

    jsonl = ROOT / args.output_dir / "enterprise_mysql_clean.jsonl"
    if args.dry_run:
        print(f"[incremental] would sync from {jsonl}")
        return

    if not args.skip_neo4j:
        run_step(
            [
                py,
                str(PIPELINE / "sync_neo4j.py"),
                "--input",
                str(jsonl),
                "--uri",
                args.neo4j_uri,
            ],
            False,
        )

    if not args.skip_qdrant:
        run_step(
            [
                py,
                str(PIPELINE / "sync_vectors_qdrant.py"),
                "--input",
                str(jsonl),
                "--host",
                args.qdrant_host,
                "--port",
                str(args.qdrant_port),
                "--collection",
                args.collection,
            ],
            False,
        )

    print("[incremental] done", {"since": since, "input": str(jsonl)})


if __name__ == "__main__":
    main()

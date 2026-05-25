#!/usr/bin/env python3
"""
一键：清空三库 → 从 enterprise_mysql_clean.jsonl 同步 Neo4j + Qdrant（百炼 embedding）。

前置:
  - Qdrant / Neo4j 已启动
  - 环境变量 DASHSCOPE_API_KEY（或 --embedding-api-key）
  - 已有 data/knowledge/enterprise_mysql_clean.jsonl（可先 run_pipeline 从 tdcomp 构建）

用法:
  python scripts/ops/rebuild_local_knowledge.py
  python scripts/ops/rebuild_local_knowledge.py --limit 50
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PIPELINE = ROOT / "scripts/enterprise_pipeline"
OPS = ROOT / "scripts/ops"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Reset stores and rebuild graph + vectors from JSONL")
    p.add_argument("--input", default="data/knowledge/enterprise_mysql_clean.jsonl")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--skip-reset", action="store_true")
    p.add_argument("--clear-logs", action="store_true")
    p.add_argument("--qdrant-collection", default="enterprise_knowledge_v2")
    p.add_argument("--embedding-provider", default="dashscope", choices=["dashscope", "hash"])
    p.add_argument("--embedding-api-key", default=os.environ.get("DASHSCOPE_API_KEY", ""))
    p.add_argument("--neo4j-uri", default="bolt://localhost:7687")
    return p.parse_args()


def run(cmd: list[str]) -> None:
    print("Running:", " ".join(cmd))
    subprocess.run(cmd, check=True, cwd=str(ROOT))


def main() -> None:
    args = parse_args()
    input_path = ROOT / args.input
    if not input_path.exists():
        raise SystemExit(
            f"Missing {input_path}. Build it first, e.g.:\n"
            "  python scripts/enterprise_pipeline/build_knowledge_from_mysql.py --schema tdcomp\n"
            "  or use existing enterprise_mysql_clean.jsonl from data/knowledge/"
        )

    if not args.skip_reset:
        reset_cmd = [sys.executable, str(OPS / "reset_local_stores.py")]
        if args.clear_logs:
            reset_cmd.append("--clear-logs")
        run(reset_cmd)

    sync_neo4j = [
        sys.executable,
        str(PIPELINE / "sync_neo4j.py"),
        "--input",
        str(input_path.relative_to(ROOT)).replace("\\", "/"),
        "--uri",
        args.neo4j_uri,
        "--wipe",
    ]
    if args.limit > 0:
        sync_neo4j += ["--limit", str(args.limit)]
    run(sync_neo4j)

    sync_vec = [
        sys.executable,
        str(PIPELINE / "sync_vectors_qdrant.py"),
        "--input",
        str(input_path.relative_to(ROOT)).replace("\\", "/"),
        "--collection",
        args.qdrant_collection,
        "--embedding-provider",
        args.embedding_provider,
        "--embedding-model",
        "text-embedding-v4",
        "--embedding-dim",
        "1024",
        "--recreate",
    ]
    api_key = args.embedding_api_key
    if args.embedding_provider == "dashscope" and not api_key:
        print("[warn] DASHSCOPE_API_KEY unset; falling back to hash embedding for this run.")
        sync_vec[sync_vec.index("dashscope")] = "hash"
    elif api_key:
        sync_vec += ["--embedding-api-key", api_key]
    if args.limit > 0:
        sync_vec += ["--limit", str(args.limit)]
    run(sync_vec)

    print("\n=== rebuild complete ===")
    print("Next: ./mvnw.cmd spring-boot:run")
    print("Check: curl http://localhost:8080/qa/assistant/runtime-summary")


if __name__ == "__main__":
    main()

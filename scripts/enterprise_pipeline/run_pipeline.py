#!/usr/bin/env python3
"""
One-command pipeline:
1) build normalized knowledge from MySQL
2) sync cleaned data to Neo4j
3) embed cleaned data and sync vectors to Qdrant (optional)
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run mysql knowledge build + graph/vector sync pipeline")
    parser.add_argument("--output-dir", default="data/knowledge")
    parser.add_argument("--mysql-host", default="localhost")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-username", default="root")
    parser.add_argument("--mysql-password", default="root")
    parser.add_argument("--mysql-schema", default="tdcomp")
    parser.add_argument("--uri", default="bolt://localhost:7687")
    parser.add_argument("--username", default="")
    parser.add_argument("--password", default="")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--wipe", action="store_true")
    parser.add_argument("--with-vector", action="store_true")
    parser.add_argument("--qdrant-host", default="localhost")
    parser.add_argument("--qdrant-port", type=int, default=6333)
    parser.add_argument("--qdrant-collection", default="enterprise_knowledge_v1")
    parser.add_argument("--embedding-provider", default="hash", choices=["hash", "minimax"])
    parser.add_argument("--embedding-model", default="MiniMax-Embedding-1")
    parser.add_argument("--embedding-dim", type=int, default=768)
    parser.add_argument("--embedding-api-url", default="https://api.minimaxi.com/v1/embeddings")
    parser.add_argument("--embedding-api-key", default="")
    parser.add_argument("--vector-batch-size", type=int, default=128)
    parser.add_argument("--recreate-vector", action="store_true")
    return parser.parse_args()


def run(cmd: list[str]) -> None:
    print("Running:", " ".join(cmd))
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        raise SystemExit(result.returncode)


def main() -> None:
    args = parse_args()
    root = Path(__file__).resolve().parents[2]
    build_script = Path(__file__).resolve().parent / "build_knowledge_from_mysql.py"
    sync_script = Path(__file__).resolve().parent / "sync_neo4j.py"
    vector_script = Path(__file__).resolve().parent / "sync_vectors_qdrant.py"
    clean_output = Path(args.output_dir) / "enterprise_mysql_clean.jsonl"
    compiled_text_output = Path(args.output_dir) / "enterprise_mysql_compiled.txt"

    build_cmd = [
        sys.executable,
        str(build_script),
        "--host",
        args.mysql_host,
        "--port",
        str(args.mysql_port),
        "--username",
        args.mysql_username,
        "--password",
        args.mysql_password,
        "--schema",
        args.mysql_schema,
        "--output-dir",
        args.output_dir,
    ]
    if args.limit > 0:
        build_cmd += ["--limit", str(args.limit)]

    sync_cmd = [
        sys.executable,
        str(sync_script),
        "--input",
        str(clean_output),
        "--uri",
        args.uri,
    ]
    if args.username:
        sync_cmd += ["--username", args.username]
    if args.password:
        sync_cmd += ["--password", args.password]
    if args.limit > 0:
        sync_cmd += ["--limit", str(args.limit)]
    if args.wipe:
        sync_cmd += ["--wipe"]

    run(build_cmd)
    run(sync_cmd)
    if args.with_vector:
        vector_cmd = [
            sys.executable,
            str(vector_script),
            "--input",
            str(clean_output),
            "--host",
            args.qdrant_host,
            "--port",
            str(args.qdrant_port),
            "--collection",
            args.qdrant_collection,
            "--embedding-provider",
            args.embedding_provider,
            "--embedding-model",
            args.embedding_model,
            "--embedding-dim",
            str(args.embedding_dim),
            "--embedding-api-url",
            args.embedding_api_url,
            "--batch-size",
            str(args.vector_batch_size),
        ]
        if args.embedding_api_key:
            vector_cmd += ["--embedding-api-key", args.embedding_api_key]
        if args.limit > 0:
            vector_cmd += ["--limit", str(args.limit)]
        if args.recreate_vector:
            vector_cmd += ["--recreate"]
        run(vector_cmd)
    print(f"Pipeline done in: {root}")
    print(f"Compiled docs: {compiled_text_output}")


if __name__ == "__main__":
    main()

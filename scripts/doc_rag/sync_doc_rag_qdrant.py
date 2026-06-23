#!/usr/bin/env python3
"""将 doc_rag 公司档案文档灌入独立 Qdrant collection（与主 enterprise_knowledge_v2 隔离）。"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

import requests

ROOT = Path(__file__).resolve().parents[2]
PIPELINE = Path(__file__).resolve().parents[1] / "enterprise_pipeline"
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(PIPELINE))

from local_config import resolve_dashscope_api_key  # noqa: E402
from sync_common import stable_point_id  # noqa: E402
from sync_vectors_qdrant import (  # noqa: E402
    build_vectors,
    collection_count,
    ensure_collection,
    read_jsonl,
    upsert_points,
)

DOC_DOMAIN = "doc_rag"
DOC_ENTITY_TYPE = "CompanyProfile"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sync doc-RAG profiles to Qdrant")
    parser.add_argument("--input", default="data/knowledge/doc_rag/company_profiles.jsonl")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=6333)
    parser.add_argument("--collection", default="enterprise_doc_rag_v1")
    parser.add_argument("--embedding-provider", default="dashscope", choices=["hash", "dashscope"])
    parser.add_argument("--embedding-model", default="text-embedding-v4")
    parser.add_argument("--embedding-dim", type=int, default=1024)
    parser.add_argument(
        "--embedding-api-url",
        default="https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding",
    )
    parser.add_argument(
        "--embedding-api-key",
        default="",
        help="默认从 DASHSCOPE_API_KEY 或 application-local.properties 读取",
    )
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--recreate", action="store_true")
    return parser.parse_args()


def payload_from_profile(row: dict[str, Any], embedding_text: str | None = None) -> dict[str, Any]:
    doc_id = str(row.get("doc_id") or row.get("company_id") or "").strip()
    profile_text = str(row.get("profile_text") or "")
    payload: dict[str, Any] = {
        "domain": DOC_DOMAIN,
        "entity_type_key": DOC_ENTITY_TYPE,
        "entity_id": doc_id,
        "company_id": doc_id,
        "company_name": row.get("company_name") or "",
        "status": row.get("status") or "",
        "registered_area": row.get("registered_area") or "",
        "text": embedding_text or profile_text,
        "raw_text": profile_text,
        "source": "doc_rag_profile_v1",
        "embedding_provider": "dashscope",
        "embedding_model": "text-embedding-v4",
    }
    return payload


def main() -> None:
    args = parse_args()
    input_path = Path(args.input)
    if not input_path.is_absolute():
        input_path = ROOT / input_path
    if not input_path.exists():
        raise SystemExit(f"Input not found: {input_path}. Run build_doc_profiles.py first.")

    rows = read_jsonl(input_path, args.limit)
    if not rows:
        raise SystemExit("No profile rows to sync.")

    args.embedding_api_key = resolve_dashscope_api_key(args.embedding_api_key)
    if args.embedding_provider == "dashscope" and not args.embedding_api_key:
        print("[warn] 未找到 DashScope 密钥，降级为 hash embedding")
        args.embedding_provider = "hash"
    elif args.embedding_provider == "dashscope":
        print("[info] 使用 DashScope text-embedding-v4 灌库")

    # 档案已是自然语言段落，直接向量化全文（avg ~570 字，max ~2500 字）
    embedding_docs = [str(r.get("profile_text") or "") for r in rows]
    vectors = build_vectors(args, embedding_docs)
    vector_size = len(vectors[0])

    base_url = f"http://{args.host}:{args.port}"
    ensure_collection(base_url, args.collection, vector_size, args.recreate)

    points: list[dict[str, Any]] = []
    for row, vector, emb_doc in zip(rows, vectors, embedding_docs):
        doc_id = str(row.get("doc_id") or row.get("company_id") or "").strip()
        if not doc_id:
            continue
        point_id = stable_point_id(DOC_DOMAIN, DOC_ENTITY_TYPE, doc_id)
        points.append(
            {
                "id": point_id,
                "vector": vector,
                "payload": payload_from_profile(row, embedding_text=emb_doc),
            }
        )
        if len(points) >= args.batch_size:
            upsert_points(base_url, args.collection, points)
            print(f"[sync] upserted {len(points)} points...")
            points.clear()
    if points:
        upsert_points(base_url, args.collection, points)

    count = collection_count(base_url, args.collection)
    print(
        json.dumps(
            {
                "collection": args.collection,
                "embedding_provider": args.embedding_provider,
                "embedding_model": args.embedding_model,
                "synced_rows": len(rows),
                "vector_size": vector_size,
                "collection_points": count,
                "qdrant": base_url,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

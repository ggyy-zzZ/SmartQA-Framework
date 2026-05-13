#!/usr/bin/env python3
"""Embed cleaned enterprise knowledge and sync vectors to Qdrant."""

from __future__ import annotations

import argparse
import hashlib
import json
import uuid
from pathlib import Path
from typing import Any

import numpy as np
import requests


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sync enterprise vectors to Qdrant")
    parser.add_argument("--input", default="data/knowledge/enterprise_mysql_clean.jsonl")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=6333)
    parser.add_argument("--collection", default="enterprise_knowledge_v1")
    parser.add_argument("--embedding-provider", default="hash", choices=["hash", "minimax"])
    parser.add_argument("--embedding-model", default="MiniMax-Embedding-1")
    parser.add_argument("--embedding-dim", type=int, default=768)
    parser.add_argument("--embedding-api-url", default="https://api.minimaxi.com/v1/embeddings")
    parser.add_argument("--embedding-api-key", default="")
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--recreate", action="store_true")
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


def build_document(row: dict[str, Any]) -> str:
    product_lines = ", ".join(
        f"{x.get('module', '')}/{x.get('line', '')}/{x.get('relation', '')}"
        for x in row.get("product_lines", [])
    )
    key_people = ", ".join(
        f"{x.get('role', '')}:{x.get('name', '')}" for x in row.get("key_people", [])
    )
    shareholders = ", ".join(
        f"{x.get('holder_name', '')}({x.get('ratio', '')})" for x in row.get("shareholders", [])
    )
    certificates = ", ".join(
        f"{x.get('cert_type', '')}:{x.get('status', '')}" for x in row.get("certificates", [])
    )
    bank_accounts = ", ".join(
        f"{x.get('account_type', '')}/{x.get('bank_name', '')}/{x.get('account_name', '')}/状态:{x.get('status', '')}"
        for x in row.get("bank_accounts", [])
    )
    directors_supervisors = ", ".join(
        f"{x.get('member_type', '')}:{x.get('member_name', '')}"
        for x in row.get("directors_supervisors", [])
    )
    certificate_people = ", ".join(
        f"类型:{x.get('cert_type', '')}/保管:{'|'.join(x.get('certification_keepers', []))}/监管:{'|'.join(x.get('supervisors', []))}/执行:{'|'.join(x.get('executors', []))}"
        for x in row.get("certificates", [])
    )
    seals = ", ".join(
        f"{x.get('seal_type', '')}/{x.get('seal_category', '')}/部门:{x.get('custody_department', '')}/人员:{'|'.join((p.get('account_name', '') for p in x.get('persons', [])))}"
        for x in row.get("seals", [])
    )

    return (
        f"公司ID: {row.get('company_id', '')}\n"
        f"公司名: {row.get('company_name', '')}\n"
        f"简称: {row.get('company_short_name', '')}\n"
        f"经营状态: {row.get('status', '')}\n"
        f"主体类型: {row.get('entity_type', '')}\n"
        f"主体分类: {row.get('entity_category', '')}\n"
        f"注册地区: {row.get('registered_area', '')}\n"
        f"注册地址: {row.get('registered_address', '')}\n"
        f"办公地址: {row.get('office_address', '')}\n"
        f"经营范围: {row.get('business_scope', '')}\n"
        f"产品线: {product_lines}\n"
        f"关键人员: {key_people}\n"
        f"股东: {shareholders}\n"
        f"证照: {certificates}\n"
        f"证照角色: {certificate_people}\n"
        f"董监高: {directors_supervisors}\n"
        f"印章: {seals}\n"
        f"银行账户: {bank_accounts}"
    )


def payload_from_row(row: dict[str, Any], doc_text: str) -> dict[str, Any]:
    return {
        "company_id": row.get("company_id"),
        "company_name": row.get("company_name"),
        "status": row.get("status"),
        "entity_type": row.get("entity_type"),
        "entity_category": row.get("entity_category"),
        "registered_area": row.get("registered_area"),
        "source_file": row.get("source_file"),
        "text": doc_text,
    }


def hash_embed_text(text: str, dim: int) -> list[float]:
    vec = np.zeros(dim, dtype=np.float32)
    for token in tokenize(text):
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        idx = int.from_bytes(digest[:4], "little") % dim
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        weight = 1.0 + (digest[5] / 255.0)
        vec[idx] += sign * weight
    norm = float(np.linalg.norm(vec))
    if norm > 0:
        vec /= norm
    return vec.tolist()


def tokenize(text: str) -> list[str]:
    # Lightweight tokenizer for mixed Chinese/English content.
    pieces: list[str] = []
    buffer = []
    for ch in text:
        if ch.isspace() or ch in ",.;:|()[]{}<>!?\"'，。；：、（）":
            if buffer:
                pieces.append("".join(buffer))
                buffer.clear()
            continue
        if "\u4e00" <= ch <= "\u9fff":
            if buffer:
                pieces.append("".join(buffer))
                buffer.clear()
            pieces.append(ch)
        else:
            buffer.append(ch.lower())
    if buffer:
        pieces.append("".join(buffer))
    return [p for p in pieces if p]


def minimax_embed_batch(
    texts: list[str],
    api_url: str,
    api_key: str,
    model: str,
    timeout: int = 60,
) -> list[list[float]]:
    if not api_key:
        raise ValueError("embedding-api-key is required for minimax provider")
    payload = {"model": model, "input": texts}
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    resp = requests.post(api_url, headers=headers, json=payload, timeout=timeout)
    resp.raise_for_status()
    data = resp.json()
    vectors = data.get("data") or []
    if not vectors:
        raise ValueError(f"Embedding API returned no data: {data}")
    return [item.get("embedding", []) for item in vectors]


def build_vectors(args: argparse.Namespace, docs: list[str]) -> list[list[float]]:
    if args.embedding_provider == "hash":
        return [hash_embed_text(doc, args.embedding_dim) for doc in docs]

    vectors: list[list[float]] = []
    for i in range(0, len(docs), args.batch_size):
        batch = docs[i : i + args.batch_size]
        batch_vec = minimax_embed_batch(
            texts=batch,
            api_url=args.embedding_api_url,
            api_key=args.embedding_api_key,
            model=args.embedding_model,
        )
        vectors.extend(batch_vec)
    return vectors


def main() -> None:
    args = parse_args()
    input_path = Path(args.input)
    if not input_path.exists():
        raise SystemExit(f"Input JSONL not found: {input_path}")

    rows = read_jsonl(input_path, args.limit)
    if not rows:
        raise SystemExit("No rows found for vector sync.")

    docs = [build_document(r) for r in rows]
    vectors = build_vectors(args, docs)
    vector_size = len(vectors[0])

    base_url = f"http://{args.host}:{args.port}"
    ensure_collection(base_url, args.collection, vector_size, args.recreate)

    points: list[dict[str, Any]] = []
    for row, vector, doc in zip(rows, vectors, docs):
        points.append(
            {
                "id": str(uuid.uuid4()),
                "vector": vector,
                "payload": payload_from_row(row, doc),
            }
        )
        if len(points) >= args.batch_size:
            upsert_points(base_url, args.collection, points)
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


def ensure_collection(base_url: str, collection: str, vector_size: int, recreate: bool) -> None:
    if recreate:
        requests.delete(f"{base_url}/collections/{collection}", timeout=30)
    resp = requests.get(f"{base_url}/collections/{collection}", timeout=30)
    if resp.status_code == 200:
        return
    payload = {
        "vectors": {
            "size": vector_size,
            "distance": "Cosine",
        }
    }
    create_resp = requests.put(
        f"{base_url}/collections/{collection}",
        json=payload,
        timeout=30,
    )
    create_resp.raise_for_status()


def upsert_points(base_url: str, collection: str, points: list[dict[str, Any]]) -> None:
    resp = requests.put(
        f"{base_url}/collections/{collection}/points?wait=true",
        json={"points": points},
        timeout=120,
    )
    resp.raise_for_status()


def collection_count(base_url: str, collection: str) -> int:
    resp = requests.post(
        f"{base_url}/collections/{collection}/points/count",
        json={"exact": True},
        timeout=30,
    )
    resp.raise_for_status()
    data = resp.json()
    return int(data.get("result", {}).get("count", 0))


if __name__ == "__main__":
    main()

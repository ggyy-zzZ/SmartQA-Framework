#!/usr/bin/env python3
"""Embed cleaned enterprise knowledge and sync vectors to Qdrant."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any

import numpy as np
import requests

from sync_common import DEFAULT_DOMAIN, DEFAULT_ENTITY_TYPE, stable_point_id

from _embedding_text_rewriter import (
    default_llm_config,
    rewrite_batch as llm_rewrite_batch,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sync enterprise vectors to Qdrant")
    parser.add_argument("--input", default="data/knowledge/enterprise_mysql_clean.jsonl")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=6333)
    parser.add_argument(
        "--embedding-provider",
        default="dashscope",
        choices=["hash", "minimax", "dashscope"],
    )
    parser.add_argument("--embedding-model", default="text-embedding-v4")
    parser.add_argument("--embedding-dim", type=int, default=1024)
    parser.add_argument(
        "--embedding-api-url",
        default="https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding",
    )
    parser.add_argument(
        "--embedding-api-key",
        default=os.environ.get("DASHSCOPE_API_KEY", ""),
    )
    parser.add_argument("--collection", default="enterprise_knowledge_v2")
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--recreate", action="store_true")
    # LLM 改写 embedding_text（默认开启；与 application.properties#qa.assistant.embedding-text-mode 同步）
    llm_default = default_llm_config()
    parser.add_argument(
        "--llm-mode",
        default="rewrite",
        choices=["none", "rewrite"],
        help="none=用 build_document 拼接；rewrite=先调 LLM 改写为通顺段落（降级回 none）",
    )
    parser.add_argument(
        "--llm-api-url", default=llm_default["api_url"]
    )
    parser.add_argument(
        "--llm-api-key", default=llm_default["api_key"]
    )
    parser.add_argument(
        "--llm-model", default=llm_default["model"]
    )
    parser.add_argument(
        "--llm-workers", type=int, default=4, help="LLM 改写并发线程数"
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


def build_document(row: dict[str, Any]) -> str:
    product_lines = ", ".join(
        f"{x.get('module', '')}/{x.get('line', '')}/{x.get('relation', '')}"
        for x in row.get("product_lines", [])
    )
    key_people = ", ".join(
        f"{x.get('role', '')}:{x.get('name', '')}" for x in row.get("key_people", [])
    )
    legal_representatives = ", ".join(
        x.get("name", "")
        for x in row.get("key_people", [])
        if "法定代表" in str(x.get("role", ""))
    )
    shareholders = ", ".join(
        f"{x.get('holder_name', '')}({x.get('ratio', '')})" for x in row.get("shareholders", [])
    )
    certificates = ", ".join(
        f"{x.get('cert_type', '')}:{x.get('status', '')}"
        + (f"/编号:{x.get('code')}" if x.get("code") else "")
        + (f"/至:{x.get('expire_date')}" if x.get("expire_date") else "")
        for x in row.get("certificates", [])
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
        f"统一社会信用代码: {row.get('credit_code', '')}\n"
        f"经营状态: {row.get('status', '')}\n"
        f"主体类型: {row.get('entity_type', '')}\n"
        f"主体分类: {row.get('entity_category', '')}\n"
        f"成立日期: {row.get('established_date', '')}\n"
        f"注册地区: {row.get('registered_area', '')}\n"
        f"母公司: {row.get('parent_company', '')}\n"
        f"注册地址: {row.get('registered_address', '')}\n"
        f"办公地址: {row.get('office_address', '')}\n"
        f"经营范围: {row.get('business_scope', '')}\n"
        f"产品线: {product_lines}\n"
        f"法定代表人: {legal_representatives}\n"
        f"关键人员: {key_people}\n"
        f"股东: {shareholders}\n"
        f"证照: {certificates}\n"
        f"证照角色: {certificate_people}\n"
        f"董监高: {directors_supervisors}\n"
        f"印章: {seals}\n"
        f"银行账户: {bank_accounts}"
    )


def _coerce_codes(value: Any) -> list[str]:
    """归一化 registeredAreaCode/officeAreaCode 字段为 List[str]。

    支持：None / 单字符串 / List[str] / List[int] / 逗号/顿号分隔字符串。
    """
    if value is None:
        return []
    if isinstance(value, str):
        s = value.strip()
        if not s:
            return []
        # 逗号、顿号、分号分割
        for sep in [",", "，", "、", ";"]:
            if sep in s:
                return [t.strip() for t in s.split(sep) if t.strip()]
        return [s]
    if isinstance(value, (list, tuple, set)):
        out: list[str] = []
        for v in value:
            if v is None:
                continue
            s = str(v).strip()
            if s:
                out.append(s)
        return out
    s = str(value).strip()
    return [s] if s else []


def payload_from_row(
    row: dict[str, Any],
    doc_text: str,
    embedding_text: str | None = None,
    embedding_text_version: str | None = None,
) -> dict[str, Any]:
    company_id = row.get("company_id")
    # 解析 company_graph_props（Python ETL 写入的 GB/T 2260 codes）
    gprops = row.get("company_graph_props") or {}
    registered_codes = _coerce_codes(gprops.get("registeredAreaCode") or row.get("registered_area_code"))
    office_codes = _coerce_codes(gprops.get("officeAreaCode") or row.get("office_area_code"))
    # embedding_text 默认 = 结构化拼接版（与原行为一致）；若 LLM 改写成功则用改写后版本
    final_embedding_text = embedding_text or doc_text
    payload: dict[str, Any] = {
        "domain": row.get("domain") or DEFAULT_DOMAIN,
        "entity_type_key": DEFAULT_ENTITY_TYPE,
        "entity_id": str(company_id) if company_id is not None else "",
        "company_id": company_id,
        "company_name": row.get("company_name"),
        "status": row.get("status"),
        "entity_type": row.get("entity_type"),
        "entity_category": row.get("entity_category"),
        "registered_area": row.get("registered_area"),
        "office_area": row.get("office_address"),
        "registeredAreaCode": registered_codes,
        "officeAreaCode": office_codes,
        "source_file": row.get("source_file"),
        "text": final_embedding_text,
        "raw_text": doc_text,
    }
    if embedding_text and embedding_text_version:
        payload["embedding_text_version"] = embedding_text_version
    return payload


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


def dashscope_embed_batch(
    texts: list[str],
    api_url: str,
    api_key: str,
    model: str,
    dimension: int,
    timeout: int = 60,
) -> list[list[float]]:
    if not api_key:
        raise ValueError("embedding-api-key is required for dashscope provider")
    payload = {
        "model": model,
        "input": {"texts": texts},
        "parameters": {"dimension": dimension, "output_type": "dense"},
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    resp = requests.post(api_url, headers=headers, json=payload, timeout=timeout)
    resp.raise_for_status()
    data = resp.json()
    if data.get("code"):
        raise ValueError(f"DashScope error: {data}")
    embeddings = (data.get("output") or {}).get("embeddings") or []
    if not embeddings:
        raise ValueError(f"DashScope returned no embeddings: {data}")
    ordered = sorted(embeddings, key=lambda x: x.get("text_index", 0))
    return [item.get("embedding", []) for item in ordered]


def build_vectors(args: argparse.Namespace, docs: list[str]) -> list[list[float]]:
    if args.embedding_provider == "hash":
        return [hash_embed_text(doc, args.embedding_dim) for doc in docs]

    api_batch = 10 if args.embedding_provider == "dashscope" else args.batch_size
    vectors: list[list[float]] = []
    for i in range(0, len(docs), api_batch):
        batch = docs[i : i + api_batch]
        if args.embedding_provider == "dashscope":
            batch_vec = dashscope_embed_batch(
                texts=batch,
                api_url=args.embedding_api_url,
                api_key=args.embedding_api_key,
                model=args.embedding_model,
                dimension=args.embedding_dim,
            )
        else:
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

    # 1) 结构化拼接版（保留为 raw_text，可读；与历史行为一致）
    raw_docs = [build_document(r) for r in rows]

    # 2) LLM 改写：把结构化字段 → 通顺中文段落，用于 embedding 召回
    embedding_docs: list[str] = list(raw_docs)
    embedding_version: str | None = None
    if args.llm_mode == "rewrite":
        print(f"[rewrite] calling LLM {args.llm_model} via {args.llm_api_url} "
              f"workers={args.llm_workers} rows={len(rows)}")
        rewritten = llm_rewrite_batch(
            rows=rows,
            api_url=args.llm_api_url,
            api_key=args.llm_api_key,
            model=args.llm_model,
            workers=args.llm_workers,
        )
        success = sum(1 for t in rewritten if t)
        failed = len(rewritten) - success
        print(f"[rewrite] done: success={success} failed={failed} (降级到结构化拼接)")
        # 失败的回退到 raw_docs
        for i, t in enumerate(rewritten):
            if t:
                embedding_docs[i] = t
        if success > 0:
            embedding_version = "llm-rewrite-v1"

    vectors = build_vectors(args, embedding_docs)
    vector_size = len(vectors[0])

    base_url = f"http://{args.host}:{args.port}"
    ensure_collection(base_url, args.collection, vector_size, args.recreate)

    points: list[dict[str, Any]] = []
    for row, vector, raw_doc, emb_doc in zip(rows, vectors, raw_docs, embedding_docs):
        company_id = str(row.get("company_id") or "").strip()
        if not company_id:
            continue
        domain = str(row.get("domain") or DEFAULT_DOMAIN)
        point_id = stable_point_id(domain, DEFAULT_ENTITY_TYPE, company_id)
        # 仅当 LLM 改写成功时把 embedding_text 写为改写版；否则回退到 raw（=无 LLM 版）
        used_emb = emb_doc if emb_doc != raw_doc else None
        points.append(
            {
                "id": point_id,
                "vector": vector,
                "payload": payload_from_row(
                    row,
                    doc_text=raw_doc,
                    embedding_text=used_emb,
                    embedding_text_version=embedding_version,
                ),
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
                "llm_mode": args.llm_mode,
                "embedding_text_version": embedding_version,
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

#!/usr/bin/env python3
"""Query enterprise vectors from Qdrant using the same embedding method."""

from __future__ import annotations

import argparse
import hashlib
import json

import numpy as np
import requests


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Query vectors from Qdrant")
    parser.add_argument("--query", required=True, help="Natural language query")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=6333)
    parser.add_argument("--collection", default="enterprise_knowledge_v1")
    parser.add_argument("--embedding-provider", default="hash", choices=["hash", "minimax"])
    parser.add_argument("--embedding-model", default="MiniMax-Embedding-1")
    parser.add_argument("--embedding-dim", type=int, default=768)
    parser.add_argument("--embedding-api-url", default="https://api.minimaxi.com/v1/embeddings")
    parser.add_argument("--embedding-api-key", default="")
    parser.add_argument("--top-k", type=int, default=5)
    return parser.parse_args()


def tokenize(text: str) -> list[str]:
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


def minimax_embed_text(
    text: str,
    api_url: str,
    api_key: str,
    model: str,
    timeout: int = 60,
) -> list[float]:
    if not api_key:
        raise ValueError("embedding-api-key is required for minimax provider")
    payload = {"model": model, "input": [text]}
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
    return vectors[0].get("embedding", [])


def build_query_vector(args: argparse.Namespace) -> list[float]:
    if args.embedding_provider == "hash":
        return hash_embed_text(args.query, args.embedding_dim)
    return minimax_embed_text(
        text=args.query,
        api_url=args.embedding_api_url,
        api_key=args.embedding_api_key,
        model=args.embedding_model,
    )


def main() -> None:
    args = parse_args()
    query_vector = build_query_vector(args)
    base_url = f"http://{args.host}:{args.port}"

    search_resp = requests.post(
        f"{base_url}/collections/{args.collection}/points/search",
        json={
            "vector": query_vector,
            "limit": max(1, min(args.top_k, 20)),
            "with_payload": True,
            "with_vector": False,
        },
        timeout=60,
    )
    search_resp.raise_for_status()
    hits = search_resp.json().get("result", [])

    result = []
    for item in hits:
        payload = item.get("payload") or {}
        result.append(
            {
                "score": item.get("score"),
                "company_id": payload.get("company_id"),
                "company_name": payload.get("company_name"),
                "status": payload.get("status"),
                "text_preview": (payload.get("text", "") or "")[:220],
            }
        )

    print(
        json.dumps(
            {
                "query": args.query,
                "collection": args.collection,
                "embedding_provider": args.embedding_provider,
                "embedding_model": args.embedding_model,
                "top_k": args.top_k,
                "hits": result,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

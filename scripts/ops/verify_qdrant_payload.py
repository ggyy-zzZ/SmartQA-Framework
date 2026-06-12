"""验证 Qdrant 灌库后 payload 字段完整性。

用法：
  python scripts/ops/verify_qdrant_payload.py [--host localhost] [--port 6333] [--collection X] [--sample 30]
"""
from __future__ import annotations

import argparse
import json
from collections import Counter

import requests


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Verify Qdrant payload field fill rate")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=6333)
    p.add_argument("--collection", default="enterprise_knowledge_v2")
    p.add_argument("--sample", type=int, default=30, help="number of points to scroll for sampling")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    base = f"http://{args.host}:{args.port}"
    # Collection count
    cnt = requests.post(
        f"{base}/collections/{args.collection}/points/count",
        json={"exact": True},
        timeout=30,
    ).json().get("result", {}).get("count", 0)
    print(f"[verify] collection={args.collection} total_points={cnt}")

    # Scroll sample
    scroll = requests.post(
        f"{base}/collections/{args.collection}/points/scroll",
        json={"limit": args.sample, "with_payload": True, "with_vector": False},
        timeout=30,
    ).json().get("result", {}).get("points", [])
    print(f"[verify] sampled {len(scroll)} points")

    field_filled: Counter[str] = Counter()
    text_lens: list[int] = []
    raw_lens: list[int] = []
    rewrite_seen = 0
    for p in scroll:
        pl = p.get("payload", {}) or {}
        for f in [
            "company_id", "company_name", "status", "entity_type",
            "registeredAreaCode", "officeAreaCode", "text", "raw_text",
            "embedding_text_version",
        ]:
            v = pl.get(f)
            if v is None:
                continue
            if isinstance(v, str) and not v.strip():
                continue
            if isinstance(v, (list, tuple)) and len(v) == 0:
                continue
            field_filled[f] += 1
        if pl.get("text"):
            text_lens.append(len(pl["text"]))
        if pl.get("raw_text"):
            raw_lens.append(len(pl["raw_text"]))
        if pl.get("embedding_text_version"):
            rewrite_seen += 1

    report = {
        "collection_total": cnt,
        "sample_size": len(scroll),
        "field_filled": dict(field_filled),
        "rewrite_version_seen": rewrite_seen,
        "text_len": {
            "min": min(text_lens) if text_lens else 0,
            "max": max(text_lens) if text_lens else 0,
            "avg": (sum(text_lens) // len(text_lens)) if text_lens else 0,
        },
        "raw_text_len": {
            "min": min(raw_lens) if raw_lens else 0,
            "max": max(raw_lens) if raw_lens else 0,
            "avg": (sum(raw_lens) // len(raw_lens)) if raw_lens else 0,
        },
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))

    # 验收：registeredAreaCode + officeAreaCode + text 三个字段都应 100% 填充
    for f in ["registeredAreaCode", "officeAreaCode", "text", "company_name"]:
        rate = field_filled.get(f, 0) / max(1, len(scroll))
        status = "OK" if rate >= 0.95 else "WARN"
        print(f"[{status}] field={f} fill_rate={rate:.1%}")
    if rewrite_seen == len(scroll):
        print("[OK] all sampled points carry embedding_text_version (LLM rewrite succeeded)")
    elif rewrite_seen == 0:
        print("[WARN] no points carry embedding_text_version (LLM rewrite may have failed; falling back to raw)")
    else:
        print(f"[INFO] partial rewrite: {rewrite_seen}/{len(scroll)} points LLM-rewritten")


if __name__ == "__main__":
    main()

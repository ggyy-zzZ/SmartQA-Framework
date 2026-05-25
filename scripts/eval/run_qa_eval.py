#!/usr/bin/env python3
"""离线调用 /qa/ask，输出评测 CSV（需应用已启动）。"""

from __future__ import annotations

import argparse
import csv
import json
from datetime import datetime
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--cases", default="data/eval/qa_cases.jsonl")
    p.add_argument("--output", default="")
    return p.parse_args()


def load_cases(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def hit_source(evidence: list, must: list) -> bool:
    if not must:
        return True
    sources = {e.get("source", "") for e in evidence if isinstance(e, dict)}
    return any(any(m in s for s in sources) for m in must)


def main() -> None:
    args = parse_args()
    cases_path = ROOT / args.cases
    cases = load_cases(cases_path)
    out = args.output or str(
        ROOT / "data/eval" / f"baseline_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
    )
    out_path = Path(out)

    fieldnames = [
        "id",
        "question",
        "intent",
        "route",
        "canAnswer",
        "evidenceCount",
        "lowOverlap",
        "answerGateRejectReason",
        "embeddingProvider",
        "retrievalSource",
        "rerankProvider",
        "unifiedRetrieval",
        "mustHitOk",
        "rejectOk",
        "notes",
    ]
    rows_out = []
    for case in cases:
        resp = requests.post(
            f"{args.base_url}/qa/ask",
            json={
                "question": case["question"],
                "scope": case.get("scope", "enterprise"),
            },
            timeout=120,
        )
        resp.raise_for_status()
        body = resp.json()
        evidence = body.get("evidence") or []
        align = body.get("evidenceAlignment") or {}
        must_ok = hit_source(evidence, case.get("mustHitSource") or [])
        should_reject = bool(case.get("shouldReject"))
        can_answer = bool(body.get("canAnswer"))
        reject_ok = (not can_answer) if should_reject else True
        rows_out.append(
            {
                "id": case.get("id"),
                "question": case["question"],
                "intent": body.get("intent"),
                "route": body.get("route"),
                "canAnswer": can_answer,
                "evidenceCount": len(evidence),
                "lowOverlap": align.get("lowOverlap"),
                "answerGateRejectReason": body.get("answerGateRejectReason", ""),
                "embeddingProvider": body.get("embeddingProvider", ""),
                "retrievalSource": body.get("retrievalSource", ""),
                "rerankProvider": body.get("rerankProvider", ""),
                "unifiedRetrieval": body.get("unifiedRetrieval", ""),
                "mustHitOk": must_ok,
                "rejectOk": reject_ok,
                "notes": case.get("notes", ""),
            }
        )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows_out)

    total = len(rows_out)
    must_pass = sum(1 for r in rows_out if r["mustHitOk"])
    reject_pass = sum(1 for r in rows_out if r["rejectOk"])
    print(
        json.dumps(
            {
                "cases": total,
                "mustHitPass": must_pass,
                "rejectPass": reject_pass,
                "output": str(out_path.relative_to(ROOT)),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

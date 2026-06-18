#!/usr/bin/env python3
"""Publish classpath QA configs to /qa/admin/config/publish (UTF-8 safe)."""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

CONFIGS = {
    "business-rules": "src/main/resources/qa/business-rules.json",
    "retrieval-catalog": "src/main/resources/qa/retrieval-catalog.json",
    "cdc-graph-sync": "src/main/resources/qa/cdc-graph-sync.json",
    "evidence-schemas": "src/main/resources/qa/evidence-schemas.json",
    "answer-output-contracts": "src/main/resources/qa/answer-output-contracts.json",
    "enterprise-lexicon": "src/main/resources/qa/enterprise-lexicon.json",
    "graph-company-facets": "src/main/resources/qa/graph-company-facets.json",
    "sql-role-columns": "src/main/resources/qa/sql-role-columns.json",
    "enterprise-canonical-facts": "src/main/resources/qa/enterprise-canonical-facts.json",
}


def publish(base_url: str, repo_root: Path) -> int:
    failed = 0
    for key, rel in CONFIGS.items():
        path = repo_root / rel
        content = path.read_text(encoding="utf-8")
        payload = json.dumps({"configKey": key, "contentJson": content}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            f"{base_url.rstrip('/')}/qa/admin/config/publish",
            data=payload,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            if not body.get("ok"):
                print(f"FAIL {key}: {body}", file=sys.stderr)
                failed += 1
            else:
                print(f"OK   {key} hash={body.get('contentHash', '')[:12]}...")
        except urllib.error.HTTPError as ex:
            raw = ex.read().decode("utf-8", errors="replace")
            print(f"HTTP {ex.code} {key}: {raw}", file=sys.stderr)
            failed += 1
    return failed


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    base = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
    sys.exit(1 if publish(base, root) else 0)

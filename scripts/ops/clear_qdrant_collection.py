#!/usr/bin/env python
"""DELETE 单个 Qdrant collection。404 视为已空。"""
import argparse
import os
import sys

import requests


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--collection", required=True)
    ap.add_argument("--qdrant-host", default=os.environ.get("QDRANT_HOST", "localhost"))
    ap.add_argument("--qdrant-port", default=os.environ.get("QDRANT_PORT", "6333"))
    args = ap.parse_args()
    url = f"http://{args.qdrant_host}:{args.qdrant_port}/collections/{args.collection}"
    print(f"[qdrant] DELETE {url}")
    try:
        r = requests.delete(url, timeout=15)
    except requests.RequestException as e:
        print(f"[qdrant] FAILED: {e}", file=sys.stderr)
        sys.exit(1)
    if r.status_code in (200, 202, 404):
        print(f"[qdrant] cleared {args.collection} (status={r.status_code})")
        return
    print(f"[qdrant] FAILED status={r.status_code} body={r.text[:200]}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()

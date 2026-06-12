#!/usr/bin/env python
"""
Fix region-dictionary.json: replace flat `reverse` (name -> code, last-write-wins)
with `reverseByName` (name -> [code,...]) so resolvers can disambiguate by
context (e.g. parent province name appearing in same string).

Input:  qa/region-dictionary.json
Output: qa/region-dictionary.json (in-place fix; preserves _meta, codes, aliases)

向后兼容：保留 `reverse` 字段（取每个 name 的第一个 code），同时新增
`reverseByName`（name -> list[code]），并提供 `codesByName` (name -> 全部
含 (code, level, parent) 的列表) 供 Java 端按"父级"消歧。

注意：本脚本不会清空或修改 codes / aliases / _meta。
"""
from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from datetime import datetime

DICT_PATH = r"D:\project\ai\demo\src\main\resources\qa\region-dictionary.json"


def main() -> int:
    if not os.path.exists(DICT_PATH):
        print(f"[error] not found: {DICT_PATH}", file=sys.stderr)
        return 1

    with open(DICT_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    codes: dict = data.get("codes") or {}
    reverse: dict = data.get("reverse") or {}

    # 1) 反查 codes（不依赖 reverse）拿到 name → [(code, level, parent, path), ...]
    by_name_full: dict[str, list[dict]] = defaultdict(list)
    for code, entry in codes.items():
        name = entry.get("name") or ""
        if not name:
            continue
        by_name_full[name].append(
            {
                "code": code,
                "level": entry.get("level", 0),
                "parent": entry.get("parent", ""),
                "path": entry.get("path", ""),
            }
        )

    # 2) reverseByName：name → list[code]（按 code 升序）
    reverse_by_name: dict[str, list[str]] = {
        name: sorted([e["code"] for e in entries]) for name, entries in by_name_full.items()
    }
    # 同名 code 数统计
    multi = {n: c for n, c in reverse_by_name.items() if len(c) > 1}
    if multi:
        print(f"[info] ambiguous names (multiple codes): {len(multi)}")
        for n, cs in list(multi.items())[:5]:
            print(f"       {n!r} -> {cs}")

    # 3) 新的 reverse：保留兼容性，但每个 name 优先取「最具体的 code（位数最多）」
    reverse_new: dict[str, str] = {}
    for name, entries in by_name_full.items():
        # 优先选 6-digit（district） > 4-digit（city） > 2-digit（province）
        sorted_entries = sorted(entries, key=lambda e: (-len(e["code"]), e["code"]))
        reverse_new[name] = sorted_entries[0]["code"]

    # 4) codesByName：name → list[{code, level, parent}]
    codes_by_name: dict[str, list[dict]] = {
        name: sorted(entries, key=lambda e: e["code"]) for name, entries in by_name_full.items()
    }

    # 5) parents：code → parent name（基于 codes）
    parents: dict[str, str] = {
        code: entry.get("parent", "") for code, entry in codes.items() if entry.get("parent")
    }

    data["reverse"] = reverse_new
    data["reverseByName"] = reverse_by_name
    data["codesByName"] = codes_by_name
    data["parents"] = parents
    data.setdefault("_meta", {})
    data["_meta"]["fixedAt"] = datetime.now().isoformat(timespec="seconds")
    data["_meta"]["ambiguousNameCount"] = len(multi)
    data["_meta"]["totalCodes"] = len(codes)
    data["_meta"]["totalReverseByName"] = len(reverse_by_name)

    with open(DICT_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    size = os.path.getsize(DICT_PATH)
    print(f"[ok] wrote {DICT_PATH} ({size:,} bytes)")
    print(f"     reverse         : {len(reverse_new)} entries (flat, prefer district)")
    print(f"     reverseByName   : {len(reverse_by_name)} entries (multi-code aware)")
    print(f"     codesByName     : {len(codes_by_name)} entries")
    print(f"     parents         : {len(parents)} entries")
    print(f"     ambiguous names : {len(multi)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

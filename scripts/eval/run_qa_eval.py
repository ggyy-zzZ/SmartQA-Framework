#!/usr/bin/env python3
"""
批量回放 data/eval/qa_cases.jsonl，对运行中的 /qa/ask 做断言并输出 CSV。

用法:
  python scripts/eval/run_qa_eval.py
  python scripts/eval/run_qa_eval.py --base-url http://localhost:8089 --cases data/eval/qa_cases.jsonl
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases


def ask(base_url: str, question: str, conversation_id: str | None, follow_up: bool) -> dict[str, Any]:
    payload: dict[str, Any] = {"question": question, "scope": "enterprise", "followUp": follow_up}
    if conversation_id:
        payload["conversationId"] = conversation_id
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{base_url.rstrip('/')}/qa/ask",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as ex:
        # 4xx/5xx：把 body 读成 JSON 一起返回，方便 run_case 看到 errorMessage
        raw = ex.read().decode("utf-8", errors="replace") if ex.fp else ""
        try:
            body_json = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            body_json = {"error": "non_json_body", "message": raw}
        return {
            "_http_status": ex.code,
            "_http_error": True,
            "error": body_json.get("error", f"HTTP {ex.code}"),
            "message": body_json.get("message", ex.reason or ""),
            "question": question,
        }


def check_expect(case: dict[str, Any], response: dict[str, Any]) -> tuple[bool, list[str]]:
    expect = case.get("expect") or {}
    failures: list[str] = []
    routing = response.get("routing") or {}

    if "queryType" in expect:
        actual = response.get("queryType") or routing.get("queryType") or ""
        if actual != expect["queryType"]:
            failures.append(f"queryType: want={expect['queryType']} got={actual}")

    for key, routing_key in (
        ("needGranularity", "needGranularity"),
        ("needFacet", "needFacet"),
    ):
        if key in expect:
            actual = routing.get(routing_key, "")
            if actual != expect[key]:
                failures.append(f"{routing_key}: want={expect[key]} got={actual}")

    if "retrievalSourceContains" in expect:
        src = str(response.get("retrievalSource") or "")
        if expect["retrievalSourceContains"] not in src:
            failures.append(f"retrievalSource missing {expect['retrievalSourceContains']}: {src}")

    if "canAnswer" in expect:
        actual = bool(response.get("canAnswer"))
        if actual != expect["canAnswer"]:
            failures.append(f"canAnswer: want={expect['canAnswer']} got={actual}")

    if "minEvidenceCount" in expect:
        evidence = response.get("evidence") or []
        if len(evidence) < int(expect["minEvidenceCount"]):
            failures.append(f"evidenceCount: want>={expect['minEvidenceCount']} got={len(evidence)}")

    if "evidenceSchemas" in expect:
        schemas = {
            (e.get("evidenceSchema") or "")
            for e in (response.get("evidence") or [])
            if isinstance(e, dict)
        }
        for schema in expect["evidenceSchemas"]:
            if schema not in schemas:
                failures.append(f"missing evidenceSchema={schema}")

    if "followUpApplied" in expect:
        actual = bool(response.get("followUpApplied"))
        if actual != expect["followUpApplied"]:
            failures.append(f"followUpApplied: want={expect['followUpApplied']} got={actual}")

    if "routeContains" in expect:
        route = str(response.get("route") or "")
        if expect["routeContains"] not in route:
            failures.append(f"route missing {expect['routeContains']}: {route}")

    if "evidenceContains" in expect:
        snippets = " ".join(
            str((e.get("snippet") or "")) for e in (response.get("evidence") or []) if isinstance(e, dict)
        )
        for token in expect["evidenceContains"]:
            if token not in snippets:
                failures.append(f"evidence missing token={token}")

    return len(failures) == 0, failures


def run_case(base_url: str, case: dict[str, Any]) -> dict[str, Any]:
    conv_id: str | None = None
    prior = case.get("priorTurns") or []
    for turn in prior:
        try:
            seed = ask(base_url, turn["question"], conv_id, follow_up=conv_id is not None)
        except urllib.error.URLError as ex:
            return {
                "id": case.get("id", ""),
                "question": case.get("question", ""),
                "pass": False,
                "failures": f"prior turn network error: {ex}",
                "route": "",
                "retrievalSource": "",
                "queryType": "",
                "canAnswer": "",
                "evidenceCount": 0,
                "errorMessage": str(ex),
            }
        conv_id = seed.get("conversationId") if not seed.get("_http_error") else None
    try:
        response = ask(base_url, case["question"], conv_id, follow_up=conv_id is not None)
    except urllib.error.URLError as ex:
        return {
            "id": case.get("id", ""),
            "question": case.get("question", ""),
            "pass": False,
            "failures": f"network error: {ex}",
            "route": "",
            "retrievalSource": "",
            "queryType": "",
            "canAnswer": "",
            "evidenceCount": 0,
            "errorMessage": str(ex),
        }
    is_http_error = response.get("_http_error", False)
    if is_http_error:
        # 4xx/5xx：直接把异常信息当 failures
        status = response.get("_http_status", 0)
        err_msg = response.get("message", "") or response.get("error", "")
        failures = [f"http_{status}: {err_msg}"]
        return {
            "id": case.get("id", ""),
            "question": case.get("question", ""),
            "pass": False,
            "failures": "; ".join(failures),
            "route": f"http_{status}",
            "retrievalSource": "",
            "queryType": "",
            "canAnswer": False,
            "evidenceCount": 0,
            "errorMessage": err_msg,
        }
    ok, failures = check_expect(case, response)
    return {
        "id": case.get("id", ""),
        "question": case.get("question", ""),
        "pass": ok,
        "failures": "; ".join(failures),
        "route": response.get("route", ""),
        "retrievalSource": response.get("retrievalSource", ""),
        "queryType": response.get("queryType", ""),
        "canAnswer": response.get("canAnswer", ""),
        "evidenceCount": len(response.get("evidence") or []),
        "errorMessage": "",
    }


def load_baseline(path: Path) -> dict[str, bool]:
    """读取 baseline CSV，返回 {id: pass}。文件不存在或读失败返回空 dict。"""
    if not path.exists():
        return {}
    out: dict[str, bool] = {}
    try:
        with path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for r in reader:
                rid = r.get("id", "").strip()
                if rid:
                    out[rid] = r.get("pass", "False") in ("True", "true", "1")
    except Exception as ex:
        print(f"[baseline] failed to read {path}: {ex}", file=sys.stderr)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Replay QA eval cases against /qa/ask")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", default="data/eval/qa_cases.jsonl")
    parser.add_argument("--out", default="data/eval/qa_eval_results.csv")
    parser.add_argument(
        "--tags",
        default="",
        help="逗号分隔，仅运行 tags 与其中任一项 intersect 的用例（如 section4,type_catalog）",
    )
    parser.add_argument(
        "--fail-under",
        type=float,
        default=None,
        help="通过率下限（百分数，如 80）。低于此值退出码=3（门禁触发）",
    )
    parser.add_argument(
        "--must-pass",
        default="",
        help="逗号分隔的 case id，这些 case 任何一条 fail 退出码=4",
    )
    parser.add_argument(
        "--baseline",
        default="",
        help="baseline CSV 路径；与该文件比对 regressed/improved，有 regressed 退出码=2",
    )
    args = parser.parse_args()

    tag_filter = {t.strip() for t in args.tags.split(",") if t.strip()}
    must_pass_ids = {t.strip() for t in args.must_pass.split(",") if t.strip()}

    root = Path(__file__).resolve().parents[2]
    cases_path = root / args.cases
    if not cases_path.exists():
        print(f"cases file not found: {cases_path}", file=sys.stderr)
        return 1

    cases = load_cases(cases_path)
    if tag_filter:
        cases = [c for c in cases if tag_filter.intersection(set(c.get("tags") or []))]
        if not cases:
            print(f"no cases match tags={sorted(tag_filter)}", file=sys.stderr)
            return 1

    baseline: dict[str, bool] = {}
    if args.baseline:
        baseline = load_baseline(root / args.baseline)

    rows: list[dict[str, Any]] = []
    passed = 0
    for case in cases:
        try:
            row = run_case(args.base_url, case)
        except urllib.error.URLError as ex:
            row = {
                "id": case.get("id", ""),
                "question": case.get("question", ""),
                "pass": False,
                "failures": str(ex),
                "route": "",
                "retrievalSource": "",
                "queryType": "",
                "canAnswer": "",
                "evidenceCount": 0,
                "errorMessage": str(ex),
            }
        # baseline diff
        rid = row.get("id", "")
        if rid in baseline:
            row["regressed"] = bool(baseline[rid] and not row["pass"])
            row["improved"] = bool(not baseline[rid] and row["pass"])
        else:
            row["regressed"] = ""
            row["improved"] = ""
        if row["pass"]:
            passed += 1
        rows.append(row)
        status = "PASS" if row["pass"] else "FAIL"
        marker = ""
        if row.get("regressed") is True:
            marker += " [REGRESSED]"
        if row.get("improved") is True:
            marker += " [IMPROVED]"
        print(f"[{status}] {row['id']}: {row.get('failures') or 'ok'}{marker}")

    out_path = root / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = ["id", "question", "pass", "failures", "route", "retrievalSource",
                  "queryType", "canAnswer", "evidenceCount", "errorMessage",
                  "regressed", "improved"]
    with out_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)

    rate = (passed / len(rows) * 100) if rows else 0.0
    print(f"\n{passed}/{len(rows)} passed ({rate:.1f}%) -> {out_path}")

    # 退出码优先级：must-pass(4) > fail-under(3) > regressed(2) > all-pass(0)
    regressed_rows = [r for r in rows if r.get("regressed") is True]
    if regressed_rows:
        print(f"[baseline] regressed cases ({len(regressed_rows)}):")
        for r in regressed_rows:
            print(f"  - {r['id']}")
        return 2
    if must_pass_ids:
        must_failed = [r for r in rows if r["id"] in must_pass_ids and not r["pass"]]
        if must_failed:
            print(f"[must-pass] failed ({len(must_failed)}):")
            for r in must_failed:
                print(f"  - {r['id']}: {r.get('failures', '')}")
            return 4
    if args.fail_under is not None and rate < args.fail_under:
        print(f"[fail-under] {rate:.1f}% < {args.fail_under}% (门禁触发)")
        return 3
    return 0 if passed == len(rows) else 2


if __name__ == "__main__":
    raise SystemExit(main())

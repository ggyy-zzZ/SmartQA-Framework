"""P0-S5：gate_metrics 日报脚本。

读取 data/qa_logs/gate_metrics.jsonl，按天聚合输出 docs/qa-metrics/YYYY-MM-DD.md。
字段来自 GateMetricsWriter（ts / questionType / intent / evidenceCount / canAnswer /
confidence / route / rejectReason / latencyMs）。

CLI：
    python -m scripts.qa_metrics.report --date today
    python -m scripts.qa_metrics.report --date 2026-06-12
    python -m scripts.qa_metrics.report --date all
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "data" / "qa_logs" / "gate_metrics.jsonl"
DEFAULT_OUTPUT = ROOT / "docs" / "qa-metrics"


def parse_date(s: str) -> date:
    if s == "today":
        return date.today()
    if s == "yesterday":
        return date.today() - timedelta(days=1)
    if s == "all":
        return None
    return datetime.strptime(s, "%Y-%m-%d").date()


def load_records(path: Path, target: date | None) -> list[dict]:
    if not path.exists():
        return []
    out: list[dict] = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            ts = rec.get("ts", "")
            try:
                d = datetime.fromisoformat(ts.replace("Z", "+00:00")).date()
            except Exception:
                continue
            if target is None or d == target:
                out.append(rec)
    return out


def aggregate(records: list[dict]) -> dict:
    by_qt: dict[str, dict] = defaultdict(lambda: {
        "count": 0,
        "can_answer": 0,
        "dropped": 0,
        "latency_sum": 0,
        "confidence_sum": 0.0,
        "routes": Counter(),
        "reject_reasons": Counter(),
    })
    total = 0
    for r in records:
        total += 1
        qt = r.get("questionType") or "UNKNOWN"
        b = by_qt[qt]
        b["count"] += 1
        if r.get("canAnswer"):
            b["can_answer"] += 1
        if r.get("rejectReason"):
            b["reject_reasons"][r["rejectReason"]] += 1
            b["dropped"] += 1
        b["routes"][r.get("route", "")] += 1
        b["latency_sum"] += int(r.get("latencyMs", 0) or 0)
        b["confidence_sum"] += float(r.get("confidence", 0.0) or 0.0)
    return {"total": total, "by_qt": by_qt}


def render_markdown(target: date | None, agg: dict) -> str:
    title = "全部" if target is None else target.isoformat()
    lines = [
        f"# QA 闸门指标日报 — {title}",
        "",
        f"总问数：{agg['total']}",
        "",
        "| QueryType | Count | CanAnswer | DropRate | AvgConfidence | AvgLatencyMs | TopRoute |",
        "|---|---|---|---|---|---|---|",
    ]
    for qt, b in sorted(agg["by_qt"].items(), key=lambda kv: -kv[1]["count"]):
        cnt = b["count"]
        ca = b["can_answer"]
        dr = (b["dropped"] / cnt * 100) if cnt else 0.0
        avg_conf = (b["confidence_sum"] / cnt) if cnt else 0.0
        avg_lat = (b["latency_sum"] / cnt) if cnt else 0
        top_route = b["routes"].most_common(1)[0][0] if b["routes"] else "-"
        lines.append(
            f"| {qt or 'EMPTY'} | {cnt} | {ca} | {dr:.1f}% | {avg_conf:.2f} | {avg_lat} | {top_route} |"
        )
    if not agg["by_qt"]:
        lines.append("| (无数据) | 0 | 0 | 0% | 0.00 | 0 | - |")
    lines.append("")
    lines.append("## RejectReason 分布")
    lines.append("")
    for qt, b in sorted(agg["by_qt"].items(), key=lambda kv: -kv[1]["count"]):
        if not b["reject_reasons"]:
            continue
        lines.append(f"- **{qt}**")
        for reason, c in b["reject_reasons"].most_common():
            lines.append(f"  - `{reason}` × {c}")
    lines.append("")
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="聚合 gate_metrics.jsonl 输出日报")
    p.add_argument("--input", default=str(DEFAULT_INPUT))
    p.add_argument("--output-dir", default=str(DEFAULT_OUTPUT))
    p.add_argument("--date", default="today", help="today / yesterday / YYYY-MM-DD / all")
    args = p.parse_args(argv)

    target = parse_date(args.date)
    records = load_records(Path(args.input), target)
    agg = aggregate(records)
    md = render_markdown(target, agg)

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    fname = "all.md" if target is None else f"{target.isoformat()}.md"
    out = out_dir / fname
    out.write_text(md, encoding="utf-8")
    print(f"wrote {out} (records={agg['total']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

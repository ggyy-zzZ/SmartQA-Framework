#!/usr/bin/env python3
"""
从 enterprise_mysql_clean.jsonl 生成面向 RAG 的公司档案文档（无业务 ID，纯可读文本）。

输出：
  data/knowledge/doc_rag/company_profiles.jsonl  — 灌库用
  data/knowledge/doc_rag/company_profiles.txt    — 人工阅读
  data/knowledge/doc_rag/stats.json
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

SEAL_ROLE_LABELS = {"1": "保管负责人", "2": "使用人", "3": "审批人"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build doc-RAG company profile documents")
    parser.add_argument(
        "--input",
        default="data/knowledge/enterprise_mysql_clean.jsonl",
        help="Normalized company JSONL from build_knowledge_from_mysql.py",
    )
    parser.add_argument("--output-dir", default="data/knowledge/doc_rag")
    parser.add_argument("--limit", type=int, default=0)
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


def _is_numeric_id(value: Any) -> bool:
    s = str(value or "").strip()
    return bool(s) and s.isdigit()


def _fmt_date(raw: str) -> str:
    s = str(raw or "").strip()
    if len(s) == 8 and s.isdigit():
        return f"{s[0:4]}-{s[4:6]}-{s[6:8]}"
    return s


def _join_names(items: list[str]) -> str:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        name = str(item or "").strip()
        if not name or name in seen:
            continue
        seen.add(name)
        out.append(name)
    return "、".join(out)


def _format_cert(cert: dict[str, Any], index: int) -> str:
    cert_type = cert.get("cert_type_display") or cert.get("cert_type") or "未知证照"
    status = cert.get("status_display") or cert.get("status") or ""
    parts = [f"{index}. {cert_type}"]
    if status:
        parts.append(f"状态：{status}")
    valid_from = _fmt_date(str(cert.get("valid_from") or cert.get("issue_date") or ""))
    valid_to = _fmt_date(str(cert.get("expire_date") or ""))
    if valid_from or valid_to:
        parts.append(f"有效期：{valid_from or '未知'} 至 {valid_to or '长期'}")
    if cert.get("code"):
        parts.append(f"编号：{cert.get('code')}")
    supervisors = _join_names(cert.get("supervisors") or [])
    keepers = _join_names(cert.get("certification_keepers") or [])
    executors = _join_names(cert.get("executors") or [])
    if supervisors:
        parts.append(f"监管人：{supervisors}")
    if keepers:
        parts.append(f"保管人：{keepers}")
    if executors:
        parts.append(f"执行人：{executors}")
    return "｜".join(parts)


def _format_seal(seal: dict[str, Any], index: int) -> str:
    seal_type = seal.get("seal_type_display") or seal.get("seal_type") or "未知印章"
    category = seal.get("seal_category_display") or seal.get("seal_category") or ""
    status = seal.get("status_display") or seal.get("status") or ""
    parts = [f"{index}. {seal_type}"]
    if category:
        parts.append(f"类别：{category}")
    if status:
        parts.append(f"状态：{status}")
    persons = seal.get("persons") or []
    person_names: list[str] = []
    for p in persons:
        name = str(p.get("account_name") or "").strip()
        if name:
            person_names.append(name)
    if person_names:
        parts.append(f"相关人员：{_join_names(person_names)}")
    return "｜".join(parts)


def _core_key_people(key_people: list[dict[str, Any]]) -> list[str]:
    skip_prefixes = ("印章角色", "证照")
    lines: list[str] = []
    seen: set[tuple[str, str]] = set()
    for person in key_people:
        role = str(person.get("role") or person.get("role_display") or "").strip()
        name = str(person.get("name") or person.get("person_display") or "").strip()
        if not role or not name:
            continue
        if any(role.startswith(p) for p in skip_prefixes):
            continue
        key = (role, name)
        if key in seen:
            continue
        seen.add(key)
        lines.append(f"- {role}：{name}")
    return lines


def _shareholders(shareholders: list[dict[str, Any]]) -> list[str]:
    lines: list[str] = []
    for sh in shareholders:
        holder = sh.get("holder_name") or sh.get("name") or ""
        holder_type = sh.get("holder_type") or sh.get("type") or ""
        ratio = sh.get("ratio") or ""
        if not holder:
            continue
        label = f"{holder_type}：{holder}" if holder_type else str(holder)
        if ratio:
            label += f"（{ratio}）"
        lines.append(f"- {label}")
    return lines


def _directors(directors: list[dict[str, Any]]) -> list[str]:
    lines: list[str] = []
    for ds in directors:
        member_type = ds.get("member_type") or ds.get("type") or "成员"
        member_name = ds.get("member_name") or ds.get("name") or ""
        if member_name:
            lines.append(f"- {member_type}：{member_name}")
    return lines


def _bank_accounts(accounts: list[dict[str, Any]]) -> list[str]:
    lines: list[str] = []
    for acc in accounts:
        account_type = acc.get("account_type") or "账户"
        bank_name = acc.get("bank_name") or ""
        account_name = acc.get("account_name") or ""
        status = acc.get("status") or ""
        label = f"- {account_type}"
        if bank_name:
            label += f"｜{bank_name}"
        if account_name:
            label += f"｜户名：{account_name}"
        if status:
            label += f"｜状态：{status}"
        lines.append(label)
    return lines


def _product_lines(product_lines: list[dict[str, Any]]) -> str:
    items: list[str] = []
    for pl in product_lines:
        module = pl.get("module_display") or pl.get("module") or ""
        line = pl.get("line_display") or pl.get("line") or ""
        relation = pl.get("relation") or ""
        text = "/".join(x for x in [module, line, relation] if x)
        if text:
            items.append(text)
    return "；".join(items)


def build_enum_profile() -> dict[str, Any]:
    """证照/印章类型目录，供类型枚举类问句检索。"""
    # 从已有 compiled 文本抽取成本高；此处写常见类型名，灌库时作为独立文档。
    cert_types = [
        "营业执照-独立法人", "营业执照-独立法人分公司", "营业执照-合伙企业",
        "人力资源服务许可证", "劳务派遣经营许可证", "增值电信业务经营许可证",
        "高新技术企业证书", "广播电视节目制作许可证", "ICP备案", "开户许可证",
        "ISO9001", "ISO14001", "ISO45001", "ISO27001", "人力资源服务备案",
    ]
    seal_types = [
        "法定名称章", "财务专用章", "发票专用章", "合同专用章",
        "人力资源专用章", "法人手签章", "法人方章", "社保专用章", "钢印章",
    ]
    lines = [
        "=== 类型目录 ===",
        "说明：本档案列出系统中常见的资质证照类型与印章类型，供「有哪些种类」类问句检索。",
        "",
        "【资质证照类型】",
        *[f"- {t}" for t in cert_types],
        "",
        "【印章类型】",
        *[f"- {t}" for t in seal_types],
        "=== 目录结束 ===",
    ]
    profile_text = "\n".join(lines)
    return {
        "doc_id": "enum-catalog",
        "company_id": "enum-catalog",
        "company_name": "企业证照与印章类型目录",
        "status": "参考",
        "registered_area": "",
        "profile_text": profile_text,
    }


def build_company_profile(row: dict[str, Any]) -> dict[str, Any]:
    company_id = str(row.get("company_id") or "").strip()
    company_name = str(row.get("company_name") or "").strip()
    parent = str(row.get("parent_company") or "").strip()
    if parent and _is_numeric_id(parent):
        parent = ""
    parent = re.sub(r"\（ID\s*\d+\）", "", parent).strip()

    sections: list[str] = [
        "=== 公司档案 ===",
        f"名称：{company_name}",
        "",
        "【基础信息】",
        f"统一社会信用代码：{row.get('credit_code') or '未登记'}",
        f"经营状态：{row.get('status') or '未知'}",
        f"主体类型：{row.get('entity_type') or '未知'}",
        f"主体分类：{row.get('entity_category') or '未知'}",
        f"成立日期：{_fmt_date(str(row.get('established_date') or '')) or '未登记'}",
        f"注册地区：{row.get('registered_area') or '未登记'}",
        f"注册地址：{row.get('registered_address') or '未登记'}",
        f"实际办公地址：{row.get('office_address') or '未登记'}",
        f"母公司：{parent or '无'}",
    ]

    scope = str(row.get("business_scope") or "").strip()
    if scope:
        sections.extend(["", "【经营范围】", scope])

    product_line_text = _product_lines(row.get("product_lines") or [])
    if product_line_text:
        sections.extend(["", "【产品线】", product_line_text])

    people_lines = _core_key_people(row.get("key_people") or [])
    if people_lines:
        sections.extend(["", "【关键人员】", *people_lines])

    director_lines = _directors(row.get("directors_supervisors") or [])
    if director_lines:
        sections.extend(["", "【董监高】", *director_lines])

    shareholder_lines = _shareholders(row.get("shareholders") or [])
    if shareholder_lines:
        sections.extend(["", "【股东结构】", *shareholder_lines])

    certs = row.get("certificates") or []
    if certs:
        sections.append("")
        sections.append("【资质证照】")
        for i, cert in enumerate(certs, 1):
            sections.append(_format_cert(cert, i))
    else:
        sections.extend(["", "【资质证照】", "（暂无登记）"])

    seals = row.get("seals") or []
    if seals:
        sections.append("")
        sections.append("【印章】")
        for i, seal in enumerate(seals, 1):
            sections.append(_format_seal(seal, i))
    else:
        sections.extend(["", "【印章】", "（暂无登记）"])

    bank_lines = _bank_accounts(row.get("bank_accounts") or [])
    if bank_lines:
        sections.extend(["", "【银行账户】", *bank_lines])

    sections.extend(["", "=== 档案结束 ==="])
    profile_text = "\n".join(sections)

    return {
        "doc_id": company_id,
        "company_id": company_id,
        "company_name": company_name,
        "status": row.get("status") or "",
        "registered_area": row.get("registered_area") or "",
        "profile_text": profile_text,
    }


def main() -> None:
    args = parse_args()
    root = Path(__file__).resolve().parents[2]
    input_path = Path(args.input)
    if not input_path.is_absolute():
        input_path = root / input_path
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = root / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    rows = read_jsonl(input_path, args.limit)
    if not rows:
        raise SystemExit(f"No rows in {input_path}")

    profiles = [build_enum_profile()]
    profiles.extend(build_company_profile(row) for row in rows)

    jsonl_path = output_dir / "company_profiles.jsonl"
    txt_path = output_dir / "company_profiles.txt"
    stats_path = output_dir / "stats.json"

    with jsonl_path.open("w", encoding="utf-8") as f:
        for profile in profiles:
            f.write(json.dumps(profile, ensure_ascii=False) + "\n")

    txt_path.write_text(
        "\n\n".join(p["profile_text"] for p in profiles),
        encoding="utf-8",
    )

    stats = {
        "source": str(input_path),
        "profile_count": len(profiles),
        "company_count": len(profiles) - 1,
        "output_jsonl": str(jsonl_path),
        "output_txt": str(txt_path),
    }
    stats_path.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

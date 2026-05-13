#!/usr/bin/env python3
"""
Clean enterprise.txt into structured JSONL for QA and graph sync.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


SPLIT_TOKEN_REGEX = re.compile(r"\};\s*\{companyId=")
FIELD_LINE_REGEX = re.compile(r"^([^：]+)：\s*(.*)$")
PERSON_REGEX = re.compile(r"^(?P<name>[^（\s]+)(?:（ID:(?P<id>[^）]+)）)?")
TEST_NAME_REGEX = re.compile(r"(测试|test|TEST)")
QUESTION_MARK_REGEX = re.compile(r"\?{2,}")


@dataclass
class ProductLine:
    module: str
    line: str
    relation: str


@dataclass
class PersonRole:
    role: str
    name: str
    person_id: str | None


@dataclass
class Shareholder:
    holder_type: str
    holder_name: str
    ratio: str | None


@dataclass
class Certificate:
    cert_type: str
    status: str | None
    code: str | None
    issue_date: str | None
    expire_date: str | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Clean enterprise document into JSONL")
    parser.add_argument(
        "--input",
        default="src/main/resources/enterprise.txt",
        help="Path to enterprise.txt",
    )
    parser.add_argument(
        "--output-dir",
        default="data/knowledge",
        help="Output directory for cleaned data",
    )
    parser.add_argument(
        "--max-records",
        type=int,
        default=0,
        help="Limit number of records for debugging (0 means no limit)",
    )
    return parser.parse_args()


def normalize_text(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\ufeff", "").strip()


def split_records(raw_text: str) -> list[str]:
    text = normalize_text(raw_text)
    if "{companyId=" not in text:
        return []
    chunks = SPLIT_TOKEN_REGEX.split(text)
    records: list[str] = []
    for idx, chunk in enumerate(chunks):
        if idx == 0:
            record = chunk
        else:
            record = "{companyId=" + chunk
        record = record.strip()
        if not record.startswith("{companyId="):
            continue
        records.append(record)
    return records


def extract_between(text: str, start: str, end: str) -> str | None:
    start_idx = text.find(start)
    if start_idx < 0:
        return None
    from_idx = start_idx + len(start)
    end_idx = text.find(end, from_idx)
    if end_idx < 0:
        return None
    return text[from_idx:end_idx].strip()


def extract_field_from_lines(lines: list[str], field_name: str) -> str | None:
    prefix = field_name + "："
    for line in lines:
        if line.startswith(prefix):
            value = line[len(prefix) :].strip()
            return value if value else None
    return None


def parse_person_role(lines: list[str], role: str) -> PersonRole | None:
    raw = extract_field_from_lines(lines, role)
    if not raw:
        return None
    m = PERSON_REGEX.search(raw)
    if not m:
        return None
    name = m.group("name").strip()
    person_id = (m.group("id") or "").strip() or None
    if not name or name in {"无", "-"}:
        return None
    return PersonRole(role=role, name=name, person_id=person_id)


def parse_product_lines(lines: list[str]) -> list[ProductLine]:
    result: list[ProductLine] = []
    for line in lines:
        if not line.startswith("记录"):
            continue
        module = extract_between(line, "模块 ", "，产品线 ")
        line_name = extract_between(line, "产品线 ", "，关系 ")
        relation = extract_between(line, "关系 ", "。")
        if module and line_name:
            result.append(
                ProductLine(
                    module=module.strip(),
                    line=line_name.strip(),
                    relation=(relation or "").strip(),
                )
            )
    return result


def parse_shareholders(lines: list[str]) -> list[Shareholder]:
    result: list[Shareholder] = []
    for line in lines:
        if not line.startswith("股东"):
            continue
        holder_type = "未知"
        holder_name = None
        ratio = None
        if "公司股东 " in line:
            holder_type = "公司"
            holder_name = extract_between(line, "公司股东 ", "；")
        elif "自然人股东 " in line:
            holder_type = "自然人"
            holder_name = extract_between(line, "自然人股东 ", "；")
        if "持股比例 " in line:
            ratio = extract_between(line, "持股比例 ", "%")
            if ratio:
                ratio = ratio + "%"
        if holder_name:
            result.append(
                Shareholder(
                    holder_type=holder_type,
                    holder_name=holder_name.strip(),
                    ratio=ratio,
                )
            )
    return result


def parse_certificates(lines: list[str]) -> list[Certificate]:
    certs: list[Certificate] = []
    current: dict[str, str | None] | None = None

    for line in lines:
        if line.startswith("证照"):
            if current:
                certs.append(
                    Certificate(
                        cert_type=current.get("cert_type") or "未知证照",
                        status=current.get("status"),
                        code=current.get("code"),
                        issue_date=current.get("issue_date"),
                        expire_date=current.get("expire_date"),
                    )
                )
            cert_type = extract_between(line, "：", "，状态 ")
            status = extract_between(line, "状态 ", "。")
            if not status and "，状态 " in line:
                status = line.split("，状态 ", 1)[1].split("，", 1)[0].split("。", 1)[0]
            current = {
                "cert_type": cert_type,
                "status": status,
                "code": None,
                "issue_date": None,
                "expire_date": None,
            }
            continue

        if current is None:
            continue

        m = FIELD_LINE_REGEX.match(line)
        if not m:
            continue
        key = m.group(1).strip()
        value = m.group(2).strip()

        if key in {"统一社会信用代码", "编号"}:
            current["code"] = value or current.get("code")
        elif key in {"发证日期/首次发证日期", "发证日期"}:
            current["issue_date"] = value or current.get("issue_date")
        elif key in {"续期时间", "有效期"}:
            current["expire_date"] = value or current.get("expire_date")

    if current:
        certs.append(
            Certificate(
                cert_type=current.get("cert_type") or "未知证照",
                status=current.get("status"),
                code=current.get("code"),
                issue_date=current.get("issue_date"),
                expire_date=current.get("expire_date"),
            )
        )
    return certs


def is_bad_record(company_name: str | None, status: str | None, record_text: str) -> tuple[bool, str]:
    if not company_name:
        return True, "missing_company_name"
    if TEST_NAME_REGEX.search(company_name):
        return True, "test_name"
    if status == "0":
        return True, "invalid_status_0"
    if QUESTION_MARK_REGEX.search(record_text):
        return True, "garbled_question_marks"
    return False, ""


def parse_record(record_text: str) -> tuple[dict[str, Any] | None, str]:
    lines = [line.strip() for line in normalize_text(record_text).split("\n") if line.strip()]

    company_id = extract_between(record_text, "{companyId=", ", companyName=")
    company_name = extract_between(record_text, "companyName=", ", summary=")
    status = extract_field_from_lines(lines, "经营状态")

    bad, reason = is_bad_record(company_name, status, record_text)
    if bad:
        return None, reason

    base = {
        "company_id": company_id,
        "company_name": company_name,
        "company_short_name": extract_field_from_lines(lines, "公司简称"),
        "company_code": extract_field_from_lines(lines, "公司代码"),
        "credit_code": extract_field_from_lines(lines, "统一社会信用代码"),
        "status": status,
        "entity_type": extract_field_from_lines(lines, "主体类型"),
        "entity_category": extract_field_from_lines(lines, "主体分类"),
        "currency": extract_field_from_lines(lines, "记账币种"),
        "registered_area": extract_field_from_lines(lines, "注册地区"),
        "registered_address": extract_field_from_lines(lines, "注册地址"),
        "office_address": extract_field_from_lines(lines, "实际办公地址"),
        "established_date": extract_field_from_lines(lines, "成立日期"),
        "business_scope": extract_field_from_lines(lines, "经营范围"),
        "parent_company": extract_field_from_lines(lines, "总公司"),
        "social_security_opened": extract_field_from_lines(lines, "是否已办理社保账号"),
        "housing_fund_opened": extract_field_from_lines(lines, "是否已办理公积金账号"),
        "tax_registered": extract_field_from_lines(lines, "是否已办理税务登记"),
        "bank_account_opened": extract_field_from_lines(lines, "是否已开立银行账户"),
        "product_lines": [asdict(x) for x in parse_product_lines(lines)],
        "key_people": [
            asdict(p)
            for p in [
                parse_person_role(lines, "法定代表人"),
                parse_person_role(lines, "财务负责人"),
                parse_person_role(lines, "经理"),
                parse_person_role(lines, "董事长或执行董事"),
                parse_person_role(lines, "企业联络人"),
            ]
            if p is not None
        ],
        "shareholders": [asdict(s) for s in parse_shareholders(lines)],
        "certificates": [asdict(c) for c in parse_certificates(lines)],
        "source_file": "enterprise.txt",
    }

    if base["status"] in {"0", "", None}:
        return None, "invalid_status_empty"
    if base["entity_type"] in {"0"} or base["entity_category"] in {"0"}:
        return None, "invalid_entity_type_or_category"

    return base, ""


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> None:
    args = parse_args()
    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    if not input_path.exists():
        raise SystemExit(f"Input file not found: {input_path}")

    raw_text = input_path.read_text(encoding="utf-8", errors="replace")
    record_texts = split_records(raw_text)

    cleaned: list[dict[str, Any]] = []
    rejects: list[dict[str, Any]] = []

    max_records = args.max_records if args.max_records and args.max_records > 0 else len(record_texts)
    for idx, record_text in enumerate(record_texts[:max_records], start=1):
        parsed, reason = parse_record(record_text)
        if parsed is None:
            rejects.append(
                {
                    "index": idx,
                    "reason": reason,
                    "preview": record_text[:280],
                }
            )
        else:
            cleaned.append(parsed)

    clean_path = output_dir / "enterprise_clean.jsonl"
    reject_path = output_dir / "enterprise_rejects.jsonl"
    stats_path = output_dir / "enterprise_stats.json"

    write_jsonl(clean_path, cleaned)
    write_jsonl(reject_path, rejects)

    stats = {
        "input_file": str(input_path),
        "total_records": len(record_texts[:max_records]),
        "clean_records": len(cleaned),
        "rejected_records": len(rejects),
        "reject_reasons": {},
    }
    for item in rejects:
        reason = item["reason"]
        stats["reject_reasons"][reason] = stats["reject_reasons"].get(reason, 0) + 1

    stats_path.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")

    print("Clean finished.")
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    print(f"Output: {clean_path}")
    print(f"Rejects: {reject_path}")
    print(f"Stats: {stats_path}")


if __name__ == "__main__":
    main()

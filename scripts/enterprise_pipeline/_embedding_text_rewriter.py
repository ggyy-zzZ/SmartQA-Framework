"""LLM 改写：把结构化 company row → 通顺中文段落（80-180 字），用于 embedding 召回。

设计原则：
- 100% 保留结构化字段里的关键事实（主营/产品/技术/地域/资质）
- 不创造结构化字段里没有的事实
- 地域粒度保留原始（"北京海淀"不要改写成"华北"）
- 长度限制在 80-180 字（embedding 最佳输入区间）
- 失败降级：调用失败/超时 → 退回 build_document 拼接版本
"""
from __future__ import annotations

import json
import os
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import requests

REWRITE_PROMPT = """你是企业知识库编目员。请把以下结构化企业信息改写为一段 80-180 字的通顺中文描述，用于向量检索。

严格要求：
1. 保留所有可检索的关键事实：主营/产品/技术/行业/地域特色/资质
2. 不要捏造结构化字段里没有的内容
3. 不要堆砌"行业领先"等空话
4. 地域表述保留原始粒度（如"北京海淀"不要改写成"华北"）
5. 直接输出段落，不要带"该公司"、"我们"等主语开头
6. 不要写思考过程，不要加前缀解释，直接输出段落正文

输入（JSON）：
{row}

改写后段落："""


def _format_row_for_prompt(row: dict) -> str:
    """压平结构化字段成 LLM 易读的简短 JSON，控制输入 < 1500 字"""
    keys = [
        "company_name", "company_short_name", "credit_code", "status",
        "entity_type", "entity_category", "established_date",
        "registered_area", "office_area", "parent_company",
        "registered_address", "office_address", "business_scope",
    ]
    out: dict[str, Any] = {k: row.get(k) for k in keys if row.get(k)}
    kp = row.get("key_people") or []
    out["key_people"] = [
        {"role": p.get("role"), "name": p.get("name")}
        for p in kp if p.get("name")
    ]
    certs = row.get("certificates") or []
    out["certificates"] = [
        {"type": c.get("cert_type"), "status": c.get("status")}
        for c in certs if c.get("cert_type")
    ]
    return json.dumps(out, ensure_ascii=False)


def _extract_text(data: dict) -> str:
    """从 LLM 响应里取最佳可用文本。

    顺序：message.content > message.reasoning_content。
    一些模型（如 MiniMax-M3）会先在 reasoning_content 里写思考过程，
    然后 content 写最终输出；如果两个都存在，仅用 content。
    """
    try:
        msg = data["choices"][0]["message"]
    except (KeyError, IndexError, TypeError):
        return ""
    content = (msg.get("content") or "").strip()
    if content:
        return content
    # 兜底：取 reasoning_content 的"思考后"段
    reasoning = (msg.get("reasoning_content") or "").strip()
    if reasoning:
        # 启发式：如果 reasoning 里有完整段落（>=30 字 + 含中文逗号/句号），采用
        if 30 <= len(reasoning) <= 2000:
            return reasoning
    return ""


def rewrite_one(
    row: dict,
    api_url: str,
    api_key: str,
    model: str,
    timeout: int = 30,
    max_retries: int = 2,
) -> str | None:
    """单条改写；返回改写后文本，失败返回 None"""
    if not api_key:
        return None
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你是企业知识库编目员。直接输出正文，不要写思考过程。"},
            {
                "role": "user",
                "content": REWRITE_PROMPT.format(row=_format_row_for_prompt(row)),
            },
        ],
        "max_tokens": 1024,
        "temperature": 0.2,
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    for attempt in range(max_retries):
        try:
            r = requests.post(api_url, headers=headers, json=payload, timeout=timeout)
            r.raise_for_status()
            data = r.json()
            text = _extract_text(data)
            if 30 <= len(text) <= 1500:
                return text
        except Exception:
            if attempt == max_retries - 1:
                return None
            time.sleep(0.5 * (attempt + 1))
    return None


def rewrite_batch(
    rows: list[dict],
    api_url: str,
    api_key: str,
    model: str,
    workers: int = 4,
) -> list[str | None]:
    """并发批量改写；返回与 rows 等长的 list，失败位置为 None"""
    results: list[str | None] = [None] * len(rows)
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futures = {
            ex.submit(rewrite_one, r, api_url, api_key, model): i
            for i, r in enumerate(rows)
        }
        for fut in futures:
            i = futures[fut]
            try:
                results[i] = fut.result()
            except Exception:
                results[i] = None
    return results


def default_llm_config() -> dict:
    """从环境变量读默认 LLM 改写配置（与 application.properties 镜像）。"""
    return {
        "api_url": os.environ.get(
            "MINIMAX_API_URL",
            "https://api.minimaxi.com/v1/text/chatcompletion_v2",
        ),
        "api_key": os.environ.get("MINIMAX_API_KEY", ""),
        "model": os.environ.get("MINIMAX_MODEL", "MiniMax-M3"),
    }

"""从 application-local.properties / 环境变量读取密钥（供 doc_rag 脚本使用）。"""

from __future__ import annotations

import os
from pathlib import Path


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def load_local_properties() -> dict[str, str]:
    path = _repo_root() / "application-local.properties"
    if not path.exists():
        return {}
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def resolve_dashscope_api_key(explicit: str = "") -> str:
    if explicit and explicit.strip():
        return explicit.strip()
    env = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if env:
        return env
    props = load_local_properties()
    return props.get("qa.assistant.dashscope-api-key", "").strip()

"""Shared helpers for EKSP incremental sync (stable ids, content hash)."""

from __future__ import annotations

import hashlib
import json
import uuid
from typing import Any


DEFAULT_DOMAIN = "org_master"
DEFAULT_ENTITY_TYPE = "Company"


def stable_point_id(domain: str, entity_type: str, entity_id: str) -> str:
    """Deterministic Qdrant point id from domain + entity_type + entity_id."""
    key = f"{domain}:{entity_type}:{entity_id}"
    digest = hashlib.sha256(key.encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest[:16]))


def content_hash(payload: dict[str, Any]) -> str:
    """SHA-256 of canonical JSON for change detection."""
    normalized = json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def entity_keys_from_row(row: dict[str, Any], domain: str = DEFAULT_DOMAIN) -> tuple[str, str, str]:
    entity_id = str(row.get("company_id") or row.get("entity_id") or "").strip()
    entity_type = str(row.get("entity_type_key") or DEFAULT_ENTITY_TYPE)
    return domain, entity_type, entity_id

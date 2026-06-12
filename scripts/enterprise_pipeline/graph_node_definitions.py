"""
Python 镜像类：直接 json.load 同一份 src/main/resources/qa/graph-node-definitions.json。

与 Java 侧 com.qa.demo.qa.config.GraphNodeDefinitionsProperties 共用同一份 JSON；
任何字段名、截断上限、枚举字典的变更必须同步两侧。

用法：
    from graph_node_definitions import GraphNodeDefinitions
    defs = GraphNodeDefinitions.load()
    print(defs.properties_for("Company"))
    print(defs.max_chars_for("Company", "businessScope"))
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


# 仓库根（scripts/enterprise_pipeline/ -> ../..）
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DEFINITIONS_PATH = (
    REPO_ROOT / "src" / "main" / "resources" / "qa" / "graph-node-definitions.json"
)


class GraphNodeDefinitions:
    """字段白名单的不可变快照。"""

    def __init__(self, raw: dict[str, Any], source_path: Path) -> None:
        self._raw = raw
        self._source_path = source_path
        global_cfg = raw.get("global") or {}
        trunc = global_cfg.get("truncation") or {}
        self.default_max_chars = int(trunc.get("maxChars", 4000))
        self.truncation_suffix = trunc.get("suffix", "…[Truncated]")
        self.truncation_mark_suffix = trunc.get("markPropertySuffix", "Truncated")
        self.truncation_count_suffix = trunc.get("charCountPropertySuffix", "CharCount")
        self.exclude_keys: frozenset[str] = frozenset(global_cfg.get("exclude") or [])
        self.enum_dicts: dict[str, dict[str, str]] = dict(raw.get("enumDicts") or {})
        self.node_types: dict[str, dict[str, Any]] = dict(raw.get("nodeTypes") or {})
        self.constraints: list[dict[str, Any]] = list(raw.get("constraints") or [])
        self.indexes: list[dict[str, Any]] = list(raw.get("indexes") or [])

    # ------------------------------------------------------------------
    # 加载
    # ------------------------------------------------------------------
    @classmethod
    def load(cls, path: Path | str | None = None) -> "GraphNodeDefinitions":
        target = Path(path) if path else DEFAULT_DEFINITIONS_PATH
        if not target.exists():
            raise FileNotFoundError(
                f"graph-node-definitions.json not found at: {target}"
            )
        with target.open("r", encoding="utf-8") as fh:
            return cls(json.load(fh), target)

    @property
    def source_path(self) -> Path:
        return self._source_path

    # ------------------------------------------------------------------
    # 查询
    # ------------------------------------------------------------------
    def labels(self) -> list[str]:
        return list(self.node_types.keys())

    def definition(self, label: str) -> dict[str, Any] | None:
        return self.node_types.get(label)

    def properties_for(self, label: str) -> list[dict[str, Any]]:
        defn = self.definition(label)
        if not defn:
            return []
        return list(defn.get("properties") or [])

    def property_spec(self, label: str, prop_name: str) -> dict[str, Any] | None:
        for spec in self.properties_for(label):
            if spec.get("name") == prop_name:
                return spec
        return None

    def max_chars_for(self, label: str, prop_name: str) -> int:
        spec = self.property_spec(label, prop_name)
        if not spec:
            return self.default_max_chars
        max_chars = spec.get("maxChars")
        if isinstance(max_chars, int) and max_chars > 0:
            return max_chars
        return self.default_max_chars

    def enum_dict_key_for(self, label: str, prop_name: str) -> str | None:
        spec = self.property_spec(label, prop_name)
        if not spec:
            return None
        return spec.get("enumDictKey")

    def required_props_for(self, label: str) -> list[str]:
        return [p["name"] for p in self.properties_for(label) if p.get("required")]

    def all_physical_columns_for(self, label: str) -> set[str]:
        cols: set[str] = set()
        for spec in self.properties_for(label):
            for col in spec.get("columns") or []:
                cols.add(str(col))
        return cols

    def find_property_by_column(self, label: str, physical_column: str) -> dict[str, Any] | None:
        for spec in self.properties_for(label):
            if physical_column in (spec.get("columns") or []):
                return spec
        return None

    def is_excluded(self, column_name: str) -> bool:
        return column_name in self.exclude_keys

    # ------------------------------------------------------------------
    # 截断辅助
    # ------------------------------------------------------------------
    def truncate_value(
        self, value: Any, label: str, prop_name: str
    ) -> tuple[Any, int, bool]:
        """
        截断返回 (value, char_count, truncated)。
        char_count 永远记录原始字符串长度；truncated 为 True 时 value 已被截断并附 suffix。
        """
        if value is None:
            return None, 0, False
        text = str(value)
        char_count = len(text)
        max_chars = self.max_chars_for(label, prop_name)
        if max_chars <= 0 or char_count <= max_chars:
            return text, char_count, False
        keep = max(0, max_chars - len(self.truncation_suffix))
        truncated = text[:keep] + self.truncation_suffix
        return truncated, char_count, True


__all__ = ["GraphNodeDefinitions", "DEFAULT_DEFINITIONS_PATH"]
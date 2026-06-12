"""
MySQL 列筛选与 Neo4j 属性映射：白名单 + 截断入图。

字段白名单来源：``qa/graph-node-definitions.json``（Python/Java 共同消费）。
不在白名单内的字段不进入 Neo4j；白名单内的长文本按 ``maxChars`` 截断并附
``*Truncated=true`` / ``*CharCount=N`` 标记。
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from typing import Any
import json

from graph_node_definitions import GraphNodeDefinitions

# 兜底白名单：当未传入 ``definitions`` 时仍走「v1 行为」——varchar(<=100) 可入图，
# TEXT/BLOB 全部跳过。这样旧调用方（不带 defs）行为不变。
LEGACY_KEEP_TYPES = frozenset(
    {
        "varchar",
        "char",
        "nvarchar",
        "nchar",
        "date",
        "datetime",
        "timestamp",
        "int",
        "bigint",
        "smallint",
        "tinyint",
        "mediumint",
        "decimal",
        "numeric",
        "float",
        "double",
        "json",
    }
)
LEGACY_VARCHAR_MAX = 100
LEGACY_DROP_TYPES = frozenset(
    {
        "text",
        "tinytext",
        "mediumtext",
        "longtext",
        "blob",
        "tinyblob",
        "mediumblob",
        "longblob",
        "binary",
        "varbinary",
    }
)

# JSONL 顶层嵌套字段，不作为 Company 扁平属性
COMPANY_JSONL_NESTED_KEYS = frozenset(
    {
        "bank_accounts",
        "certificates",
        "seals",
        "key_people",
        "shareholders",
        "product_lines",
        "directors_supervisors",
        "attachments",
        "certificate_persons",
        "change_events",
        "company_graph_props",
    }
)

# 顶层 row 字段 → graph-node-definitions.json 中的节点 Label
NESTED_ARRAY_LABEL_MAP: dict[str, str] = {
    "key_people": "Person",
    "bank_accounts": "BankAccount",
    "certificates": "Certificate",
    "seals": "Seal",
    "shareholders": "Shareholder",
    "product_lines": "ProductLine",
    "directors_supervisors": "DirectorSupervisor",
    "attachments": "CertificateAttachment",
    "certificate_persons": "CertificatePersonDetail",
    "change_events": "CompanyChangeEvent",
}


def _default_definitions() -> GraphNodeDefinitions | None:
    """懒加载白名单；失败时返回 None（退回 LEGACY 行为）。"""
    try:
        return GraphNodeDefinitions.load()
    except FileNotFoundError:
        return None


def row_value(row: dict[str, Any], key: str, default: Any = None) -> Any:
    if key in row:
        return row[key]
    key_lower = key.lower()
    for k, v in row.items():
        if str(k).lower() == key_lower:
            return v
    return default


def is_exportable_column(
    data_type: str,
    char_max_length: int | None,
    definitions: GraphNodeDefinitions | None = None,
) -> bool:
    """
    判定物理列是否可入图。

    - 传入 ``definitions``（白名单驱动模式）：仅当列属于白名单内任意节点的
      ``properties[].columns`` 时返回 ``True``，类型过滤放宽到可表达字段
      （varchar/char/text/longtext/date/datetime/int/bigint/decimal/tinyint/json 等），
      blob/binary 永远拒绝。
    - 未传入：走 LEGACY 行为（varchar(<=100) 通过、TEXT/BLOB 拒绝），与 v1 一致。
    """
    dt = (data_type or "").strip().lower()
    if not dt:
        return False
    if "blob" in dt or dt in ("binary", "varbinary"):
        return False
    if definitions is None:
        # LEGACY 路径：保持旧实现语义
        if dt in LEGACY_DROP_TYPES:
            return False
        if dt in LEGACY_KEEP_TYPES:
            if dt in ("varchar", "char", "nvarchar", "nchar"):
                if char_max_length is not None and char_max_length > LEGACY_VARCHAR_MAX:
                    return False
            return True
        return False
    # 白名单驱动：仅依赖 defs.is_excluded / find_property_by_column 决定是否入图；
    # 不在白名单内的字段全部返回 False（剔除 domain/creator_id 等）。
    # 类型过滤放宽：text/longtext 允许通过，由 build_graph_props 阶段负责截断。
    return True


def is_whitelisted_column(
    physical_column: str,
    definitions: GraphNodeDefinitions,
) -> bool:
    """
    物理列是否在 graph-node-definitions.json 的任意节点白名单内。
    """
    if not physical_column:
        return False
    if definitions.is_excluded(physical_column):
        return False
    for label in definitions.labels():
        if definitions.find_property_by_column(label, physical_column):
            return True
    return False


def list_exportable_column_meta(
    conn,
    schema: str,
    table: str,
    definitions: GraphNodeDefinitions | None = None,
) -> list[dict[str, Any]]:
    sql = """
    SELECT column_name, data_type, character_maximum_length
    FROM information_schema.columns
    WHERE table_schema = %s AND table_name = %s
    ORDER BY ordinal_position
    """
    with conn.cursor() as cur:
        cur.execute(sql, (schema, table))
        rows = cur.fetchall()
    result: list[dict[str, Any]] = []
    for row in rows:
        name = str(row_value(row, "column_name", "") or "").strip()
        if not name:
            continue
        if definitions is not None and not is_whitelisted_column(name, definitions):
            continue
        data_type = str(row_value(row, "data_type", "") or "")
        max_len = row_value(row, "character_maximum_length")
        try:
            max_len_int = int(max_len) if max_len is not None else None
        except (TypeError, ValueError):
            max_len_int = None
        if is_exportable_column(data_type, max_len_int, definitions=definitions):
            result.append(
                {
                    "column_name": name,
                    "data_type": data_type,
                    "char_max_length": max_len_int,
                }
            )
    return result


def list_exportable_column_names(
    conn,
    schema: str,
    table: str,
    definitions: GraphNodeDefinitions | None = None,
) -> list[str]:
    return [
        m["column_name"]
        for m in list_exportable_column_meta(conn, schema, table, definitions=definitions)
    ]


def snake_to_camel(name: str) -> str:
    parts = [p for p in name.strip().split("_") if p]
    if not parts:
        return name
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def scalar_to_neo4j(value: Any) -> str | int | float | bool | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return value
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat(sep=" ", timespec="seconds")
    text = str(value).strip()
    if not text:
        return None
    return text


def truncate_value(
    value: Any,
    max_chars: int,
    prop_name: str = "",
) -> tuple[Any, int, bool]:
    """
    与 Java ``CdcFieldTruncator`` 行为一致：超长按 ``global.truncation.suffix`` 截断。

    返回 ``(value, char_count, truncated)``：
    - ``char_count`` 永远记录原始字符串长度；
    - ``truncated`` 为 True 时 ``value`` 已被截断并附 suffix；
    - ``max_chars <= 0`` 时视为「不截断」。
    """
    if value is None:
        return None, 0, False
    text = str(value)
    char_count = len(text)
    if max_chars <= 0 or char_count <= max_chars:
        return text, char_count, False
    # 默认 suffix 与 graph-node-definitions.json#global.truncation.suffix 对齐
    suffix = "…[Truncated]"
    keep = max(0, max_chars - len(suffix))
    truncated = text[:keep] + suffix
    return truncated, char_count, True


def build_graph_props(
    row: dict[str, Any],
    *,
    exclude_keys: frozenset[str] | set[str] | None = None,
    skip_empty: bool = True,
    definitions: GraphNodeDefinitions | None = None,
    label: str | None = None,
) -> dict[str, Any]:
    """
    将 MySQL 行转为 Neo4j 可 SET 的属性 map（camelCase 键）。

    - ``definitions`` + ``label`` 同时传入时：按白名单 columns 过滤字段，并对
      ``maxChars > 0`` 的字段做截断（附 ``*Truncated`` / ``*CharCount`` 标记）。
    - 仅传 ``exclude_keys``：保持 v1 行为，截断不生效。
    """
    exclude = set(exclude_keys or frozenset())
    out: dict[str, Any] = {}

    if definitions is not None and label:
        label_specs = definitions.properties_for(label)
        # 物理列 → (prop_name, max_chars, enum_dict_key)
        col_to_spec: dict[str, tuple[str, int, str | None]] = {}
        for spec in label_specs:
            for col in spec.get("columns") or []:
                col_to_spec[str(col)] = (
                    str(spec.get("name") or ""),
                    int(spec.get("maxChars") or 0),
                    spec.get("enumDictKey"),
                )
        for key, value in row.items():
            key_str = str(key)
            if key_str in exclude:
                continue
            if isinstance(value, (list, dict, tuple, set)):
                continue
            spec_tuple = col_to_spec.get(key_str)
            if not spec_tuple:
                # 不在白名单内 → 跳过
                continue
            prop_name, max_chars, _enum_key = spec_tuple
            converted = scalar_to_neo4j(value)
            if converted is None and skip_empty:
                continue
            if prop_name and prop_name in out and out[prop_name] != converted:
                out[f"{prop_name}Raw"] = converted
                continue
            if max_chars > 0 and isinstance(converted, str):
                truncated_text, char_count, was_truncated = truncate_value(
                    converted, max_chars, prop_name
                )
                out[prop_name] = truncated_text
                if was_truncated:
                    out[f"{prop_name}Truncated"] = True
                    out[f"{prop_name}CharCount"] = char_count
            else:
                if prop_name:
                    out[prop_name] = converted
                else:
                    out[snake_to_camel(key_str)] = converted
        return out

    # 旧行为：原样驼峰化
    for key, value in row.items():
        key_str = str(key)
        if key_str in exclude:
            continue
        if isinstance(value, (list, dict, tuple, set)):
            continue
        converted = scalar_to_neo4j(value)
        if converted is None and skip_empty:
            continue
        prop = snake_to_camel(key_str)
        if prop in out and out[prop] != converted:
            out[f"{prop}Raw"] = converted
        else:
            out[prop] = converted
    return out


def attach_graph_props(
    item: dict[str, Any],
    exclude: frozenset[str] | set[str] | None = None,
    *,
    definitions: GraphNodeDefinitions | None = None,
    label: str | None = None,
) -> dict[str, Any]:
    if "graph_props" not in item:
        item["graph_props"] = build_graph_props(
            item,
            exclude_keys=exclude,
            definitions=definitions,
            label=label,
        )
    return item


def build_company_graph_props(
    row: dict[str, Any],
    definitions: GraphNodeDefinitions | None = None,
) -> dict[str, Any]:
    exclude = COMPANY_JSONL_NESTED_KEYS | frozenset({"domain", "source_file"})
    defs = definitions if definitions is not None else _default_definitions()
    return build_graph_props(row, exclude_keys=exclude, definitions=defs, label="Company")


# 行政区划字典懒加载（一次性），避免每次 prepare_jsonl_row_for_neo4j 重新读盘
_REGION_DICT_CACHE: dict[str, Any] | None = None


def _load_region_dict() -> dict[str, Any] | None:
    """加载 qa/region-dictionary.json 的 reverse 子表（name → 6 位 code）。"""
    global _REGION_DICT_CACHE
    if _REGION_DICT_CACHE is not None:
        return _REGION_DICT_CACHE
    from pathlib import Path
    candidates = [
        Path("src/main/resources/qa/region-dictionary.json"),
        Path("../src/main/resources/qa/region-dictionary.json"),
    ]
    for p in candidates:
        if p.exists():
            with p.open("r", encoding="utf-8") as f:
                _REGION_DICT_CACHE = json.load(f)
            return _REGION_DICT_CACHE
    return None


def _resolve_region_code(area_name: str, region_dict: dict[str, Any]) -> str:
    """
    把注册地区名（"北京市"/"东城区"/"南京"）解析为 6 位 GB/T 2260 行政代码；
    若未命中返回空串。

    策略：
    1. 扫文本中所有 2-12 个连续汉字片段 → reverse 表查 → 命中按代码长度（数字）从长到短；
    2. 兼容"南京"等无"市"后缀的简称：尝试直接以 fragment + 常见后缀（"市"/"省"/"自治区"/"壮族自治区"）反查。
    """
    if not area_name or not region_dict:
        return ""
    name = str(area_name).strip()
    if not name:
        return ""
    reverse = region_dict.get("reverse") or {}
    import re

    candidates: list[tuple[int, str, str]] = []
    seen: set[str] = set()

    def _add_candidate(frag: str) -> None:
        if frag in seen:
            return
        seen.add(frag)
        if frag in reverse:
            candidates.append((len(reverse[frag]), frag, reverse[frag]))
            return
        # 兼容短名（"南京"/"天津"/"广州"）无"市"后缀的情形
        for suffix in ("市", "省", "区", "县", "地区", "盟", "自治州", "自治县", "自治区",
                       "壮族自治区", "回族自治区", "维吾尔自治区", "特别行政区"):
            if (frag + suffix) in reverse:
                candidates.append((len(reverse[frag + suffix]), frag + suffix, reverse[frag + suffix]))
                return
            # 反向：fragment 自身是 reverse 表里某个长名的一部分 → 跳过（避免误匹配）
            for name_in_dict, code_in_dict in reverse.items():
                if name_in_dict != frag and name_in_dict.startswith(frag) and name_in_dict.endswith(suffix):
                    candidates.append((len(code_in_dict), name_in_dict, code_in_dict))
                    return

    # 1) 全局扫所有 2-12 字符窗口（用滑窗替代单次 regex）
    chinese = "".join(c for c in name if "\u4e00" <= c <= "\u9fa5")
    for start in range(len(chinese)):
        for end in range(start + 2, min(start + 13, len(chinese) + 1)):
            _add_candidate(chinese[start:end])
    # 2) 同时保留 regex 模式（处理跨汉字与西文混合的片段）
    for m in re.finditer(r"[\u4e00-\u9fa5]{2,12}", name):
        _add_candidate(m.group())

    if not candidates:
        return ""
    # 最长（最具体）的 code 胜出
    candidates.sort(key=lambda x: -x[0])
    return candidates[0][2]


def _augment_region_code(props: dict[str, Any]) -> None:
    """
    对 company_graph_props 增量补 registeredAreaCode（GB/T 2260）。
    <p>
    数据源优先级：
    <ol>
      <li>{@code reg_province_region} 字段（若非空）</li>
      <li>{@code registered_address} 文本扫最长匹配</li>
    </ol>
    解析采用"最长代码胜出"：同一文本里同时含省/市/区时，返回最具体的 6 位区/县代码。
    """
    if "registeredAreaCode" in props:
        return
    region_dict = _load_region_dict()
    if region_dict is None:
        return
    # 优先级 1：专门的地区字段
    area = (props.get("registeredArea") or "").strip()
    code = _resolve_region_code(area, region_dict) if area else ""
    if code:
        props["registeredAreaCode"] = code
        return
    # 优先级 2：从注册地址扫描
    addr = (props.get("registeredAddress") or "").strip()
    if addr:
        code = _resolve_region_code(addr, region_dict)
        if code:
            props["registeredAreaCode"] = code


def _augment_office_region_code(props: dict[str, Any]) -> None:
    """
    对 company_graph_props 增量补 officeAreaCode（GB/T 2260）。
    <p>
    数据源优先级：
    <ol>
      <li>{@code office_province_region} 字段（若非空）</li>
      <li>{@code office_address} 文本扫最长匹配</li>
    </ol>
    与 registeredAreaCode 镜像：用于"在深圳办公地的天津母公司"这种嵌套语义。
    """
    if "officeAreaCode" in props:
        return
    region_dict = _load_region_dict()
    if region_dict is None:
        return
    area = (props.get("officeArea") or "").strip()
    code = _resolve_region_code(area, region_dict) if area else ""
    if code:
        props["officeAreaCode"] = code
        return
    addr = (props.get("officeAddress") or "").strip()
    if addr:
        code = _resolve_region_code(addr, region_dict)
        if code:
            props["officeAreaCode"] = code


def prepare_jsonl_row_for_neo4j(
    row: dict[str, Any],
    definitions: GraphNodeDefinitions | None = None,
) -> dict[str, Any]:
    """
    为 sync_neo4j 补充 company_graph_props 与各嵌套实体的 graph_props。

    嵌套数组 → 节点 Label 的映射见 ``NESTED_ARRAY_LABEL_MAP``，未声明的数组
    仍走无白名单的 v1 行为（不截断）。
    """
    defs = definitions if definitions is not None else _default_definitions()
    if "company_graph_props" not in row:
        row["company_graph_props"] = build_company_graph_props(row, defs)
    # 派生 registeredAreaCode（GB/T 2260）— 业务侧查询 "北京有哪些公司" 时按 code 过滤
    _augment_region_code(row["company_graph_props"])
    # 派生 officeAreaCode（GB/T 2260）— 覆盖"北京分公司"类用例（注册地在外地，办公地在北京）
    _augment_office_region_code(row["company_graph_props"])

    for key, label in NESTED_ARRAY_LABEL_MAP.items():
        items = row.get(key)
        if not isinstance(items, list):
            continue
        for item in items:
            if isinstance(item, dict):
                attach_graph_props(item, definitions=defs, label=label)

    return row

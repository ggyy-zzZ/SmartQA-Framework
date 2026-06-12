#!/usr/bin/env python
"""
Build qa/region-dictionary.json from modood/Administrative-divisions-of-China datasets.

Output structure:
  {
    "_meta": { source, generatedAt, totalCodes, totalAliases },
    "codes": { "<6-digit-code>": { "name", "level", "parent", "path" } },
    "aliases": { "<alias>": "<canonical-name>" },
    "reverse": { "<canonical-name>": "<6-digit-code>" }
  }
"""
import json
import os
import sys
from datetime import datetime

DATA_DIR = r"D:\project\ai\demo\data"
OUT_FILE = r"D:\project\ai\demo\src\main\resources\qa\region-dictionary.json"

# Aliases (简称 / 区域群) → 名称（必须与 codes.name 一致）
ALIASES = {
    # 直辖市 + 特别行政区（覆盖原名 + 简称）
    "京": "北京市",
    "津": "天津市",
    "沪": "上海市",
    "渝": "重庆市",
    "粤": "广东省",
    "冀": "河北省",
    "豫": "河南省",
    "云": "云南省",
    "辽": "辽宁省",
    "黑": "黑龙江省",
    "湘": "湖南省",
    "皖": "安徽省",
    "鲁": "山东省",
    "新": "新疆维吾尔自治区",
    "苏": "江苏省",
    "浙": "浙江省",
    "赣": "江西省",
    "鄂": "湖北省",
    "桂": "广西壮族自治区",
    "甘": "甘肃省",
    "晋": "山西省",
    "蒙": "内蒙古自治区",
    "陕": "陕西省",
    "吉": "吉林省",
    "闽": "福建省",
    "贵": "贵州省",
    "青": "青海省",
    "藏": "西藏自治区",
    "川": "四川省",
    "宁": "宁夏回族自治区",
    "琼": "海南省",
    "港": "香港特别行政区",
    "澳": "澳门特别行政区",
    "台": "台湾省",
    # 区域群 / 经济圈 / 同义词
    "北上广": "北京市",  # 兜底映射到首个；具体多地查询由 Cypher 处理
    "北上广深": "北京市",
    "珠三角": "广东省",
    "粤港澳大湾区": "广东省",
    "长三角": "上海市",
    "京津冀": "北京市",
    "中原": "河南省",
    "西北": "甘肃省",
    "西南": "四川省",
    "东北": "辽宁省",
    "华南": "广东省",
    "华北": "北京市",
    "华东": "上海市",
    "华中": "湖北省",
    # 省份常用简写
    "中国": "北京市",  # 注册地不写中国，兜底到北京
    "华夏": "北京市",
}


def load_json(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def build():
    provinces = load_json(os.path.join(DATA_DIR, "provinces.json"))
    cities = load_json(os.path.join(DATA_DIR, "cities.json"))
    areas = load_json(os.path.join(DATA_DIR, "areas.json"))

    # name → code
    by_name = {}

    def add(code, name, level, parent=""):
        # 去重：同名以行政代码最大的（即区级）优先
        existing = by_name.get(name)
        if existing is None or code > existing[0]:
            by_name[name] = (code, level, parent)
        # codes 始终按 code 注册
        codes[code] = {"name": name, "level": level, "parent": parent, "path": parent + name if parent else name}

    codes = {}
    # 1) 省级 (level=1)
    for p in provinces:
        add(p["code"], p["name"], level=1, parent="")
    # 2) 市级 (level=2)：parent = 省级名
    province_name_by_code = {p["code"]: p["name"] for p in provinces}
    for c in cities:
        parent_name = province_name_by_code.get(c["provinceCode"], "")
        add(c["code"], c["name"], level=2, parent=parent_name)
    # 3) 区县级 (level=3)：parent = 市级名
    city_name_by_code = {c["code"]: c["name"] for c in cities}
    for a in areas:
        parent_name = city_name_by_code.get(a["cityCode"], "")
        add(a["code"], a["name"], level=3, parent=parent_name)

    # reverse: name → code
    reverse = {name: code for name, (code, _, _) in by_name.items()}

    # aliases: alias → name
    alias_map = {}
    for alias, target_name in ALIASES.items():
        if target_name in reverse:
            alias_map[alias] = target_name
        else:
            sys.stderr.write(f"[warn] alias '{alias}' → '{target_name}' not found in codes\n")

    out = {
        "_meta": {
            "source": "modood/Administrative-divisions-of-China (provinces/cities/areas)",
            "generatedAt": datetime.now().isoformat(timespec="seconds"),
            "totalCodes": len(codes),
            "totalAliases": len(alias_map),
            "levelMeaning": {"1": "省/直辖市/自治区/特别行政区", "2": "市/地区/盟", "3": "区/县/县级市"},
        },
        "codes": codes,
        "aliases": alias_map,
        "reverse": reverse,
    }
    return out


if __name__ == "__main__":
    data = build()
    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    size = os.path.getsize(OUT_FILE)
    print(f"wrote {OUT_FILE} ({size} bytes)")
    print(f"  total codes   = {data['_meta']['totalCodes']}")
    print(f"  total aliases = {data['_meta']['totalAliases']}")
    print(f"  reverse size  = {len(data['reverse'])}")
    # 抽样
    print(f"  sample codes : 11 → {data['codes'].get('11')}, 1101 → {data['codes'].get('1101')}, 110101 → {data['codes'].get('110101')}")
    print(f"  sample alias : '京' → {data['aliases'].get('京')}, '珠三角' → {data['aliases'].get('珠三角')}")
    print(f"  sample rev   : '北京市' → {data['reverse'].get('北京市')}, '东城区' → {data['reverse'].get('东城区')}")

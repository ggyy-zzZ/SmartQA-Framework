# 企业知识库问答落地手册（4周版）

## 周计划

### 第1周：先把“答得稳”做出来

- 数据诊断：规模、噪声、异常枚举、敏感字段
- 问答协议升级：`answer/evidence/confidence/canAnswer/route`
- 引入拒答策略：证据不足时不生成确定性结论

验收：

- 所有回答都带证据
- 不确定问题可以稳定拒答

### 第2周：先筛选后回答

- 文本清洗：`enterprise.txt -> enterprise_clean.jsonl`
- 建立实体定位：公司名/简称/ID/别名匹配
- 建立字段检索：按意图取 TopK 字段片段

验收：

- 非首屏企业问题可正确命中
- 回答延迟可控（< 3s，按环境可调）

### 第3周：越用越聪明闭环

- 记录问答日志与反馈
- 按质量分生成 `knowledge_candidate`
- 审核通过发布 `knowledge_card`

验收：

- 每天可自动产出候选知识
- 审核后可被下次问答命中

### 第4周：评测驱动迭代

- 建立 100~300 条评测集
- 每次改造后自动回归评测
- 分析误答/拒答/漏召并修复路由与清洗规则

验收：

- 指标稳定达到阈值并可持续跟踪

## 最小数据表建议

- `qa_session`
- `qa_turn`
- `qa_evidence`
- `knowledge_candidate`
- `knowledge_card`
- `knowledge_card_source`

## enterprise.txt 清洗规则（建议）

## 1) 样本级过滤

- 丢弃测试公司：名称含 `测试`、明显占位值
- 丢弃结构异常：关键字段全空或枚举值为 `0`
- 丢弃乱码记录：关键字段包含大量 `?` 或非法字符

## 2) 字段级脱敏

- `账号` -> 保留后4位，其余 `*`
- 手机号 -> 保留前3后4
- 证照编号 -> 仅保留前后片段

## 3) 语义归一

- 枚举归一：英文枚举映射中文业务枚举
- 布尔归一：是/否/未知 -> true/false/null
- 日期归一：统一 `yyyy-MM-dd` 或原样+标准化字段

## 4) 输出结构（JSONL）

```json
{
  "company_id": "10422",
  "company_name": "猎道信息技术有限公司",
  "aliases": ["猎道"],
  "status": "存续",
  "entity_type": "有限责任公司",
  "entity_category": "集团内企业",
  "registered_address": "...",
  "actual_address": "...",
  "business_scope": "...",
  "product_lines": [{"module":"中台","line":"战投","relation":"运营主体"}],
  "certificates": [{"type":"营业执照","status":"生效中","expire_date":"2044-04-24"}],
  "last_updated_at": "2024-01-12",
  "source": "enterprise.txt"
}
```

## 接口契约建议

`POST /qa/ask`

- request: `question`, `sessionId`, `scene`
- response:
  - `answer`
  - `evidence[]`
  - `confidence`
  - `canAnswer`
  - `route`
  - `timestamp`

`POST /qa/feedback`

- request: `turnId`, `useful`, `comment`

## 风险与规避

- 风险：把全量文本直接喂模型导致错答  
  规避：先检索后生成 + 强制引用

- 风险：测试数据污染线上答案  
  规避：清洗阶段强过滤 + rejects 审计

- 风险：敏感数据泄露  
  规避：入库前脱敏 + 提示词屏蔽敏感字段

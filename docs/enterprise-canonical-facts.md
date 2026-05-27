# 企业常识事实（Canonical Facts）

## 用途

维护**少量、稳定、全公司共识**的事实（如集团总部、BOSS 别称），在问答检索前注入为高优先级证据，避免：

- 仅靠模糊检索命中分公司噪声；
- 用户每次口头纠偏「总部是 XXX」；
- 别称（Rick）无法关联到结构化库中的实名（戴科彬）。

与 **主动学习**（`qa_active_knowledge`）的区别：

| 维度 | 企业常识 | 主动学习 |
|------|----------|----------|
| 维护 | 配置文件，走评审后合入 | 运行时沉淀 / 人工写入 DB |
| 加载 | 启动即加载 | 按问句检索 |
| 锚点 | `anchorId` 为公司 ID / 员工 ID | `knowledge_id` |
| 来源标记 | `enterprise_canonical` | `active_learning` |

## 配置文件

路径：`src/main/resources/qa/enterprise-canonical-facts.json`

```json
{
  "facts": [
    {
      "id": "enterprise_hq_parent",
      "scope": "enterprise",
      "triggers": ["总部", "母公司"],
      "entityKind": "company",
      "anchorId": "11280",
      "displayLabel": "同道精英（天津）信息技术有限公司",
      "values": {
        "factType": "组织关系",
        "subject": "集团总部/母公司",
        "value": "同道精英（天津）信息技术有限公司"
      }
    }
  ],
  "retrievalAliases": [
    { "alias": "rick", "canonical": "戴科彬" }
  ]
}
```

- **triggers**：问句包含任一触发词即注入该条常识（中英文不区分大小写）。
- **anchorId**：检索锚点，须与 MySQL/图谱中的 `company_id` / `employee_id` 一致。
- **retrievalAliases**：仅改写**检索问句**，不改变用户可见原句。

## 证据形态

- Schema：`enterprise_fact_v1`（见 `qa/evidence-schemas.json`）
- 输出契约：`enterprise_canonical`（见 `qa/answer-output-contracts.json`）

## 运维建议

1. 变更走 PR + 业务方确认，勿在通用 prompt 里写死。
2. 单条事实保持一句话可核对；细节（证照、印章）仍走结构化检索。
3. 员工 `anchorId` 以 `employee` 表为准；花名/别名可多条 fact 或合并到 `values`。

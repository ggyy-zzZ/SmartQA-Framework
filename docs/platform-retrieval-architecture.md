# 平台化检索与会话架构（2026-06）

> 目标：系统作为**通用企业知识库问答**，不以某一测试域（证照、主体、法人等）作为硬编码主轴；新数据源（CRM、云文档、商机等）通过**配置 + Connector** 接入。

## 1. 分层原则

| 层 | 职责 | 配置位置 | 禁止 |
|----|------|----------|------|
| **会话范围** | 断链、全局列表、接续判定、经营状态推断 | `business-rules.json` → `conversationScope` | Java 里写死「忽略上文」等短语 |
| **信息需求 Need** | facet、粒度、是否列表 | `retrieval-catalog.json` → `needInferenceRules` | 用 `question.contains("存续")` 驱动 SQL |
| **意图槽位** | queryType、person、companyHints（执行参数） | `business-rules.json` → `intentRouting` | 每接入一域新增 Java `if (证照)` |
| **检索 Connector** | 图 / SQL / 向量 / 文档 | 沉淀 catalog、StructuredDataProvider | 业务表名写死在 Pipeline |
| **证据闸门** | schemaId、条数 | `answerGate` | 用自然语言记忆凑清单 |

## 2. 会话范围（`ConversationScopeSupport`）

- **断链**：`breakContextPhrases`（含「忽略之前的问题」）→ 不当作追问，不拼接会话锚点。
- **全局列表**：`globalListMarkers` + `globalListContextKeywords` → `IntentScopeNormalizer` 清空 `personName` / `companyHints`，检索不做上轮主体合并。
- **经营状态**：`operatingStatus` 区分主体状态词与证照状态词（「有效」在证照语境下不视为「存续」）。
- **接续**：`continuationMarkers` / `continuationExcludePatterns`（排除「不是存续」「所有」等）。

实现类：`com.qa.demo.qa.domain.ConversationScopeSupport`  
槽位重置：`com.qa.demo.qa.intent.IntentScopeNormalizer`

## 3. 结构化证照检索（过渡）

`PersonCertificateQueryService` 标记 `@Deprecated`，后续迁入 `StructuredDataProvider`。

当前能力：

- `retrieveByCompanyNames(names, Optional<经营状态过滤>, limit)` — 有主体 hints。
- `retrieveGlobalFiltered(Optional<经营状态>, validCertificatesOnly, limit)` — **无 hints 的全局列表**。

检索编排：`QaRetrievalPipeline.appendPersonCertificateEvidence` 根据 `ConversationScopeSupport` 选择路径，**不再**使用 `question.contains("存续")`。

图谱：`GraphContextService.queryByCertificateIntent` 支持 `companyStatusMode`（active / inactive / all）与 `certValidOnly`。

## 4. 扩展 CRM / 云文档的路径

1. 在 `retrieval-catalog.json` 增加 facet（如 `crm.customer`、`document`）与 need 规则。
2. 实现或注册 Connector（只读 SQL / 向量 collection / 文档 chunk），输出统一 `ContextChunk`。
3. 在 `business-rules.json` 增加 queryType 槽位要求（或收敛为少量能力型 queryType）。
4. **不要**复制 `FollowUpSessionHintMerger` 式域专用合并逻辑；会话只保留 **scope**（实体 ID 集、断链标记）。

## 5. 相关代码索引

| 组件 | 路径 |
|------|------|
| 会话范围 | `qa/domain/ConversationScopeSupport.java` |
| 槽位重置 | `qa/intent/IntentScopeNormalizer.java` |
| 追问合并 | `qa/intent/FollowUpSessionHintMerger.java` |
| 多轮服务 | `qa/response/QaConversationService.java` |
| 检索管道 | `qa/retrieval/QaRetrievalPipeline.java` |
| 规则配置 | `src/main/resources/qa/business-rules.json` |
| Need 目录 | `src/main/resources/qa/retrieval-catalog.json` |

## 6. 验证建议

- 断链：「忽略之前的问题，列出所有主体不是存续但证照有效的证照」→ `followUpApplied=false` 或 `scope_reset_*`，无李晓峰锚点，MySQL 证据含「主体状态范围=非存续」。
- 多轮：接上轮问「这些主体下还有哪些证」→ 仍可合并 companyHints。
- 全局：无 companyHints 时走 `retrieveGlobalFiltered` 或图谱 `queryByCertificateIntent`（非 `queryByCompanyHints` 短路）。

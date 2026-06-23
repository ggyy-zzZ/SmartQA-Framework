# Doc-RAG 实验路径

独立于主 `/qa/ask` 链路：**视图 SQL（列表/COUNT）+ Doc-RAG 向量（单公司详情）**。

## 1. 整理文档

```bash
python scripts/doc_rag/build_doc_profiles.py
```

输出 `data/knowledge/doc_rag/company_profiles.jsonl`（无业务 ID 的可读档案）。

## 2. 灌入 Qdrant

需 Qdrant 已启动。默认使用百炼 `text-embedding-v4`（1024 维），密钥自动从 `DASHSCOPE_API_KEY` 或 `application-local.properties` 读取。

```bash
python scripts/doc_rag/sync_doc_rag_qdrant.py --recreate
```

Collection 名：`enterprise_doc_rag_v1`（与 `enterprise_knowledge_v2` 隔离）。

## 2.5 创建问答视图（首次或 DDL 变更后）

```bash
mysql -uroot -proot tdcomp < data/sql/mysql/tdcomp_qa_views.sql
```

视图：`v_company_profile`、`v_person_company_roles`（中文列名、枚举已翻译）。

## 3. 问答 API

**页面（推荐）：** 打开 `http://localhost:8091/docvec-playground.html`（支持多轮，`conversationId` 自动传递）

**路由：** LLM 判断走视图 SQL 或 Doc-RAG（非关键词规则）。

```bash
curl -X POST http://localhost:8091/qa/docvec/ask \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"question":"现在有 人力资源服务许可证的主体有哪些"}'
```

多轮示例（同一 `conversationId`）：

```bash
# 第一轮
curl -X POST ... -d '{"question":"戴科彬还担任哪些法人"}'
# 第二轮带上 conversationId
curl -X POST ... -d '{"question":"其中有哪些在北京","conversationId":"<上轮返回>"}'
```

**自动路由（LLM）：**

| 问句 | 通路 |
|------|------|
| 戴科彬还是哪些主体的法人 | 视图 SQL（人员任职） |
| 其中有哪些在北京（接续上文） | 视图 SQL（人员任职 + 地区） |
| 北京有哪些公司 | 视图 SQL（地区列表） |
| 人力资源服务许可证的主体有哪些 | 视图 SQL（证照表） |
| 万仕道北京有哪些证照 | Doc-RAG |

可选参数：`topK`、`rerankTopK`（仅 RAG 通路）。

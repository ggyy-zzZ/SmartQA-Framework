# Proposal: 通用知识库助手与提示词去产品化

## 背景

早期实现与特定企业知识库形态耦合较深；产品目标是**学习用户提供的文档与简单结构化数据**，以中立助手身份作答，并在缺信息时追问补全。

## 目标

1. 将 LLM 系统提示与用户可见兜底文案抽离到 `KnowledgeAssistantPrompts`，并可通过 `qa.assistant.assistant-name` 配置称谓。  
2. 用 OpenSpec 记录目标模块（学习、意图、回答与追问、结果整理、对齐自检、沉淀与学习）。  
3. 保持现有 Qdrant / Neo4j / MySQL / MiniMax 集成不变，仅弱化文案与提示中的系统绑定感。

## 非目标

- 本变更不一次性重写为六个独立微服务；模块边界以规格与后续 tasks 渐进拆分。

## 影响

- 行为上：用户可见的「超出范围 / 证据不足」类话术更中性。  
- 配置上：新增可选 `qa.assistant.assistant-name`、`qa.assistant.max-structured-ingest-rows`（规划用）。

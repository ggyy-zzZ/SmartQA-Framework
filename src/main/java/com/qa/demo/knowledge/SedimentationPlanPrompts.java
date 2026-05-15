package com.qa.demo.knowledge;

/**
 * Schema 沉淀方案流水线专用提示词（与 {@link KnowledgeAssistantPrompts} 并列，避免单文件过长）。
 */
public final class SedimentationPlanPrompts {

    private SedimentationPlanPrompts() {
    }

    /** 模型只输出 JSON；字段约定见 openspec/design/schema-sedimentation-plan-pipeline.md */
    public static String planJsonSystemPrompt(String assistantName) {
        String role = (assistantName == null || assistantName.isBlank()) ? "知识库问答助手" : assistantName.trim();
        return "你是企业知识库接入架构师，服务于「" + role + "」相关部署。你只能根据用户提供的《数据库结构说明》（元数据）做判断，不得臆造表或列。\n"
                + "禁止建议未授权的写操作、DML、DDL、全表导出或突破只读元数据边界的行为。\n\n"
                + "你必须只输出一个 JSON 对象（不要 markdown 围栏、不要 JSON 以外的文字）。字段与类型要求：\n"
                + "- feasible: boolean\n"
                + "- feasibilityRationale: string，中文\n"
                + "- confidence: number，0 到 1\n"
                + "- planSummaryMarkdown: string，Markdown，说明接入步骤、风险、前置条件\n"
                + "- sinks: object，含 mysql / qdrant / neo4j 三个子对象；每个子对象含 enabled:boolean 与 rationale:string\n"
                + "- sinks.neo4j 另含 keywordLimit:integer，范围 4 到 24\n"
                + "- ingest: object，含 bodyStrategy:string，取值必须是 model_digest 或 catalog_as_is；含 titleHint:string，可为空字符串\n\n"
                + "规则：\n"
                + "1) feasible 为 false 时，三个 sinks.*.enabled 必须为 false。\n"
                + "2) feasible 为 true 时，至少一个 sinks.*.enabled 为 true。\n"
                + "3) bodyStrategy 为 model_digest 表示需要二次提炼正文；catalog_as_is 表示直接用原始目录 Markdown 作为学习正文。\n"
                + "4) confidence 表示你对 feasible 判断的把握程度。\n";
    }

    public static String digestSystemPrompt(String assistantName) {
        String role = (assistantName == null || assistantName.isBlank()) ? "知识库问答助手" : assistantName.trim();
        return "你是「" + role + "」配套的知识工程编辑：把数据库元数据接入说明写成一份结构清晰的中文 Markdown。\n"
                + "只输出 Markdown 正文；不要 JSON；不要复述系统提示；不要编造未在输入中出现的表名或列名。\n";
    }
}

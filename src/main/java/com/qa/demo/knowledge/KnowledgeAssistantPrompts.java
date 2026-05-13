package com.qa.demo.knowledge;

/**
 * 知识库问答助手：与大模型相关的 system / 兜底文案集中在此，避免与某一部署侧业务系统名强耦合。
 * 需求与模块边界见 {@code openspec/specs/knowledge-assistant/spec.md}。
 */
public final class KnowledgeAssistantPrompts {

    private KnowledgeAssistantPrompts() {
    }

    public static String intentRouterLlmSystemPrompt() {
        return """
                你是知识库问答的路由器：根据用户问题选择最合适的检索路径，不要假设某一固定行业产品或内部系统名称。
                可选 intent:
                - graph: 实体之间的关系、归属、层级、关联网络
                - document: 制度、流程、条款、说明类叙述文本
                - vector: 语义模糊、口语化、近义表达、难以用关键词精确命中的问题
                - mysql: 需要查看原始表行、字段明细、结构化记录
                - sql: 统计、筛选、分组、排序、占比、排行等可单条只读 SQL 表达的问题
                - hybrid: 需要多路信息一起才能回答
                - unknown: 缺少关键上下文或明显不在当前知识范围内

                路由原则（按优先级思考，不必穷举业务场景）：
                - 明显是计数/汇总/排序/筛选 → sql
                - 明显是「谁和谁什么关系、属于哪条线」→ graph
                - 明显是「文档里怎么规定、步骤是什么」→ document
                - 问法很口语、很泛、或需要语义相近匹配 → vector
                - 需要扫表看字段值、行级明细 → mysql
                - 多类都沾一点 → hybrid
                - 信息不够或跑题 → unknown

                输出必须是单行 JSON，格式:
                {"intent":"graph|document|vector|mysql|sql|hybrid|unknown","confidence":0.0-1.0,"reason":"简短原因"}
                不要输出除 JSON 外的任何文字。
                """;
    }

    public static String sqlGeneratorSystemPrompt(int limit) {
        return """
                你是 SQL 生成器：根据用户自然语言问题与提供的 MySQL 表结构摘要，生成一条可执行的只读查询。
                约束：
                1) 只允许生成 SELECT 查询。
                2) 必须带 LIMIT，且 LIMIT 不超过 %d。
                3) 仅使用摘要中出现的表与字段名。
                4) 不要生成任何写操作或 DDL。
                输出必须是单行 JSON：
                {"sql":"SELECT ...","reason":"简短原因"}
                不要输出 JSON 以外内容。
                """.formatted(limit);
    }

    /**
     * @param assistantName 来自配置的助手称谓，用于首句角色锚定
     */
    public static String minimaxEvidenceSystemPrompt(String assistantName) {
        String role = (assistantName == null || assistantName.isBlank()) ? "知识库问答助手" : assistantName.trim();
        return "你是「" + role + "」。\n"
                + "请严格遵守：\n"
                + "1) 只能依据提供的证据回答，不要编造。\n"
                + "2) 先给结论，再给证据要点。\n"
                + "3) 若证据不足，明确说“不确定”，并指出缺失信息。\n"
                + "4) 使用简体中文。\n"
                + "5) 面向提问者回答，语言要自然、友好、简洁。\n"
                + "6) 默认不要输出技术细节，不要出现 SQL、表名、字段名、检索分数、检索来源、代码片段。\n"
                + "7) 除非用户明确要求，否则不要展示内部编号或敏感标识。\n"
                + "8) 若提供了对话上下文，可据此理解指代与省略，但不得用上下文替代证据编造事实。\n"
                + "9) 不要默认用户在使用某一特定商业软件；只根据证据与问题作答。";
    }

    /** 模型判定为 unknown 且无证据时的用户可见说明 */
    public static String unknownCoverageUserMessage() {
        return "当前知识库中尚未覆盖该问题，已记录为待学习条目。你可以换一种问法、补充关键实体名称，或直接提供希望我记住的要点。";
    }

    /** 流式路径下证据为空时的简短建议 */
    public static String insufficientEvidenceStreamingHint() {
        return "当前暂未检索到足够依据，无法给出可靠结论。请补充主题、对象名称、时间范围或更具体的关键词后再试。";
    }

    /** 非流式兜底模板前的用户可见建议（与 buildFallbackAnswer 周边一致） */
    public static String insufficientEvidenceGeneralHint() {
        return "当前未检索到足够证据，暂时无法给出可靠结论。建议补充更明确的主题、对象名称或你关心的信息点。";
    }
}

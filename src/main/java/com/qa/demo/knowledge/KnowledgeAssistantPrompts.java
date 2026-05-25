package com.qa.demo.knowledge;

/**
 * 知识库问答助手：与大模型相关的 system / 兜底文案集中在此，避免与某一部署侧业务系统名强耦合。
 * 需求与模块边界见 {@code openspec/specs/knowledge-assistant/spec.md}。
 */
public final class KnowledgeAssistantPrompts {

    private KnowledgeAssistantPrompts() {
    }

    public static String intentRouterLlmSystemPrompt() {
        return "你是企业知识库问答的「意图与实体路由器」。根据用户问题选择检索通道，并抽取检索所需实体，"
                + "避免依赖固定问句模板（如必须把「公司」说成「主体」才能识别）。\n\n"
                + "可选 intent（检索通道）:\n"
                + "- graph: 人-公司任职/关系、股东、关联、层级、归属\n"
                + "- document: 制度、流程、政策、步骤类叙述\n"
                + "- vector: 口语化、语义相近、难以关键词精确命中\n"
                + "- mysql: 表行/字段明细、结构化记录\n"
                + "- sql: 统计、计数、分组、排序、占比、排行\n"
                + "- hybrid: 需多路证据组合\n"
                + "- unknown: 缺关键信息或明显超范围\n\n"
                + "可选 queryType（查询形态，与 intent 独立）:\n"
                + "- person_role_list: 某人担任哪些主体/公司的法人、董事、监事等（列表型）\n"
                + "- company_profile: 某一公司/主体的概况、状态、地址、证照等\n"
                + "- shareholder: 股东、持股、股权结构\n"
                + "- relation: 关联、穿透、母子关系\n"
                + "- aggregate: 数量、统计、汇总\n"
                + "- policy: 制度流程\n"
                + "- semantic: 泛语义检索\n"
                + "- mixed: 多形态混合\n"
                + "- unknown: 无法判断形态\n\n"
                + "roleFocus（任职类问题时填写，否则填 any）: legal_rep | director | shareholder | any\n"
                + "personName: 问句中的自然人姓名（2-12字），无则空字符串\n"
                + "companyHints: 问句中的公司/主体名称片段数组，无则 []\n\n"
                + "示例：「戴科彬是哪些主体的法人」-> intent=graph, queryType=person_role_list, personName=戴科彬, roleFocus=legal_rep\n\n"
                + "输出必须是单行 JSON，字段齐全：\n"
                + "{\"intent\":\"graph|...\",\"confidence\":0.0-1.0,\"reason\":\"简短原因\","
                + "\"queryType\":\"person_role_list|...\",\"personName\":\"\",\"companyHints\":[],\"roleFocus\":\"any\"}\n"
                + "不要输出 JSON 以外的任何文字。\n";
    }

    public static String sqlGeneratorSystemPrompt(int limit) {
        return String.format(
                "你是 SQL 生成器：根据用户自然语言问题与提供的 MySQL 表结构摘要，生成一条可执行的只读查询。\n"
                        + "约束：\n"
                        + "1) 只允许生成 SELECT 查询。\n"
                        + "2) 必须带 LIMIT，且 LIMIT 不超过 %d。\n"
                        + "3) 仅使用摘要中出现的表与字段名。\n"
                        + "4) 不要生成任何写操作或 DDL。\n"
                        + "输出必须是单行 JSON：\n"
                        + "{\"sql\":\"SELECT ...\",\"reason\":\"简短原因\"}\n"
                        + "不要输出 JSON 以外内容。\n",
                limit
        );
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
                + "9) 不要默认用户在使用某一特定商业软件；只根据证据与问题作答。\n"
                + "10) 若问题是「某人担任哪些主体/公司的法人、董事等」且证据含多家公司，"
                + "须逐条列出证据中的全部主体，不得只列其中一部分；可先给总数再列清单。\n"
                + "11) 结论中的家数/条数须与证据条数一致，勿凭记忆多写或少写。";
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

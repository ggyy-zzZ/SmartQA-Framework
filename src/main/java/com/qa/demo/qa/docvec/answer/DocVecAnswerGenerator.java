package com.qa.demo.qa.docvec.answer;

import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.docvec.config.DocVecProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于档案全文或视图 SQL 结果的 LLM 生成。
 */
@Service
public class DocVecAnswerGenerator {

    private static final String RAG_SYSTEM_PROMPT = """
            你是企业主体信息问答助手。用户问题只能依据【检索档案】作答，禁止编造。
            规则：
            1. 档案中未出现的信息，明确写「档案中未找到」或「不确定」，不要猜测。
            2. 涉及证照、印章、人员、股东时，尽量逐条列出档案中的原文要点。
            3. 若多条档案相关，综合后分点回答；优先与问句最匹配的公司。
            4. 回答使用简体中文，结构清晰，先结论后依据。
            """;

    private static final String SQL_SYSTEM_PROMPT = """
            你是企业主体信息问答助手。用户问题只能依据【SQL 查询结果】作答，禁止编造。
            规则：
            1. 必须完整列出查询结果中的每一条公司/记录，不得遗漏、不得只列部分示例。
            2. 结果行数与证据条数一致；若证据有 N 条，回答中应体现 N 条（可用表格或编号列表）。
            3. 未在结果中出现的信息不要猜测。
            4. 回答使用简体中文，先给出总数结论，再列明细。
            """;

    private final MiniMaxClient miniMaxClient;
    private final DocVecProperties properties;

    public DocVecAnswerGenerator(MiniMaxClient miniMaxClient, DocVecProperties properties) {
        this.miniMaxClient = miniMaxClient;
        this.properties = properties;
    }

    public String generate(String question, List<ContextChunk> evidence, boolean sqlMode) {
        if (evidence == null || evidence.isEmpty()) {
            return sqlMode
                    ? "视图 SQL 未查询到匹配记录，无法回答。"
                    : "未检索到相关公司档案，无法回答。请确认向量库已灌入（enterprise_doc_rag_v1）。";
        }
        String system = sqlMode ? SQL_SYSTEM_PROMPT : RAG_SYSTEM_PROMPT;
        int maxChars = sqlMode ? properties.getMaxProfileCharsForSql() : properties.getMaxProfileCharsForLlm();
        String userContent = buildUserContent(question, evidence, sqlMode, maxChars);
        return miniMaxClient.completeChat(system, userContent);
    }

    private static String buildUserContent(
            String question,
            List<ContextChunk> evidence,
            boolean sqlMode,
            int maxChars
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户问题】\n").append(question == null ? "" : question.trim()).append("\n\n");
        sb.append(sqlMode ? "【SQL 查询结果】" : "【检索档案】").append("\n");
        sb.append("共 ").append(evidence.size()).append(" 条记录。\n\n");
        int used = 0;
        for (int i = 0; i < evidence.size(); i++) {
            ContextChunk chunk = evidence.get(i);
            String block = formatEvidenceBlock(i + 1, chunk, sqlMode);
            if (!sqlMode && used + block.length() > maxChars) {
                break;
            }
            if (sqlMode && used + block.length() > maxChars) {
                sb.append("\n（后续 ").append(evidence.size() - i).append(" 条因长度限制未写入，请基于已列条目回答并注明可能不完整）\n");
                break;
            }
            sb.append(block).append("\n\n");
            used += block.length();
        }
        return sb.toString().trim();
    }

    private static String formatEvidenceBlock(int index, ContextChunk chunk, boolean sqlMode) {
        StringBuilder sb = new StringBuilder();
        if (sqlMode) {
            sb.append(index).append(". ");
            if (chunk.displayLabel() != null && !chunk.displayLabel().isBlank()) {
                sb.append(chunk.displayLabel()).append(" | ");
            }
            sb.append(chunk.snippet() == null ? "" : chunk.snippet().trim());
            return sb.toString();
        }
        sb.append("--- 档案 ").append(index);
        if (chunk.displayLabel() != null && !chunk.displayLabel().isBlank()) {
            sb.append("：").append(chunk.displayLabel());
        }
        sb.append(" ---\n");
        sb.append(chunk.snippet() == null ? "" : chunk.snippet().trim());
        return sb.toString();
    }
}

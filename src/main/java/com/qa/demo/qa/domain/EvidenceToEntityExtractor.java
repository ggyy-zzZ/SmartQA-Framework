package com.qa.demo.qa.domain;

import com.qa.demo.qa.core.ContextChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从检索证据中提取结构化实体，供多轮会话后续使用。
 * <p>
 * 例如首轮检索到人物担任多家公司法人，第二轮可以以这些公司为范围检索证照。
 */
public final class EvidenceToEntityExtractor {

    private static final Pattern PERSON_ROLE_SOURCE = Pattern.compile(
            "neo4j-person-role|neo4j-boundary|mysql-sql-person-role|sql-person-role|person_role");
    private static final Pattern CERTIFICATE_SOURCE = Pattern.compile("mysql-.*-certificate|certificate");
    private static final Pattern PERSON_SOURCE = Pattern.compile("employee_identity|employee_base|neo4j-person");

    private EvidenceToEntityExtractor() {
    }

    /**
     * 从证据列表中提取实体。
     *
     * @param evidence 检索返回的证据列表
     * @param queryType 当前查询类型，用于判断优先提取哪些实体
     * @return 按类型分组的实体列表
     */
    public static Map<String, List<EntityRef>> extractFrom(List<ContextChunk> evidence, String queryType) {
        Map<String, List<EntityRef>> result = new LinkedHashMap<>();

        if (evidence == null || evidence.isEmpty()) {
            return result;
        }

        // 按来源分类提取
        List<EntityRef> companies = new ArrayList<>();
        List<EntityRef> persons = new ArrayList<>();
        List<EntityRef> certificates = new ArrayList<>();

        for (ContextChunk chunk : evidence) {
            String source = chunk.source() != null ? chunk.source() : "";
            String anchorId = chunk.anchorId() != null ? chunk.anchorId() : "";
            String displayLabel = chunk.displayLabel() != null ? chunk.displayLabel() : "";

            // 公司实体：从人物任职角色来源提取
            if (PERSON_ROLE_SOURCE.matcher(source).find() && !anchorId.isEmpty()) {
                String status = extractStatus(chunk.snippet());
                companies.add(EntityRef.company(anchorId, displayLabel, status));
            }

            // 人物实体：从员工身份来源提取
            if (PERSON_SOURCE.matcher(source).find() && !anchorId.isEmpty()) {
                persons.add(EntityRef.person(anchorId, displayLabel));
            }

            // 证照实体：从证照来源提取
            if (CERTIFICATE_SOURCE.matcher(source).find() && !anchorId.isEmpty()) {
                certificates.add(EntityRef.certificate(anchorId, displayLabel));
            }
        }

        // 去重后放入结果
        if (!companies.isEmpty()) {
            result.put(EntityRef.TYPE_COMPANY, deduplicate(companies));
        }
        if (!persons.isEmpty()) {
            result.put(EntityRef.TYPE_PERSON, deduplicate(persons));
        }
        if (!certificates.isEmpty()) {
            result.put(EntityRef.TYPE_CERTIFICATE, deduplicate(certificates));
        }

        return result;
    }

    /**
     * 根据 queryType 决定提取哪些实体。
     */
    public static Map<String, List<EntityRef>> extractForQueryType(List<ContextChunk> evidence, String queryType) {
        Map<String, List<EntityRef>> all = extractFrom(evidence, queryType);
        Map<String, List<EntityRef>> filtered = new LinkedHashMap<>();

        if ("person_role_list".equals(queryType) || "person_certificate_list".equals(queryType)) {
            // 人物相关查询：提取公司和人物
            if (all.containsKey(EntityRef.TYPE_COMPANY)) {
                filtered.put(EntityRef.TYPE_COMPANY, all.get(EntityRef.TYPE_COMPANY));
            }
            if (all.containsKey(EntityRef.TYPE_PERSON)) {
                filtered.put(EntityRef.TYPE_PERSON, all.get(EntityRef.TYPE_PERSON));
            }
        } else if ("company_certificate".equals(queryType)) {
            // 公司证照查询：提取公司
            if (all.containsKey(EntityRef.TYPE_COMPANY)) {
                filtered.put(EntityRef.TYPE_COMPANY, all.get(EntityRef.TYPE_COMPANY));
            }
        } else {
            // 其他查询：返回所有提取的实体
            filtered = all;
        }

        return filtered;
    }

    /**
     * 从 snippet 中提取状态（如"存续"、"吊销"）。
     */
    private static String extractStatus(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return null;
        }
        String normalized = snippet.replace('；', ';');
        String[] parts = normalized.split(";");
        for (String raw : parts) {
            String part = raw == null ? "" : raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.startsWith("status=")) {
                String value = part.substring("status=".length()).trim();
                return value.isEmpty() ? null : value;
            }
            if (part.startsWith("状态=")) {
                String value = part.substring("状态=".length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        if (snippet.contains("存续")) {
            return "存续";
        }
        if (snippet.contains("吊销")) {
            return "吊销";
        }
        if (snippet.contains("注销")) {
            return "注销";
        }
        if (snippet.contains("开业")) {
            return "开业";
        }
        return null;
    }

    /**
     * 去重（按 ID）。
     */
    private static List<EntityRef> deduplicate(List<EntityRef> entities) {
        Map<String, EntityRef> seen = new LinkedHashMap<>();
        for (EntityRef e : entities) {
            if (e.id() != null && !e.id().isBlank() && !seen.containsKey(e.id())) {
                seen.put(e.id(), e);
            }
        }
        return List.copyOf(seen.values());
    }
}
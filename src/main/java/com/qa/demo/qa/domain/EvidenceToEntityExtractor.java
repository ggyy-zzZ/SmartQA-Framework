package com.qa.demo.qa.domain;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;

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
            "neo4j-person-role|neo4j-boundary|mysql-sql-person-role|sql-person-role|person_role|mysql-structured-role|structured_role");
    private static final Pattern CERTIFICATE_SOURCE = Pattern.compile("mysql-.*-certificate|certificate");
    private static final Pattern PERSON_SOURCE = Pattern.compile("employee_identity|employee_base|neo4j-person");

    private EvidenceToEntityExtractor() {
    }

    /**
     * 从证据列表中提取实体。
     */
    public static Map<String, List<EntityRef>> extractFrom(List<ContextChunk> evidence) {
        Map<String, List<EntityRef>> result = new LinkedHashMap<>();

        if (evidence == null || evidence.isEmpty()) {
            return result;
        }

        List<EntityRef> companies = new ArrayList<>();
        List<EntityRef> persons = new ArrayList<>();
        List<EntityRef> certificates = new ArrayList<>();

        for (ContextChunk chunk : evidence) {
            String source = chunk.source() != null ? chunk.source() : "";
            String anchorId = chunk.anchorId() != null ? chunk.anchorId() : "";
            String displayLabel = chunk.displayLabel() != null ? chunk.displayLabel() : "";

            if (PERSON_ROLE_SOURCE.matcher(source).find() && !anchorId.isEmpty()) {
                String status = extractStatus(chunk.snippet());
                companies.add(EntityRef.company(anchorId, displayLabel, status));
            }

            if (PERSON_SOURCE.matcher(source).find() && !anchorId.isEmpty()) {
                persons.add(EntityRef.person(anchorId, displayLabel));
            }

            if (CERTIFICATE_SOURCE.matcher(source).find() && !anchorId.isEmpty()) {
                certificates.add(EntityRef.certificate(anchorId, displayLabel));
            }
        }

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
     * 根据信息需求决定提取哪些实体。
     */
    public static Map<String, List<EntityRef>> extractForNeed(List<ContextChunk> evidence, InformationNeed need) {
        Map<String, List<EntityRef>> all = extractFrom(evidence);
        Map<String, List<EntityRef>> filtered = new LinkedHashMap<>();
        if (need == null) {
            return all;
        }
        String facet = need.facet() == null ? "" : need.facet().trim().toLowerCase();
        String granularity = need.granularity() == null ? "" : need.granularity().trim().toLowerCase();
        if ("role".equals(facet)) {
            if (all.containsKey(EntityRef.TYPE_COMPANY)) {
                filtered.put(EntityRef.TYPE_COMPANY, all.get(EntityRef.TYPE_COMPANY));
            }
            if (all.containsKey(EntityRef.TYPE_PERSON)) {
                filtered.put(EntityRef.TYPE_PERSON, all.get(EntityRef.TYPE_PERSON));
            }
            return filtered;
        }
        if ("certificate".equals(facet)) {
            if ("list".equals(granularity)) {
                if (all.containsKey(EntityRef.TYPE_COMPANY)) {
                    filtered.put(EntityRef.TYPE_COMPANY, all.get(EntityRef.TYPE_COMPANY));
                }
                if (all.containsKey(EntityRef.TYPE_PERSON)) {
                    filtered.put(EntityRef.TYPE_PERSON, all.get(EntityRef.TYPE_PERSON));
                }
                return filtered;
            }
            if (InformationNeed.GRANULARITY_INSTANCE.equals(granularity)) {
                if (all.containsKey(EntityRef.TYPE_COMPANY)) {
                    filtered.put(EntityRef.TYPE_COMPANY, all.get(EntityRef.TYPE_COMPANY));
                }
                if (all.containsKey(EntityRef.TYPE_CERTIFICATE)) {
                    filtered.put(EntityRef.TYPE_CERTIFICATE, all.get(EntityRef.TYPE_CERTIFICATE));
                }
                return filtered;
            }
        }
        if ("profile".equals(facet)) {
            if (all.containsKey(EntityRef.TYPE_COMPANY)) {
                filtered.put(EntityRef.TYPE_COMPANY, all.get(EntityRef.TYPE_COMPANY));
            }
            return filtered;
        }
        return all;
    }

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

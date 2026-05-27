package com.qa.demo.qa.retrieval.graph;

import com.qa.demo.qa.core.ContextChunk;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱：人-任职关系查询（Cypher 模板集中维护）。
 */
public final class GraphPersonRoleQuery {

    private GraphPersonRoleQuery() {
    }

    public static List<ContextChunk> execute(
            Session session,
            String personHint,
            String roleFocus,
            int topK,
            String fieldLabel
    ) {
        if (personHint == null || personHint.isBlank()) {
            return List.of();
        }
        Result result = session.run(
                """
                MATCH (p:Person)-[r:HAS_ROLE_IN]->(c:Company)
                WHERE p.name CONTAINS $personHint
                  AND (
                    $roleFocus = 'any'
                    OR ($roleFocus = 'legal_rep' AND (r.role CONTAINS '法定代表' OR r.role CONTAINS '法人'))
                    OR ($roleFocus = 'director' AND r.role CONTAINS '董事')
                    OR ($roleFocus = 'supervisor' AND r.role CONTAINS '监事')
                  )
                RETURN c.companyId AS companyId,
                       c.name AS companyName,
                       c.status AS status,
                       p.name AS personName,
                       r.role AS role
                ORDER BY c.name
                LIMIT $topK
                """,
                org.neo4j.driver.Values.parameters(
                        "personHint", personHint,
                        "roleFocus", roleFocus == null ? "any" : roleFocus,
                        "topK", topK
                )
        );
        List<ContextChunk> chunks = new ArrayList<>();
        while (result.hasNext()) {
            Record record = result.next();
            String snippet = "状态=" + safeString(record, "status")
                    + "; 关键人=" + safeString(record, "personName") + "(" + safeString(record, "role") + ")";
            chunks.add(ContextChunk.ofCompany(
                    safeString(record, "companyId"),
                    safeString(record, "companyName"),
                    fieldLabel,
                    snippet,
                    22.0,
                    "neo4j-person-role"
            ));
        }
        return chunks;
    }

    /**
     * 按姓名片段 + 任职类型列出图谱中 DISTINCT 人名（用于敬称/姓级指称消歧）。
     */
    public static List<String> listDistinctPersonNames(
            Session session,
            String nameHint,
            String roleFocus,
            int limit
    ) {
        if (nameHint == null || nameHint.isBlank()) {
            return List.of();
        }
        int top = Math.max(1, Math.min(limit, 20));
        Result result = session.run(
                """
                MATCH (p:Person)-[r:HAS_ROLE_IN]->(:Company)
                WHERE p.name CONTAINS $nameHint
                  AND (
                    $roleFocus = 'any'
                    OR ($roleFocus = 'legal_rep' AND (r.role CONTAINS '法定代表' OR r.role CONTAINS '法人'))
                    OR ($roleFocus = 'director' AND r.role CONTAINS '董事')
                    OR ($roleFocus = 'supervisor' AND r.role CONTAINS '监事')
                  )
                RETURN DISTINCT p.name AS personName
                ORDER BY personName
                LIMIT $top
                """,
                org.neo4j.driver.Values.parameters(
                        "nameHint", nameHint,
                        "roleFocus", roleFocus == null ? "any" : roleFocus,
                        "top", top
                )
        );
        List<String> names = new ArrayList<>();
        while (result.hasNext()) {
            Record record = result.next();
            String name = safeString(record, "personName");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private static String safeString(Record record, String key) {
        if (record.get(key).isNull()) {
            return "";
        }
        return record.get(key).asString("");
    }
}

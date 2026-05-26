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
            chunks.add(new ContextChunk(
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

    private static String safeString(Record record, String key) {
        if (record.get(key).isNull()) {
            return "";
        }
        return record.get(key).asString("");
    }
}

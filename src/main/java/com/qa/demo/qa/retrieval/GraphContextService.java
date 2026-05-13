package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.ContextChunk;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GraphContextService {

    private final Driver neo4jDriver;

    public GraphContextService(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK) {
        int limitedTopK = Math.max(1, Math.min(topK, 10));
        List<String> companyHints = extractCompanyHints(question);
        List<ContextChunk> chunks = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            if (!companyHints.isEmpty()) {
                chunks.addAll(queryByCompanyHints(session, question, companyHints, limitedTopK));
                // If question carries explicit company hints but graph has no hit,
                // fallback to document retrieval in controller instead of random intent samples.
                return chunks;
            }
            chunks.addAll(queryByIntentKeywords(session, question, limitedTopK));
        }
        return chunks;
    }

    public boolean hasExplicitCompanyHint(String question) {
        return !extractCompanyHints(question).isEmpty();
    }

    public List<CompanyCandidate> suggestCompanyCandidates(String question, int limit) {
        int top = Math.max(1, Math.min(limit, 8));
        List<String> hints = extractCompanyHints(question);
        try (Session session = neo4jDriver.session()) {
            Result result;
            if (!hints.isEmpty()) {
                result = session.run(
                        """
                        UNWIND $hints AS hint
                        MATCH (c:Company)
                        WHERE toLower(c.name) CONTAINS toLower(hint)
                           OR toLower(coalesce(c.shortName, '')) CONTAINS toLower(hint)
                           OR c.companyId = hint
                        RETURN DISTINCT c.companyId AS companyId, c.name AS companyName, c.status AS status
                        LIMIT $topK
                        """,
                        org.neo4j.driver.Values.parameters("hints", hints, "topK", top)
                );
            } else {
                result = session.run(
                        """
                        MATCH (c:Company)
                        WHERE c.name IS NOT NULL
                        RETURN c.companyId AS companyId, c.name AS companyName, c.status AS status
                        ORDER BY c.name
                        LIMIT $topK
                        """,
                        org.neo4j.driver.Values.parameters("topK", top)
                );
            }
            List<CompanyCandidate> candidates = new ArrayList<>();
            while (result.hasNext()) {
                Record r = result.next();
                candidates.add(new CompanyCandidate(
                        safeString(r, "companyId"),
                        safeString(r, "companyName"),
                        safeString(r, "status")
                ));
            }
            return candidates;
        }
    }

    private List<ContextChunk> queryByCompanyHints(
            Session session,
            String question,
            List<String> companyHints,
            int topK
    ) {
        Result result = session.run(
                """
                UNWIND $hints AS hint
                MATCH (c:Company)
                WHERE toLower(c.name) CONTAINS toLower(hint)
                   OR toLower(coalesce(c.shortName, '')) CONTAINS toLower(hint)
                   OR c.companyId = hint
                OPTIONAL MATCH (c)<-[roleRel:HAS_ROLE_IN]-(p:Person)
                OPTIONAL MATCH (s:Shareholder)-[shareRel:HOLDS_SHARES_IN]->(c)
                OPTIONAL MATCH (c)-[prodRel:BELONGS_TO_PRODUCT]->(pl:ProductLine)
                WITH c,
                     collect(DISTINCT roleRel.role + ":" + p.name)[0..3] AS roles,
                     collect(DISTINCT s.name + "(" + coalesce(shareRel.ratio, "?") + ")")[0..3] AS shareholders,
                     collect(DISTINCT pl.line + "(" + coalesce(prodRel.relation, "") + ")")[0..3] AS productLines
                RETURN c.companyId AS companyId,
                       c.name AS companyName,
                       c.status AS status,
                       c.entityType AS entityType,
                       c.entityCategory AS entityCategory,
                       c.registeredAddress AS registeredAddress,
                       c.businessScope AS businessScope,
                       roles AS roles,
                       shareholders AS shareholders,
                       productLines AS productLines
                LIMIT $topK
                """,
                org.neo4j.driver.Values.parameters(
                        "hints", companyHints,
                        "topK", topK
                )
        );
        List<ContextChunk> chunks = new ArrayList<>();
        while (result.hasNext()) {
            Record record = result.next();
            String companyId = safeString(record, "companyId");
            String companyName = safeString(record, "companyName");
            String field = detectField(question);
            String snippet = "状态=" + safeString(record, "status")
                    + "; 类型=" + safeString(record, "entityType")
                    + "; 分类=" + safeString(record, "entityCategory")
                    + "; 地址=" + safeString(record, "registeredAddress")
                    + "; 产品线=" + safeList(record, "productLines")
                    + "; 股东=" + safeList(record, "shareholders")
                    + "; 关键人=" + safeList(record, "roles");
            double score = 18.0 + Math.min(4.0, companyName.length() / 10.0);
            chunks.add(new ContextChunk(companyId, companyName, field, snippet, score, "neo4j"));
        }
        return chunks;
    }

    private List<ContextChunk> queryByIntentKeywords(Session session, String question, int topK) {
        List<String> labels = intentKeywords(question);
        if (labels.isEmpty()) {
            return List.of();
        }
        Result result = session.run(
                """
                MATCH (c:Company)
                WHERE c.status IS NOT NULL
                OPTIONAL MATCH (c)-[prodRel:BELONGS_TO_PRODUCT]->(pl:ProductLine)
                WITH c, collect(DISTINCT pl.line)[0..2] AS productLines
                RETURN c.companyId AS companyId,
                       c.name AS companyName,
                       c.status AS status,
                       c.entityType AS entityType,
                       c.entityCategory AS entityCategory,
                       productLines AS productLines
                LIMIT $topK
                """,
                org.neo4j.driver.Values.parameters("topK", topK)
        );
        List<ContextChunk> chunks = new ArrayList<>();
        while (result.hasNext()) {
            Record record = result.next();
            String field = labels.isEmpty() ? "概要" : String.join("/", labels);
            String snippet = "状态=" + safeString(record, "status")
                    + "; 类型=" + safeString(record, "entityType")
                    + "; 分类=" + safeString(record, "entityCategory")
                    + "; 产品线=" + safeList(record, "productLines");
            chunks.add(new ContextChunk(
                    safeString(record, "companyId"),
                    safeString(record, "companyName"),
                    field,
                    snippet,
                    8.0,
                    "neo4j-intent"
            ));
        }
        return chunks;
    }

    private List<String> extractCompanyHints(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        Set<String> hints = new HashSet<>();
        String[] tokens = q.split("[^\\p{L}\\p{N}]+");
        for (String token : tokens) {
            if (token.length() >= 4 && token.matches("\\d+")) {
                hints.add(token);
            }
        }
        for (String suffix : List.of("有限公司", "分公司", "集团", "公司")) {
            int idx = question.indexOf(suffix);
            if (idx > 0) {
                int start = Math.max(0, idx - 14);
                String hint = question.substring(start, idx + suffix.length()).trim();
                if (hint.length() >= 4) {
                    hints.add(hint);
                }
            }
        }
        return new ArrayList<>(hints);
    }

    private List<String> intentKeywords(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>();
        if (q.contains("经营状态") || q.contains("存续") || q.contains("注销")) {
            fields.add("状态");
        }
        if (q.contains("股东") || q.contains("持股") || q.contains("股权")) {
            fields.add("出资与持股");
        }
        if (q.contains("证照") || q.contains("许可证") || q.contains("执照")) {
            fields.add("资质与许可");
        }
        if (q.contains("地址") || q.contains("注册地") || q.contains("办公")) {
            fields.add("地址");
        }
        return fields;
    }

    private String detectField(String question) {
        List<String> fields = intentKeywords(question);
        if (fields.isEmpty()) {
            return "概要";
        }
        return String.join("/", fields);
    }

    private String safeString(Record record, String key) {
        if (record.get(key).isNull()) {
            return "";
        }
        return record.get(key).asString("");
    }

    private String safeList(Record record, String key) {
        if (record.get(key).isNull()) {
            return "[]";
        }
        return record.get(key).asList().toString();
    }
}

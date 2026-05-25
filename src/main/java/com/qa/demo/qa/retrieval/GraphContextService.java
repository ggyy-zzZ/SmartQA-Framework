package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
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
        return retrieveTopChunks(question, topK, null);
    }

    /**
     * @param intent 来自 {@link com.qa.demo.qa.intent.IntentRouterService} 的 LLM/规则抽取结果，可减少问句模板硬编码依赖
     */
    public List<ContextChunk> retrieveTopChunks(String question, int topK, IntentDecision intent) {
        boolean personRoleList = intent != null && intent.isPersonRoleListQuery();
        int limitedTopK = personRoleList
                ? Math.max(1, topK)
                : Math.max(1, Math.min(topK, 10));
        List<String> companyHints = resolveCompanyHints(question, intent);
        List<ContextChunk> chunks = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            String personHint = resolvePersonHint(question, intent);
            if (personHint != null && shouldQueryPersonRole(question, intent)) {
                String roleFocus = resolveRoleFocus(question, intent);
                chunks.addAll(queryByPersonAndRole(session, question, personHint, limitedTopK, roleFocus));
                if (!chunks.isEmpty()) {
                    return chunks;
                }
            }
            if (!companyHints.isEmpty()) {
                chunks.addAll(queryByCompanyHints(session, question, companyHints, limitedTopK));
                return chunks;
            }
            chunks.addAll(queryByIntentKeywords(session, question, limitedTopK));
        }
        return chunks;
    }

    private static List<String> resolveCompanyHints(String question, IntentDecision intent) {
        if (intent != null && intent.hasCompanyHints()) {
            return intent.companyHints();
        }
        return extractCompanyHints(question);
    }

    private static String resolvePersonHint(String question, IntentDecision intent) {
        if (intent != null && intent.hasPersonFocus()) {
            return intent.personName().trim();
        }
        return extractPersonHintForRoleQuery(question);
    }

    private static boolean shouldQueryPersonRole(String question, IntentDecision intent) {
        if (intent != null && intent.isPersonRoleListQuery()) {
            return intent.hasPersonFocus() || extractPersonHintForRoleQuery(question) != null;
        }
        if (intent != null && intent.hasPersonFocus()) {
            return true;
        }
        return extractPersonHintForRoleQuery(question) != null && isRoleRelationQuery(question);
    }

    private static String resolveRoleFocus(String question, IntentDecision intent) {
        if (intent != null && intent.roleFocus() != null && !intent.roleFocus().isBlank()
                && !"any".equalsIgnoreCase(intent.roleFocus())) {
            return intent.roleFocus().toLowerCase(Locale.ROOT);
        }
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("法定代表人") || q.contains("法人")) {
            return "legal_rep";
        }
        if (q.contains("董事")) {
            return "director";
        }
        if (q.contains("监事")) {
            return "supervisor";
        }
        return "any";
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

    private List<ContextChunk> queryByPersonAndRole(
            Session session,
            String question,
            String personHint,
            int topK,
            String roleFocus
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
            String companyId = safeString(record, "companyId");
            String companyName = safeString(record, "companyName");
            String personName = safeString(record, "personName");
            String role = safeString(record, "role");
            String field = detectField(question);
            String snippet = "状态=" + safeString(record, "status")
                    + "; 关键人=" + personName + "(" + role + ")";
            double score = 22.0;
            chunks.add(new ContextChunk(companyId, companyName, field, snippet, score, "neo4j-person-role"));
        }
        return chunks;
    }

    private static boolean isRoleRelationQuery(String question) {
        return question.contains("法人")
                || question.contains("法定代表人")
                || question.contains("董事")
                || question.contains("监事")
                || question.contains("担任")
                || question.contains("任职");
    }

    /**
     * 从「X是哪些公司/主体的法人」类问句中提取人名（规则兜底；优先使用 LLM 抽取的 personName）。
     */
    public static String extractPersonHintForRoleQuery(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String q = question.trim();
        String[] patterns = {
                "是哪些公司的",
                "是哪些公司",
                "是哪些主体的",
                "是哪些主体",
                "是哪些单位的",
                "是哪些单位",
                "是哪些企业的",
                "是哪些企业",
                "在哪些公司",
                "在哪些主体",
                "担任哪些公司",
                "担任哪些主体",
        };
        for (String marker : patterns) {
            int idx = q.indexOf(marker);
            if (idx > 1) {
                String name = extractPersonNameBeforeMarker(q, idx);
                if (name != null) {
                    return name;
                }
            }
        }
        // 「X是哪些主体的法人」：主体在「法人」前、不在上述 marker 中时的兜底
        int legalIdx = q.indexOf("法人");
        if (legalIdx > 1 && (q.contains("哪些主体") || q.contains("哪些公司") || q.contains("哪些企业"))) {
            int whichIdx = q.indexOf("哪些");
            if (whichIdx > 1) {
                String name = extractPersonNameBeforeMarker(q, whichIdx);
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    private String extractPersonNameBeforeMarker(String q, int markerStart) {
        String name = q.substring(0, markerStart).trim();
        name = name.replaceAll("^(请问|查询|帮我查|谁|什么人)", "").trim();
        // 去掉末尾「是」：戴科彬是哪些…
        if (name.endsWith("是") && name.length() > 1) {
            name = name.substring(0, name.length() - 1).trim();
        }
        if (name.length() >= 2 && name.length() <= 12) {
            return name;
        }
        return null;
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

    public static List<String> extractCompanyHints(String question) {
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
        if (q.contains("法人") || q.contains("法定代表人") || q.contains("董事") || q.contains("监事")) {
            fields.add("关键人员");
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

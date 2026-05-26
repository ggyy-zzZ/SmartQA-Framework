package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.domain.PersonNameParser;
import com.qa.demo.qa.domain.EnterpriseLexicon;
import com.qa.demo.qa.domain.CertificateSealEnumCatalog;
import com.qa.demo.qa.domain.GraphCompanyFacetCatalog;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import com.qa.demo.qa.retrieval.graph.GraphCompanySnippetBuilder;
import com.qa.demo.qa.retrieval.graph.GraphPersonRoleQuery;
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
    private final QuestionEntityExtractor entityExtractor;
    private final EnterpriseLexicon lexicon;
    private final GraphCompanyFacetCatalog companyFacetCatalog;
    private final CertificateSealEnumCatalog certificateSealEnumCatalog;

    public GraphContextService(
            Driver neo4jDriver,
            QuestionEntityExtractor entityExtractor,
            EnterpriseLexicon lexicon,
            GraphCompanyFacetCatalog companyFacetCatalog,
            CertificateSealEnumCatalog certificateSealEnumCatalog
    ) {
        this.neo4jDriver = neo4jDriver;
        this.entityExtractor = entityExtractor;
        this.lexicon = lexicon;
        this.companyFacetCatalog = companyFacetCatalog;
        this.certificateSealEnumCatalog = certificateSealEnumCatalog;
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK) {
        return retrieveTopChunks(question, RetrievalPlan.of(null, topK, topK));
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK, IntentDecision intent) {
        return retrieveTopChunks(question, RetrievalPlan.of(intent, topK, topK));
    }

    public List<ContextChunk> retrieveTopChunks(String question, RetrievalPlan plan) {
        IntentDecision intent = plan.intent();
        int limitedTopK = plan.personRoleList()
                ? Math.max(1, plan.graphRecallTopK())
                : Math.max(1, Math.min(plan.graphRecallTopK(), 10));
        List<String> companyHints = resolveCompanyHints(question, intent);
        List<ContextChunk> chunks = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            String personHint = resolvePersonHint(question, intent);
            if (personHint != null && shouldQueryPersonRole(question, intent)) {
                String roleFocus = resolveRoleFocus(question, intent);
                String field = fieldLabel(question);
                chunks.addAll(GraphPersonRoleQuery.execute(session, personHint, roleFocus, limitedTopK, field));
                if (!chunks.isEmpty()) {
                    return chunks;
                }
            }
            if (!companyHints.isEmpty()) {
                chunks.addAll(queryByCompanyHints(session, question, intent, companyHints, limitedTopK));
                return chunks;
            }
            chunks.addAll(queryByIntentKeywords(session, question, limitedTopK));
        }
        return chunks;
    }

    public boolean hasExplicitCompanyHint(String question) {
        return !entityExtractor.extractCompanyHints(question).isEmpty();
    }

    public String extractPersonHintForRoleQuery(String question) {
        return entityExtractor.extractPersonName(question);
    }

    public List<CompanyCandidate> suggestCompanyCandidates(String question, int limit) {
        int top = Math.max(1, Math.min(limit, 8));
        List<String> hints = entityExtractor.extractCompanyHints(question);
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

    private List<String> resolveCompanyHints(String question, IntentDecision intent) {
        if (intent != null && intent.hasCompanyHints()) {
            return intent.companyHints();
        }
        return entityExtractor.extractCompanyHints(question);
    }

    private String resolvePersonHint(String question, IntentDecision intent) {
        if (intent != null && intent.hasPersonFocus()) {
            return intent.personName().trim();
        }
        return entityExtractor.extractPersonName(question);
    }

    /**
     * 结合任职类型（如法人）在图谱中列出姓名候选，用于敬称/姓级指称消歧。
     */
    public List<String> listPersonNamesByHintAndRole(String personHint, String roleFocus, int limit) {
        if (personHint == null || personHint.isBlank()) {
            return List.of();
        }
        String hint = PersonNameParser.hasHonorificSuffix(personHint)
                ? PersonNameParser.stripHonorific(personHint)
                : personHint.trim();
        if (hint.isBlank()) {
            return List.of();
        }
        try (Session session = neo4jDriver.session()) {
            return GraphPersonRoleQuery.listDistinctPersonNames(session, hint, roleFocus, limit);
        }
    }

    private boolean shouldQueryPersonRole(String question, IntentDecision intent) {
        if (intent != null && intent.isPersonRoleListQuery()) {
            return intent.hasPersonFocus();
        }
        if (intent != null && intent.hasPersonFocus()
                && intent.roleFocus() != null
                && !"any".equalsIgnoreCase(intent.roleFocus())) {
            return true;
        }
        return entityExtractor.extractPersonName(question) != null && entityExtractor.isRoleRelationQuery(question);
    }

    private String resolveRoleFocus(String question, IntentDecision intent) {
        if (intent != null && intent.roleFocus() != null && !intent.roleFocus().isBlank()
                && !"any".equalsIgnoreCase(intent.roleFocus())) {
            return intent.roleFocus().toLowerCase(Locale.ROOT);
        }
        return entityExtractor.inferRoleFocus(question);
    }

    private String fieldLabel(String question) {
        List<String> labels = lexicon.graphFieldLabels(question);
        if (labels.isEmpty()) {
            return "概要";
        }
        return String.join("/", labels);
    }

    private List<ContextChunk> queryByCompanyHints(
            Session session,
            String question,
            IntentDecision intent,
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
                OPTIONAL MATCH (c)-[:HAS_CERTIFICATE]->(cert:Certificate)
                OPTIONAL MATCH (c)-[:HAS_SEAL]->(seal:Seal)
                WITH c,
                     collect(DISTINCT roleRel.role + ":" + p.name)[0..6] AS roles,
                     collect(DISTINCT s.name + "(" + coalesce(shareRel.ratio, "?") + ")")[0..3] AS shareholders,
                     collect(DISTINCT pl.line + "(" + coalesce(prodRel.relation, "") + ")")[0..3] AS productLines,
                     collect(DISTINCT coalesce(cert.certType, '') + ':' + coalesce(cert.status, ''))[0..10] AS certificates,
                     collect(DISTINCT coalesce(seal.sealType, '') + '/' + coalesce(seal.sealCategory, '') + '(' + coalesce(seal.status, '') + ')')[0..8] AS seals
                RETURN c.companyId AS companyId,
                       c.name AS companyName,
                       c.status AS status,
                       c.entityType AS entityType,
                       c.entityCategory AS entityCategory,
                       c.registeredAddress AS registeredAddress,
                       c.businessScope AS businessScope,
                       roles AS roles,
                       shareholders AS shareholders,
                       productLines AS productLines,
                       certificates AS certificates,
                       seals AS seals
                LIMIT $topK
                """,
                org.neo4j.driver.Values.parameters("hints", companyHints, "topK", topK)
        );
        List<ContextChunk> chunks = new ArrayList<>();
        String field = fieldLabel(question);
        while (result.hasNext()) {
            Record record = result.next();
            String companyName = safeString(record, "companyName");
            String snippet = GraphCompanySnippetBuilder.buildSnippet(
                    record, intent, companyFacetCatalog, certificateSealEnumCatalog);
            if (snippet.isBlank()) {
                snippet = "状态=" + safeString(record, "status");
            }
            double score = 18.0 + Math.min(4.0, companyName.length() / 10.0);
            chunks.add(new ContextChunk(
                    safeString(record, "companyId"),
                    companyName,
                    field,
                    snippet,
                    score,
                    "neo4j"
            ));
        }
        return chunks;
    }

    private List<ContextChunk> queryByIntentKeywords(Session session, String question, int topK) {
        List<String> labels = lexicon.graphFieldLabels(question);
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
        String field = String.join("/", labels);
        while (result.hasNext()) {
            Record record = result.next();
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

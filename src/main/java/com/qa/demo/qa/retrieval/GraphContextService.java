package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import com.qa.demo.qa.domain.PersonNameParser;
import com.qa.demo.qa.domain.EnterpriseLexicon;
import com.qa.demo.qa.domain.CertificateSealEnumCatalog;
import com.qa.demo.qa.domain.GraphCompanyFacetCatalog;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import com.qa.demo.qa.retrieval.graph.GraphCompanySnippetBuilder;
import com.qa.demo.qa.retrieval.graph.GraphPersonRoleQuery;
import com.qa.demo.qa.retrieval.structured.RegionResolverService;
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

    private static final String COLLECT_GRAPH_ROLES = """
            collect(DISTINCT coalesce(roleRel.role, '') + ':' +
              coalesce(roleRel.personDisplay, p.displayName,
                CASE WHEN coalesce(p.personId, '') <> ''
                  THEN p.personId + '(' + coalesce(p.name, '') + ')'
                  ELSE coalesce(p.name, '')
                END))
            """;

    private static final String COLLECT_GRAPH_CERTIFICATES = """
            collect(DISTINCT coalesce(ct.certTypeDisplay, ct.certType, '') + ':' +
              coalesce(ct.statusDisplay, ct.status, ''))
            """;

    private static final String COLLECT_GRAPH_CERTIFICATES_ALIAS_CERT = """
            collect(DISTINCT coalesce(cert.certTypeDisplay, cert.certType, '') + ':' +
              coalesce(cert.statusDisplay, cert.status, ''))
            """;

    private static final String COLLECT_GRAPH_SEALS = """
            collect(DISTINCT coalesce(seal.sealTypeDisplay, seal.sealType, '') + '/' +
              coalesce(seal.sealCategoryDisplay, seal.sealCategory, '') + '(' +
              coalesce(seal.statusDisplay, seal.status, '') + ')')
            """;

    private static final String COLLECT_GRAPH_SHAREHOLDERS = """
            collect(DISTINCT coalesce(s.holderDisplay, s.name, '') + '(' + coalesce(shareRel.ratio, '?') + ')')
            """;

    private static final String COLLECT_GRAPH_PRODUCT_LINES = """
            collect(DISTINCT coalesce(pl.lineDisplay, pl.line, '') + '(' + coalesce(prodRel.relation, '') + ')')
            """;

    private static final String COLLECT_GRAPH_ATTACHMENTS = """
            collect(DISTINCT coalesce(att.fileName, '') + '(' + coalesce(att.fileType, '') + ')')
            """;

    private static final String COLLECT_GRAPH_CERT_PERSONS = """
            collect(DISTINCT
              CASE WHEN coalesce(cp.roleType, '') <> ''
                THEN cp.roleType + ':' + coalesce(cp.rolePersonDisplay, cp.rolePersonName, cp.personId, '')
                ELSE coalesce(cp.rolePersonName, cp.personId, '')
              END)
            """;

    private static final String COLLECT_GRAPH_CHANGE_EVENTS = """
            collect(DISTINCT
              CASE WHEN coalesce(ce.changeType, '') <> ''
                THEN ce.changeType + '@' + coalesce(ce.changeDate, ce.eventDate, '')
                ELSE coalesce(ce.eventId, '')
              END)
            """;

    private final Driver neo4jDriver;
    private final QaAssistantProperties properties;
    private final QuestionEntityExtractor entityExtractor;
    private final EnterpriseLexicon lexicon;
    private final GraphCompanyFacetCatalog companyFacetCatalog;
    private final CertificateSealEnumCatalog certificateSealEnumCatalog;
    private final ConversationScopeSupport scopeSupport;
    private final RegionResolverService regionResolver;

    public GraphContextService(
            Driver neo4jDriver,
            QaAssistantProperties properties,
            QuestionEntityExtractor entityExtractor,
            EnterpriseLexicon lexicon,
            GraphCompanyFacetCatalog companyFacetCatalog,
            CertificateSealEnumCatalog certificateSealEnumCatalog,
            ConversationScopeSupport scopeSupport,
            RegionResolverService regionResolver
    ) {
        this.neo4jDriver = neo4jDriver;
        this.properties = properties;
        this.entityExtractor = entityExtractor;
        this.lexicon = lexicon;
        this.companyFacetCatalog = companyFacetCatalog;
        this.certificateSealEnumCatalog = certificateSealEnumCatalog;
        this.scopeSupport = scopeSupport;
        this.regionResolver = regionResolver;
    }

    /**
     * 从问句里抽取行政区划代码列表（GB/T 2260 6 位）。
     * <p>
     * 调用 {@link RegionResolverService#extractRegionCodes(String)}；问句无地区时返回空 list。
     * 用于 Cypher {@code WHERE c.registeredAreaCode IN $regionCodes} 过滤。
     */
    private java.util.List<String> regionCodesFor(String question) {
        if (question == null || question.isBlank() || regionResolver == null) {
            return java.util.List.of();
        }
        var r = regionResolver.extractRegionCodes(question);
        return r == null ? java.util.List.of() : r.codes();
    }

    /**
     * 办公地行政区划代码列表（D3："在北京" = 注册地 OR 办公地）。
     * <p>
     * 当前实现与 {@link #regionCodesFor} 复用同源（D3 简化：未在问句中区分"办公地"关键词）。
     * 留出独立方法为后续 LLM 校准或办公地专项识别预留扩展点。
     */
    private java.util.List<String> officeRegionCodesFor(String question) {
        return regionCodesFor(question);
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
        if (intent != null && intent.isPersonCertificateListQuery() && intent.hasPersonFocus()) {
            return List.of();
        }
        List<String> companyHints = resolveCompanyHints(question, intent);
        List<ContextChunk> chunks = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            String personHint = resolvePersonHint(question, intent);
            if (personHint != null && shouldQueryPersonRole(question, intent)) {
                String roleFocus = resolveRoleFocus(question, intent);
                String field = fieldLabel(question);
                if (plan.personRoleList() && properties.isPersonRoleSlimGraph()) {
                    chunks.addAll(GraphPersonRoleQuery.executeBoundary(session, personHint, roleFocus, limitedTopK, field));
                } else {
                    chunks.addAll(GraphPersonRoleQuery.execute(session, personHint, roleFocus, limitedTopK, field));
                }
                if (!chunks.isEmpty()) {
                    return chunks;
                }
                if (intent != null && intent.isPersonRoleListQuery()) {
                    return chunks;
                }
            }
            if (intent != null && "company_certificate".equalsIgnoreCase(intent.queryType())) {
                if (!companyHints.isEmpty()) {
                    chunks.addAll(retrieveCertificateInstances(session, companyHints, intent, limitedTopK, false, regionCodesFor(question)));
                }
                return chunks;
            }
            if (!companyHints.isEmpty()) {
                chunks.addAll(queryByCompanyHints(session, question, intent, companyHints, limitedTopK));
                return chunks;
            }
            chunks.addAll(queryByIntentKeywords(session, question, intent, limitedTopK));
        }
        return chunks;
    }

    /**
     * 按公司 hints 返回证照实例级证据（每本证一条 chunk），供 company_certificate 使用。
     */
    public List<ContextChunk> retrieveCertificateInstances(
            List<String> companyHints,
            IntentDecision intent,
            int topK,
            boolean activeCertificatesOnly
    ) {
        return retrieveCertificateInstances(companyHints, intent, topK, activeCertificatesOnly, java.util.List.of());
    }

    public List<ContextChunk> retrieveCertificateInstances(
            List<String> companyHints,
            IntentDecision intent,
            int topK,
            boolean activeCertificatesOnly,
            java.util.List<String> regionCodes
    ) {
        if (companyHints == null || companyHints.isEmpty()) {
            return List.of();
        }
        try (Session session = neo4jDriver.session()) {
            return retrieveCertificateInstances(session, companyHints, intent, topK, activeCertificatesOnly, regionCodes);
        }
    }

    private List<ContextChunk> retrieveCertificateInstances(
            Session session,
            List<String> companyHints,
            IntentDecision intent,
            int topK,
            boolean activeCertificatesOnly,
            java.util.List<String> regionCodes
    ) {
        List<String> activeStatuses = List.of("有效", "生效中");
        java.util.List<String> officeRegionCodes = regionCodes == null ? java.util.List.of() : regionCodes;
        Result result = session.run(
                """
                UNWIND $hints AS hint
                MATCH (c:Company)-[:HAS_CERTIFICATE]->(ct:Certificate)
                WHERE (toLower(c.name) CONTAINS toLower(hint)
                   OR toLower(coalesce(c.shortName, '')) CONTAINS toLower(hint)
                   OR c.companyId = hint)
                  AND ($activeOnly = false OR coalesce(ct.status, '') IN $activeStatuses)
                  AND (size($regionCodes) = 0
                       OR c.registeredAreaCode IN $regionCodes
                       OR c.officeAreaCode IN $officeRegionCodes)
                RETURN c.companyId AS companyId,
                       c.name AS companyName,
                       coalesce(ct.certKey, '') AS certKey,
                       coalesce(ct.certificateId, '') AS certificateId,
                       coalesce(ct.certTypeDisplay, ct.certType, '') AS certTypeDisplay,
                       coalesce(ct.statusDisplay, ct.status, '') AS statusDisplay,
                       coalesce(ct.expireDate, ct.validTo, '') AS validTo,
                       coalesce(ct.validFrom, '') AS validFrom,
                       coalesce(ct.issueDate, '') AS issueDate,
                       coalesce(ct.issueOrg, '') AS issueOrg,
                       coalesce(ct.keeper, '') AS keeper,
                       coalesce(ct.supervisor, '') AS supervisor,
                       coalesce(ct.executor, '') AS executor
                ORDER BY c.name, ct.certTypeDisplay
                LIMIT $topK
                """,
                org.neo4j.driver.Values.parameters(
                        "hints", companyHints,
                        "topK", topK,
                        "activeOnly", activeCertificatesOnly,
                        "activeStatuses", activeStatuses,
                        "regionCodes", regionCodes == null ? java.util.List.of() : regionCodes,
                        "officeRegionCodes", officeRegionCodes
                )
        );
        List<ContextChunk> chunks = new ArrayList<>();
        while (result.hasNext()) {
            Record record = result.next();
            String certTypeDisplay = safeString(record, "certTypeDisplay");
            if (certTypeDisplay.isBlank()) {
                certTypeDisplay = certificateSealEnumCatalog.resolveCertificateLabel(safeString(record, "certType"));
            }
            String companyName = safeString(record, "companyName");
            String statusDisplay = safeString(record, "statusDisplay");
            String certificateId = safeString(record, "certificateId");
            String validFrom = safeString(record, "validFrom");
            String validTo = safeString(record, "validTo");
            String issueDate = safeString(record, "issueDate");
            String issueOrg = safeString(record, "issueOrg");
            String keeper = safeString(record, "keeper");
            String supervisor = safeString(record, "supervisor");
            String executor = safeString(record, "executor");
            String evidence = String.format("登记证照 | %s | %s | 状态: %s", certTypeDisplay, companyName, statusDisplay);
            if (!certificateId.isBlank()) {
                evidence += " | 证照ID: " + certificateId;
            }
            if (!validFrom.isBlank() || !validTo.isBlank()) {
                evidence += " | 有效期: " + (validFrom.isBlank() ? "?" : validFrom)
                        + " ~ " + (validTo.isBlank() ? "至今" : validTo);
            }
            if (!issueDate.isBlank()) {
                evidence += " | 签发日期: " + issueDate;
            }
            if (!issueOrg.isBlank()) {
                evidence += " | 发证机关: " + issueOrg;
            }
            if (!keeper.isBlank()) {
                evidence += " | 保管人: " + keeper;
            }
            if (!supervisor.isBlank()) {
                evidence += " | 监管人: " + supervisor;
            }
            if (!executor.isBlank()) {
                evidence += " | 执行人: " + executor;
            }
            String anchor = safeString(record, "certKey");
            if (anchor.isBlank()) {
                anchor = safeString(record, "companyId") + "|" + certTypeDisplay;
            }
            chunks.add(ContextChunk.ofCompany(
                    anchor,
                    companyName,
                    "资质与许可",
                    evidence,
                    24.0,
                    "neo4j-certificate-instance",
                    "person_certificate_v1"
            ));
        }
        return chunks;
    }

    private List<ContextChunk> queryByCertificateIntent(
            Session session,
            String question,
            IntentDecision intent,
            int topK
    ) {
        List<String> certLabels = certificateSealEnumCatalog.certificateLabelsMentionedIn(question);
        String certLabel = certLabels.isEmpty() ? "" : certLabels.getFirst();
        String companyStatusMode = resolveCompanyStatusMode(question);
        boolean certValidOnly = question != null && question.contains("有效")
                && (question.contains("证照") || question.contains("证"));
        java.util.List<String> regionCodes = regionCodesFor(question);
        java.util.List<String> officeRegionCodes = officeRegionCodesFor(question);
        Result result = session.run(
                """
                MATCH (c:Company)-[:HAS_CERTIFICATE]->(ct:Certificate)
                WHERE ($certLabel = '' OR toLower(ct.certType) CONTAINS toLower($certLabel))
                  AND ($certValidOnly = false OR ct.status = '有效')
                  AND (
                    $companyStatusMode = 'all'
                    OR ($companyStatusMode = 'active' AND coalesce(c.status, '') IN ['存续', '在业'])
                    OR ($companyStatusMode = 'inactive' AND NOT coalesce(c.status, '') IN ['存续', '在业'])
                  )
                  AND (size($regionCodes) = 0
                       OR c.registeredAreaCode IN $regionCodes
                       OR c.officeAreaCode IN $officeRegionCodes)
                OPTIONAL MATCH (c)<-[roleRel:HAS_ROLE_IN]-(p:Person)
                OPTIONAL MATCH (s:Shareholder)-[shareRel:HOLDS_SHARES_IN]->(c)
                OPTIONAL MATCH (c)-[prodRel:BELONGS_TO_PRODUCT]->(pl:ProductLine)
                WITH c, ct,
                     """
                + COLLECT_GRAPH_ROLES.strip() + "[0..4] AS roles,\n"
                + COLLECT_GRAPH_SHAREHOLDERS.strip() + "[0..2] AS shareholders,\n"
                + COLLECT_GRAPH_PRODUCT_LINES.strip() + "[0..2] AS productLines\n"
                + """
                WITH c,
                     """
                + COLLECT_GRAPH_CERTIFICATES.strip() + "[0..12] AS certificates,\n"
                + """
                     roles, shareholders, productLines
                WHERE size(certificates) > 0
                RETURN c.companyId AS companyId,
                       c.name AS companyName,
                       c.status AS status,
                       c.entityType AS entityType,
                       c.entityCategory AS entityCategory,
                       c.registeredAddress AS registeredAddress,
                       c.businessScope AS businessScope,
                       c.alias AS alias,
                       c.establishedDate AS establishedDate,
                       certificates AS certificates,
                       roles AS roles,
                       shareholders AS shareholders,
                       productLines AS productLines,
                       [] AS seals,
                       [] AS attachments,
                       [] AS certificatePersons,
                       [] AS changeEvents
                ORDER BY size(certificates) DESC
                LIMIT $topK
                """,
                org.neo4j.driver.Values.parameters(
                        "certLabel", certLabel,
                        "topK", topK,
                        "companyStatusMode", companyStatusMode,
                        "certValidOnly", certValidOnly,
                        "regionCodes", regionCodes,
                        "officeRegionCodes", officeRegionCodes
                )
        );
        List<ContextChunk> chunks = new ArrayList<>();
        String field = "资质与许可";
        while (result.hasNext()) {
            Record record = result.next();
            String snippet = GraphCompanySnippetBuilder.buildSnippet(
                    record, intent, companyFacetCatalog, certificateSealEnumCatalog);
            if (snippet.isBlank()) {
                snippet = "证照=" + safeList(record, "certificates");
            }
            chunks.add(ContextChunk.ofCompany(
                    safeString(record, "companyId"),
                    safeString(record, "companyName"),
                    field,
                    snippet,
                    20.0 + Math.min(6.0, safeList(record, "certificates").length() / 40.0),
                    "neo4j-certificate"
            ));
        }
        return chunks;
    }

    private String resolveCompanyStatusMode(String question) {
        return switch (scopeSupport.inferOperatingStatusScope(question)) {
            case ACTIVE -> "active";
            case INACTIVE -> "inactive";
            case ALL -> "all";
        };
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
        java.util.List<String> regionCodes = regionCodesFor(question);
        java.util.List<String> officeRegionCodes = officeRegionCodesFor(question);
        Result result = session.run(
                """
                UNWIND $hints AS hint
                MATCH (c:Company)
                WHERE (toLower(c.name) CONTAINS toLower(hint)
                   OR toLower(coalesce(c.shortName, '')) CONTAINS toLower(hint)
                   OR c.companyId = hint)
                  AND (size($regionCodes) = 0
                       OR c.registeredAreaCode IN $regionCodes
                       OR c.officeAreaCode IN $officeRegionCodes)
                OPTIONAL MATCH (c)<-[roleRel:HAS_ROLE_IN]-(p:Person)
                OPTIONAL MATCH (s:Shareholder)-[shareRel:HOLDS_SHARES_IN]->(c)
                OPTIONAL MATCH (c)-[prodRel:BELONGS_TO_PRODUCT]->(pl:ProductLine)
                OPTIONAL MATCH (c)-[:HAS_CERTIFICATE]->(cert:Certificate)
                OPTIONAL MATCH (c)-[:HAS_SEAL]->(seal:Seal)
                OPTIONAL MATCH (c)-[:HAS_ATTACHMENT]->(att:CertificateAttachment)
                OPTIONAL MATCH (c)-[:HAS_CERTIFICATE_PERSON]->(cp:CertificatePersonDetail)
                OPTIONAL MATCH (c)-[:HAS_CHANGE_EVENT]->(ce:CompanyChangeEvent)
                WITH c,
                     """
                + COLLECT_GRAPH_ROLES.strip() + "[0..6] AS roles,\n"
                + COLLECT_GRAPH_SHAREHOLDERS.strip() + "[0..3] AS shareholders,\n"
                + COLLECT_GRAPH_PRODUCT_LINES.strip() + "[0..3] AS productLines,\n"
                + COLLECT_GRAPH_CERTIFICATES_ALIAS_CERT.strip() + "[0..10] AS certificates,\n"
                + COLLECT_GRAPH_SEALS.strip() + "[0..8] AS seals,\n"
                + COLLECT_GRAPH_ATTACHMENTS.strip() + "[0..6] AS attachments,\n"
                + COLLECT_GRAPH_CERT_PERSONS.strip() + "[0..6] AS certificatePersons,\n"
                + COLLECT_GRAPH_CHANGE_EVENTS.strip() + "[0..3] AS changeEvents\n"
                + """
                RETURN c.companyId AS companyId,
                       c.name AS companyName,
                       c.status AS status,
                       c.statusCode AS statusCode,
                       c.entityType AS entityType,
                       c.entityTypeCode AS entityTypeCode,
                       c.entityCategory AS entityCategory,
                       c.entityCategoryCode AS entityCategoryCode,
                       c.registeredAddress AS registeredAddress,
                       c.officeAddress AS officeAddress,
                       c.businessScope AS businessScope,
                       c.registeredCapital AS registeredCapital,
                       c.capitalCurrency AS capitalCurrency,
                       c.alias AS alias,
                       c.contactPhone AS contactPhone,
                       c.contactEmail AS contactEmail,
                       c.establishedDate AS establishedDate,
                       c.modifytime AS modifytime,
                       c.sourceFile AS sourceFile,
                       c.ingestedAt AS ingestedAt,
                       roles AS roles,
                       shareholders AS shareholders,
                       productLines AS productLines,
                       certificates AS certificates,
                       seals AS seals,
                       attachments AS attachments,
                       certificatePersons AS certificatePersons,
                       changeEvents AS changeEvents
                LIMIT $topK
                """,
                org.neo4j.driver.Values.parameters(
                        "hints", companyHints,
                        "topK", topK,
                        "regionCodes", regionCodes,
                        "officeRegionCodes", officeRegionCodes
                )
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
            chunks.add(ContextChunk.ofCompany(
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

    private List<ContextChunk> queryByIntentKeywords(
            Session session,
            String question,
            IntentDecision intent,
            int topK
    ) {
        List<String> labels = lexicon.graphFieldLabels(question);
        if (labels.isEmpty()) {
            return List.of();
        }
        boolean certificateFocus = labels.stream().anyMatch(l -> l.contains("资质") || l.contains("许可"));
        java.util.List<String> regionCodes = regionCodesFor(question);
        java.util.List<String> officeRegionCodes = officeRegionCodesFor(question);
        Result result;
        if (certificateFocus) {
            result = session.run(
                    """
                    MATCH (c:Company)-[:HAS_CERTIFICATE]->(ct:Certificate)
                    WHERE size($regionCodes) = 0
                       OR c.registeredAreaCode IN $regionCodes
                       OR c.officeAreaCode IN $officeRegionCodes
                    WITH c, """
                    + COLLECT_GRAPH_CERTIFICATES.strip() + "[0..10] AS certificates\n"
                    + """
                    WHERE size(certificates) > 0
                    RETURN c.companyId AS companyId,
                           c.name AS companyName,
                           c.status AS status,
                           c.entityType AS entityType,
                           c.entityCategory AS entityCategory,
                           c.registeredAddress AS registeredAddress,
                           c.businessScope AS businessScope,
                           c.alias AS alias,
                           c.establishedDate AS establishedDate,
                           certificates AS certificates,
                           [] AS roles,
                           [] AS shareholders,
                           [] AS productLines,
                           [] AS seals,
                           [] AS attachments,
                           [] AS certificatePersons,
                           [] AS changeEvents
                    ORDER BY size(certificates) DESC
                    LIMIT $topK
                    """,
                    org.neo4j.driver.Values.parameters(
                            "topK", topK,
                            "regionCodes", regionCodes,
                            "officeRegionCodes", officeRegionCodes
                    )
            );
        } else {
            result = session.run(
                    """
                    MATCH (c:Company)
                    WHERE c.status IS NOT NULL
                      AND (size($regionCodes) = 0
                           OR c.registeredAreaCode IN $regionCodes
                           OR c.officeAreaCode IN $officeRegionCodes)
                    OPTIONAL MATCH (c)-[prodRel:BELONGS_TO_PRODUCT]->(pl:ProductLine)
                    WITH c, collect(DISTINCT pl.line)[0..2] AS productLines
                    RETURN c.companyId AS companyId,
                           c.name AS companyName,
                           c.status AS status,
                           c.entityType AS entityType,
                           c.entityCategory AS entityCategory,
                           c.registeredAddress AS registeredAddress,
                           c.businessScope AS businessScope,
                           c.alias AS alias,
                           c.establishedDate AS establishedDate,
                           [] AS certificates,
                           [] AS roles,
                           [] AS shareholders,
                           productLines AS productLines,
                           [] AS seals,
                           [] AS attachments,
                           [] AS certificatePersons,
                           [] AS changeEvents
                    LIMIT $topK
                    """,
                    org.neo4j.driver.Values.parameters(
                            "topK", topK,
                            "regionCodes", regionCodes,
                            "officeRegionCodes", officeRegionCodes
                    )
            );
        }
        List<ContextChunk> chunks = new ArrayList<>();
        String field = String.join("/", labels);
        while (result.hasNext()) {
            Record record = result.next();
            String snippet = certificateFocus
                    ? GraphCompanySnippetBuilder.buildSnippet(
                            record, intent, companyFacetCatalog, certificateSealEnumCatalog)
                    : "状态=" + safeString(record, "status")
                            + "; 类型=" + safeString(record, "entityType")
                            + "; 分类=" + safeString(record, "entityCategory")
                            + "; 产品线=" + safeList(record, "productLines");
            if (snippet.isBlank()) {
                snippet = "状态=" + safeString(record, "status");
            }
            chunks.add(ContextChunk.ofCompany(
                    safeString(record, "companyId"),
                    safeString(record, "companyName"),
                    field,
                    snippet,
                    certificateFocus ? 14.0 : 8.0,
                    certificateFocus ? "neo4j-intent-certificate" : "neo4j-intent"
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

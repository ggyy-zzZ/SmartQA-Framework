package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.domain.CertificateSealEnumCatalog;
import com.qa.demo.qa.domain.GraphCompanyFacetCatalog;
import com.qa.demo.qa.retrieval.graph.GraphCompanySnippetBuilder;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 companyId 列表一次性拉取「全 facet 字段」上下文，
 * 供 {@code QaRetrievalPipeline.appendCompanyFacetEvidenceIfNeeded} 在 MySQL 不可用时
 * 兜底完成 facet 补齐。
 *
 * <p>输出每家公司一个 {@link ContextChunk}，source 标记为
 * {@code neo4j-company-fullprofile}，便于回溯：调用方应优先用本类补图谱，
 * 然后再考虑 SQL fallback / LLM 兜底。</p>
 */
@Service
public class GraphCompanyFullProfileQuery {

    private static final Logger log = LoggerFactory.getLogger(GraphCompanyFullProfileQuery.class);

    private static final String SOURCE_TAG = "neo4j-company-fullprofile";

    private final Driver neo4jDriver;
    private final QaAssistantProperties properties;
    private final GraphCompanyFacetCatalog facetCatalog;
    private final CertificateSealEnumCatalog enumCatalog;

    public GraphCompanyFullProfileQuery(
            Driver neo4jDriver,
            QaAssistantProperties properties,
            GraphCompanyFacetCatalog facetCatalog,
            CertificateSealEnumCatalog enumCatalog
    ) {
        this.neo4jDriver = neo4jDriver;
        this.properties = properties;
        this.facetCatalog = facetCatalog;
        this.enumCatalog = enumCatalog;
    }

    /**
     * 按 companyIds 拉取，每个公司一个 chunk。facetKeys 为空时退回到 default facets。
     */
    public List<ContextChunk> executeByCompanyIds(List<String> companyIds, List<String> facetKeys) {
        if (companyIds == null || companyIds.isEmpty()) {
            return List.of();
        }
        if (!properties.isGraphFullProfileEnabled()) {
            log.debug("[GraphCompanyFullProfileQuery] disabled by configuration; skip");
            return List.of();
        }
        List<String> facets = (facetKeys == null || facetKeys.isEmpty())
                ? facetCatalog.facetsForQueryType("default")
                : facetKeys;
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                    """
                    UNWIND $ids AS id
                    MATCH (c:Company)
                    WHERE c.companyId = id OR toLower(c.name) = toLower(id)
                    OPTIONAL MATCH (c)<-[roleRel:HAS_ROLE_IN]-(p:Person)
                    OPTIONAL MATCH (s:Shareholder)-[shareRel:HOLDS_SHARES_IN]->(c)
                    OPTIONAL MATCH (c)-[prodRel:BELONGS_TO_PRODUCT]->(pl:ProductLine)
                    OPTIONAL MATCH (c)-[:HAS_CERTIFICATE]->(cert:Certificate)
                    OPTIONAL MATCH (c)-[:HAS_SEAL]->(seal:Seal)
                    OPTIONAL MATCH (c)-[:HAS_ATTACHMENT]->(att:CertificateAttachment)
                    OPTIONAL MATCH (c)-[:HAS_CERTIFICATE_PERSON]->(cp:CertificatePersonDetail)
                    OPTIONAL MATCH (c)-[:HAS_CHANGE_EVENT]->(ce:CompanyChangeEvent)
                    WITH c,
                         collect(DISTINCT coalesce(roleRel.role,'') + ':' +
                           coalesce(roleRel.personDisplay, p.displayName, p.name, ''))[0..6] AS roles,
                         collect(DISTINCT coalesce(s.holderDisplay, s.name,'') + '(' +
                           coalesce(shareRel.ratio, '?') + ')')[0..3] AS shareholders,
                         collect(DISTINCT coalesce(pl.lineDisplay, pl.line,'') + '(' +
                           coalesce(prodRel.relation, '') + ')')[0..3] AS productLines,
                         collect(DISTINCT coalesce(cert.certTypeDisplay, cert.certType,'') + ':' +
                           coalesce(cert.statusDisplay, cert.status, ''))[0..10] AS certificates,
                         collect(DISTINCT coalesce(seal.sealTypeDisplay, seal.sealType,'') + '/' +
                           coalesce(seal.sealCategoryDisplay, seal.sealCategory,'') + '(' +
                           coalesce(seal.statusDisplay, seal.status, '') + ')')[0..8] AS seals,
                         collect(DISTINCT coalesce(att.fileName,'') + '(' + coalesce(att.fileType,'') + ')')[0..6] AS attachments,
                         collect(DISTINCT
                           CASE WHEN coalesce(cp.roleType,'') <> ''
                             THEN cp.roleType + ':' + coalesce(cp.rolePersonName, cp.personId, '')
                             ELSE coalesce(cp.rolePersonName, cp.personId, '')
                           END)[0..6] AS certificatePersons,
                         collect(DISTINCT
                           CASE WHEN coalesce(ce.changeType,'') <> ''
                             THEN ce.changeType + '@' + coalesce(ce.changeDate, ce.eventDate, '')
                             ELSE coalesce(ce.eventId, '')
                           END)[0..3] AS changeEvents
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
                           roles, shareholders, productLines, certificates, seals,
                           attachments, certificatePersons, changeEvents
                    """,
                    org.neo4j.driver.Values.parameters("ids", companyIds)
            );

            List<ContextChunk> chunks = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                String snippet = GraphCompanySnippetBuilder.buildSnippet(
                        record, facets, facetCatalog, enumCatalog);
                if (snippet.isBlank()) {
                    snippet = "公司=" + safeString(record, "companyName");
                }
                chunks.add(ContextChunk.ofCompany(
                        safeString(record, "companyId"),
                        safeString(record, "companyName"),
                        "公司全 profile",
                        snippet,
                        12.0,
                        SOURCE_TAG
                ));
            }
            return chunks;
        } catch (Exception e) {
            log.warn("[GraphCompanyFullProfileQuery] failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String safeString(Record record, String key) {
        if (record.get(key).isNull()) {
            return "";
        }
        return record.get(key).asString("");
    }
}

package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.cdc.graph.CdcGraphRelationshipSync;
import com.qa.demo.qa.config.GraphNodeDefinition;
import com.qa.demo.qa.config.GraphNodeDefinitionsProperties;
import com.qa.demo.qa.config.GraphNodeFieldSpec;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CDC 事件写入 Neo4j 图谱。
 *
 * <p>节点属性来源：
 * <ul>
 *   <li>company / employee 走 {@link com.qa.demo.qa.config.GraphNodeDefinitionsProperties} 白名单通用遍历，
 *       由 {@link CdcFieldTruncator} 截断长文本，按白名单声明的 enumDictKey 解析中文标签。</li>
 *   <li>branch / partner 仍使用字段硬编码（不进入富图 P0 范围）。</li>
 * </ul>
 * 人-企等关系由 {@link CdcGraphRelationshipSync} 按 qa/cdc-graph-sync.json 驱动。
 */
@Component
public class Neo4jCdcWriter {

    private static final Logger log = LoggerFactory.getLogger(Neo4jCdcWriter.class);

    private final Driver driver;
    private final CdcSyncAuditLogger auditLogger;
    private final CdcGraphRelationshipSync relationshipSync;
    private final CdcFieldEnricher fieldEnricher;
    private final CdcPersonDisplayResolver personDisplayResolver;
    private final CdcFieldTruncator truncator;
    private final GraphNodeDefinitionsProperties definitions;

    public Neo4jCdcWriter(
            Driver driver,
            CdcSyncAuditLogger auditLogger,
            CdcGraphRelationshipSync relationshipSync,
            CdcFieldEnricher fieldEnricher,
            CdcPersonDisplayResolver personDisplayResolver,
            CdcFieldTruncator truncator,
            GraphNodeDefinitionsProperties definitions
    ) {
        this.driver = driver;
        this.auditLogger = auditLogger;
        this.relationshipSync = relationshipSync;
        this.fieldEnricher = fieldEnricher;
        this.personDisplayResolver = personDisplayResolver;
        this.truncator = truncator;
        this.definitions = definitions;
    }

    public void write(String table, String op, JsonNode after, JsonNode before) {
        JsonNode row = CdcEntityIdResolver.dataRow(after, before, op);
        String entityId = CdcEntityIdResolver.resolveEntityId(table, row);

        if (after == null && "d".equals(op)) {
            deleteEntity(table, before, entityId);
            return;
        }

        if (after == null) {
            log.warn("[Neo4j CDC] No after data for op={}, table={}", op, table);
            return;
        }

        try (Session session = driver.session()) {
            switch (table) {
                case "company" -> upsertCompany(session, after);
                case "employee" -> upsertPerson(session, after);
                case "branch" -> upsertBranch(session, after);
                case "partner" -> upsertPartner(session, after);
                default -> log.debug("[Neo4j CDC] Unsupported table: {}", table);
            }
            relationshipSync.syncTable(session, table, after);
            auditLogger.storeWriteSuccess("neo4j", table, op, entityId, "MERGE ok");
        } catch (Exception e) {
            auditLogger.storeWriteFailed("neo4j", table, op, entityId, e.getMessage());
            log.error("[Neo4j CDC] Failed to write table={}, op={}", table, op, e);
            throw new RuntimeException("Neo4j CDC write failed", e);
        }
    }

    private void deleteEntity(String table, JsonNode before, String entityId) {
        if (before == null) {
            log.warn("[Neo4j CDC] No before data for delete, ignoring");
            return;
        }
        if (entityId == null) {
            entityId = CdcEntityIdResolver.resolveEntityId(table, before);
        }
        if (entityId == null) {
            log.warn("[Neo4j CDC] Cannot extract entity id for delete, table={}", table);
            return;
        }

        try (Session session = driver.session()) {
            String label = toLabel(table);
            String idProperty = idProperty(table);
            session.run("MATCH (n:" + label + " {" + idProperty + ": $id}) DETACH DELETE n",
                    Map.of("id", entityId));
            auditLogger.storeWriteSuccess("neo4j", table, "d", entityId, "DETACH DELETE ok");
            log.info("[Neo4j CDC] Deleted {} {}={}", label, idProperty, entityId);
        } catch (Exception e) {
            auditLogger.storeWriteFailed("neo4j", table, "d", entityId, e.getMessage());
            log.error("[Neo4j CDC] Failed to delete table={}", table, e);
            throw new RuntimeException("Neo4j CDC delete failed", e);
        }
    }

    private Value v(String s) {
        return s == null ? Values.NULL : Values.value(s);
    }

    private void upsertCompany(Session session, JsonNode row) {
        String companyId = CdcEntityIdResolver.resolveEntityId("company", row);
        if (companyId == null || companyId.isBlank()) {
            log.warn("[Neo4j CDC] company_id is null, skipping");
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("companyId", v(companyId));

        GraphNodeDefinition def = definitions.companyDef();
        if (def != null) {
            for (GraphNodeFieldSpec spec : def.properties()) {
                String raw = CdcTdcompFields.firstText(row, spec.columns().toArray(new String[0]));
                if (raw == null) {
                    continue;
                }
                String label = fieldEnricher.enumLabel(spec.enumDictKey(), raw);
                CdcFieldTruncator.Truncation codeTrunc = truncator.truncateBySpec(raw, spec);
                params.put(spec.name(), v(label));
                params.put(spec.name() + "Code", v(codeTrunc.value()));
            }
        }

        session.run("""
            MERGE (c:Company {companyId: $companyId})
            SET c += $props
            """,
                Map.of("companyId", v(companyId), "props", params));

        log.debug("[Neo4j CDC] Upserted Company: {}", companyId);
    }

    private void upsertPerson(Session session, JsonNode row) {
        CdcPersonDisplay person = personDisplayResolver.fromEmployeeRow(row);
        String personKey = (person.personId() != null && !person.personId().isBlank())
                ? person.personId()
                : "NAME::" + (person.name() != null ? person.name() : "UNKNOWN");

        Map<String, Object> params = new HashMap<>();
        params.put("personKey", v(personKey));

        GraphNodeDefinition def = definitions.personDef();
        if (def != null) {
            for (GraphNodeFieldSpec spec : def.properties()) {
                String raw = CdcTdcompFields.firstText(row, spec.columns().toArray(new String[0]));
                if (raw == null) {
                    continue;
                }
                String label = fieldEnricher.enumLabel(spec.enumDictKey(), raw);
                CdcFieldTruncator.Truncation codeTrunc = truncator.truncateBySpec(raw, spec);
                params.put(spec.name(), v(label));
                params.put(spec.name() + "Code", v(codeTrunc.value()));
            }
        } else {
            params.put("name", v(person.name()));
            params.put("anotherName", v(person.anotherName()));
            params.put("displayName", v(person.displayName()));
        }
        params.put("personId", v(person.personId()));

        session.run("""
            MERGE (p:Person {personKey: $personKey})
            SET p += $props
            """,
                Map.of("personKey", v(personKey), "props", params));

        log.debug("[Neo4j CDC] Upserted Person: {}", personKey);
    }

    private void upsertBranch(Session session, JsonNode row) {
        String branchId = CdcEntityIdResolver.resolveEntityId("branch", row);
        if (branchId == null || branchId.isBlank()) {
            log.warn("[Neo4j CDC] branch_id is null, skipping");
            return;
        }

        String statusRaw = CdcTdcompFields.operatingStatus(row);
        session.run("""
            MERGE (b:Branch {branchId: $branchId})
            SET b.name = $name,
                b.status = $status,
                b.statusCode = $statusCode
            """,
                Map.of(
                        "branchId", v(branchId),
                        "name", v(CdcTdcompFields.firstText(row, "branch_name", "name")),
                        "status", v(fieldEnricher.branchStatusLabel(row)),
                        "statusCode", v(statusRaw)
                ));

        log.debug("[Neo4j CDC] Upserted Branch: {}", branchId);
    }

    private void upsertPartner(Session session, JsonNode row) {
        String partnerId = CdcEntityIdResolver.resolveEntityId("partner", row);
        String partnerName = CdcTdcompFields.firstText(row, "partner_name", "name");

        if (partnerId == null || partnerId.isBlank()) {
            partnerId = "NAME::" + (partnerName != null ? partnerName : "UNKNOWN");
        }

        session.run("""
            MERGE (p:Partner {partnerKey: $partnerKey})
            SET p.name = $name
            """,
                Map.of(
                        "partnerKey", v(partnerId),
                        "name", v(partnerName)
                ));

        log.debug("[Neo4j CDC] Upserted Partner: {}", partnerId);
    }

    private String idProperty(String table) {
        return switch (table) {
            case "employee" -> "personKey";
            case "branch" -> "branchId";
            case "partner" -> "partnerKey";
            default -> "companyId";
        };
    }

    private String toLabel(String table) {
        return switch (table) {
            case "company" -> "Company";
            case "employee" -> "Person";
            case "branch" -> "Branch";
            case "partner" -> "Partner";
            default -> "Unknown";
        };
    }
}

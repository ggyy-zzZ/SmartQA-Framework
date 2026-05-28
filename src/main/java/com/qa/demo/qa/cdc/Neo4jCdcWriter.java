package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CDC 事件写入 Neo4j 图谱。
 *
 * 将 Debezium CDC 事件的 after 字段 MERGE 到 Neo4j。
 * 支持 Company、Person、Branch、Partner 等节点及关系。
 */
@Component
public class Neo4jCdcWriter {

    private static final Logger log = LoggerFactory.getLogger(Neo4jCdcWriter.class);

    private final Driver driver;

    public Neo4jCdcWriter(Driver driver) {
        this.driver = driver;
    }

    /**
     * 将 CDC 事件写入 Neo4j。
     */
    public void write(String table, String op, JsonNode after, JsonNode before) {
        if (after == null && "d".equals(op)) {
            deleteEntity(table, before);
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
        } catch (Exception e) {
            log.error("[Neo4j CDC] Failed to write table={}, op={}", table, op, e);
            throw new RuntimeException("Neo4j CDC write failed", e);
        }
    }

    private void deleteEntity(String table, JsonNode before) {
        if (before == null) {
            log.warn("[Neo4j CDC] No before data for delete, ignoring");
            return;
        }

        try (Session session = driver.session()) {
            String entityId = extractEntityId(table, before);
            if (entityId == null) {
                log.warn("[Neo4j CDC] Cannot extract entity id for delete, table={}", table);
                return;
            }

            String label = toLabel(table);
            session.run("MATCH (n:" + label + " {companyId: $id}) DETACH DELETE n",
                    Map.of("id", entityId));
            log.info("[Neo4j CDC] Deleted {} id={}", label, entityId);
        } catch (Exception e) {
            log.error("[Neo4j CDC] Failed to delete table={}", table, e);
            throw new RuntimeException("Neo4j CDC delete failed", e);
        }
    }

    private Value v(String s) {
        return s == null ? Values.NULL : Values.value(s);
    }

    // ==================== Company ====================

    private void upsertCompany(Session session, JsonNode row) {
        String companyId = getText(row, "company_id");
        if (companyId == null || companyId.isBlank()) {
            log.warn("[Neo4j CDC] company_id is null, skipping");
            return;
        }

        session.run("""
            MERGE (c:Company {companyId: $companyId})
            SET c.name = $name,
                c.shortName = $shortName,
                c.creditCode = $creditCode,
                c.status = $status,
                c.entityType = $entityType,
                c.entityCategory = $entityCategory,
                c.modifytime = $modifytime
            """,
                Map.of(
                        "companyId", v(companyId),
                        "name", v(getText(row, "company_name")),
                        "shortName", v(getText(row, "company_short_name")),
                        "creditCode", v(getText(row, "credit_code")),
                        "status", v(getText(row, "status")),
                        "entityType", v(getText(row, "entity_type")),
                        "entityCategory", v(getText(row, "entity_category")),
                        "modifytime", v(getText(row, "modifytime"))
                ));

        log.debug("[Neo4j CDC] Upserted Company: {}", companyId);
    }

    // ==================== Person (Employee) ====================

    private void upsertPerson(Session session, JsonNode row) {
        String personId = getText(row, "employee_id");
        String name = getText(row, "name");
        String companyId = getText(row, "company_id");

        String personKey = (personId != null && !personId.isBlank())
                ? personId
                : "NAME::" + (name != null ? name : "UNKNOWN");

        session.run("""
            MERGE (p:Person {personKey: $personKey})
            SET p.name = $name,
                p.personId = $personId
            """,
                Map.of(
                        "personKey", v(personKey),
                        "name", v(name),
                        "personId", v(personId)
                ));

        if (companyId != null && !companyId.isBlank()) {
            String role = getText(row, "role");
            session.run("""
                MATCH (p:Person {personKey: $personKey})
                MATCH (c:Company {companyId: $companyId})
                MERGE (p)-[r:HAS_ROLE_IN {role: $role}]->(c)
                """,
                    Map.of(
                            "personKey", v(personKey),
                            "companyId", v(companyId),
                            "role", v(role != null ? role : "员工")
                    ));
        }

        log.debug("[Neo4j CDC] Upserted Person: {}", personKey);
    }

    // ==================== Branch ====================

    private void upsertBranch(Session session, JsonNode row) {
        String branchId = getText(row, "branch_id");
        String companyId = getText(row, "company_id");
        if (branchId == null || branchId.isBlank()) {
            log.warn("[Neo4j CDC] branch_id is null, skipping");
            return;
        }

        session.run("""
            MERGE (b:Branch {branchId: $branchId})
            SET b.name = $name,
                b.status = $status
            """,
                Map.of(
                        "branchId", v(branchId),
                        "name", v(getText(row, "branch_name")),
                        "status", v(getText(row, "status"))
                ));

        if (companyId != null && !companyId.isBlank()) {
            session.run("""
                MATCH (b:Branch {branchId: $branchId})
                MATCH (c:Company {companyId: $companyId})
                MERGE (c)-[:HAS_BRANCH]->(b)
                """,
                    Map.of("branchId", v(branchId), "companyId", v(companyId)));
        }

        log.debug("[Neo4j CDC] Upserted Branch: {}", branchId);
    }

    // ==================== Partner ====================

    private void upsertPartner(Session session, JsonNode row) {
        String partnerId = getText(row, "partner_id");
        String companyId = getText(row, "company_id");
        String partnerName = getText(row, "partner_name");

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

        if (companyId != null && !companyId.isBlank()) {
            String relationType = getText(row, "relation_type");
            session.run("""
                MATCH (p:Partner {partnerKey: $partnerKey})
                MATCH (c:Company {companyId: $companyId})
                MERGE (c)-[r:PARTNER_OF {type: $relationType}]->(p)
                """,
                    Map.of(
                            "partnerKey", v(partnerId),
                            "companyId", v(companyId),
                            "relationType", v(relationType)
                    ));
        }

        log.debug("[Neo4j CDC] Upserted Partner: {}", partnerId);
    }

    // ==================== Helpers ====================

    private String extractEntityId(String table, JsonNode row) {
        return switch (table) {
            case "company" -> getText(row, "company_id");
            case "employee" -> getText(row, "employee_id");
            case "branch" -> getText(row, "branch_id");
            case "partner" -> getText(row, "partner_id");
            default -> null;
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

    private String getText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() ? null : n.asText(null);
    }
}
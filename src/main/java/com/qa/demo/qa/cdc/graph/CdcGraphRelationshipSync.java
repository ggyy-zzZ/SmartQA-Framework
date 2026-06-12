package com.qa.demo.qa.cdc.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.cdc.CdcEntityIdResolver;
import com.qa.demo.qa.cdc.CdcPersonDisplay;
import com.qa.demo.qa.cdc.CdcPersonDisplayResolver;
import com.qa.demo.qa.cdc.CdcTdcompFields;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 cdc-graph-sync.json 将 CDC 行同步到 Neo4j 关系（含任职类边的替换/清理）。
 */
@Component
public class CdcGraphRelationshipSync {

    private static final Logger log = LoggerFactory.getLogger(CdcGraphRelationshipSync.class);

    private final CdcGraphSyncCatalog catalog;
    private final CdcPersonRoleBindingExtractor bindingExtractor;
    private final CdcPersonDisplayResolver personDisplayResolver;

    public CdcGraphRelationshipSync(
            CdcGraphSyncCatalog catalog,
            CdcPersonRoleBindingExtractor bindingExtractor,
            CdcPersonDisplayResolver personDisplayResolver
    ) {
        this.catalog = catalog;
        this.bindingExtractor = bindingExtractor;
        this.personDisplayResolver = personDisplayResolver;
    }

    public void syncTable(Session session, String table, JsonNode row) {
        for (CdcGraphSyncCatalog.RelationshipRuleDef rule : catalog.rulesForTable(table)) {
            try {
                applyRule(session, rule, table, row);
            } catch (Exception e) {
                log.error("[CDC Graph] Rule {} failed for table={}: {}", rule.id(), table, e.getMessage());
                throw e;
            }
        }
    }

    private void applyRule(
            Session session,
            CdcGraphSyncCatalog.RelationshipRuleDef rule,
            String table,
            JsonNode row
    ) {
        if (rule.personIdBindings() != null
                && "role_column_catalog".equalsIgnoreCase(rule.personIdBindings().mode())) {
            syncCompanyPersonRoleSlots(session, rule, row);
            return;
        }
        if (rule.roleLabel() != null && rule.fromId() != null && rule.fromId().personKeyStrategy() != null) {
            syncEmployeeCompanyRole(session, rule, row);
            return;
        }
        syncStructuralRelationship(session, rule, row);
    }

    private void syncCompanyPersonRoleSlots(
            Session session,
            CdcGraphSyncCatalog.RelationshipRuleDef rule,
            JsonNode row
    ) {
        String companyId = resolveEntityId(rule.toId(), "company", row);
        if (companyId == null || companyId.isBlank()) {
            log.warn("[CDC Graph] company id missing, skip rule {}", rule.id());
            return;
        }
        CdcGraphSyncCatalog.NodeTypeDef personType = catalog.nodeType(rule.fromNodeType());
        CdcGraphSyncCatalog.NodeTypeDef companyType = catalog.nodeType(rule.toNodeType());
        if (personType == null || companyType == null) {
            return;
        }

        for (CdcPersonRoleBinding binding : bindingExtractor.fromCompanyRow(row)) {
            if (rule.exclusiveRoleLabel()) {
                clearRoleSlot(session, companyType, companyId, binding.roleLabel(), rule.relationshipType());
            }
            if (binding.personId() == null || binding.personId().isBlank()) {
                continue;
            }
            mergePerson(session, personType, binding.personKey(), personDisplayResolver.fromPersonId(binding.personId()));
            mergeRoleEdge(
                    session,
                    personType,
                    companyType,
                    binding.personKey(),
                    companyId,
                    binding.roleLabel(),
                    rule,
                    binding.sourceColumn()
            );
        }
    }

    private void syncEmployeeCompanyRole(
            Session session,
            CdcGraphSyncCatalog.RelationshipRuleDef rule,
            JsonNode row
    ) {
        String personKey = bindingExtractor.personKeyFromEmployeeRow(row);
        CdcPersonDisplay person = personDisplayResolver.fromEmployeeRow(row);
        String companyId = CdcRowFieldReader.firstText(row, rule.toId().columns());
        if (companyId == null || companyId.isBlank()) {
            return;
        }
        String roleLabel = CdcRowFieldReader.firstText(row, rule.roleLabel().columns());
        if (roleLabel == null || roleLabel.isBlank()) {
            roleLabel = rule.roleLabel().defaultLabel();
        }

        CdcGraphSyncCatalog.NodeTypeDef personType = catalog.nodeType(rule.fromNodeType());
        CdcGraphSyncCatalog.NodeTypeDef companyType = catalog.nodeType(rule.toNodeType());
        mergePerson(session, personType, personKey, person);
        mergeRoleEdge(session, personType, companyType, personKey, companyId, roleLabel, rule, "employee.role");
    }

    private void syncStructuralRelationship(
            Session session,
            CdcGraphSyncCatalog.RelationshipRuleDef rule,
            JsonNode row
    ) {
        String fromId = resolveEndpointId(rule.fromId(), rule.fromNodeType(), row);
        String toId = resolveEndpointId(rule.toId(), rule.toNodeType(), row);
        if (fromId == null || toId == null) {
            return;
        }
        CdcGraphSyncCatalog.NodeTypeDef fromType = catalog.nodeType(rule.fromNodeType());
        CdcGraphSyncCatalog.NodeTypeDef toType = catalog.nodeType(rule.toNodeType());
        if (fromType == null || toType == null) {
            return;
        }

        Map<String, Object> relProps = new HashMap<>();
        for (CdcGraphSyncCatalog.RelPropertyDef prop : rule.properties()) {
            String value = CdcRowFieldReader.firstText(row, prop.columns());
            if (value != null) {
                relProps.put(prop.name(), value);
            }
        }

        String relType = rule.relationshipType();
        String cypher = """
                MATCH (a:%s {%s: $fromId})
                MATCH (b:%s {%s: $toId})
                MERGE (a)-[r:%s]->(b)
                """.formatted(
                fromType.label(), fromType.idProperty(),
                toType.label(), toType.idProperty(),
                relType
        );
        if (!relProps.isEmpty()) {
            cypher += " SET r += $relProps";
        }
        Map<String, Object> params = new HashMap<>();
        params.put("fromId", fromId);
        params.put("toId", toId);
        if (!relProps.isEmpty()) {
            params.put("relProps", relProps);
        }
        session.run(cypher, params);
    }

    private void clearRoleSlot(
            Session session,
            CdcGraphSyncCatalog.NodeTypeDef companyType,
            String companyId,
            String roleLabel,
            String relationshipType
    ) {
        session.run(
                """
                MATCH (c:%s {%s: $companyId})<-[r:%s]-(:Person)
                WHERE r.role = $roleLabel
                DELETE r
                """.formatted(companyType.label(), companyType.idProperty(), relationshipType),
                Map.of("companyId", companyId, "roleLabel", roleLabel)
        );
    }

    private void mergePerson(
            Session session,
            CdcGraphSyncCatalog.NodeTypeDef personType,
            String personKey,
            CdcPersonDisplay person
    ) {
        if (person == null) {
            person = new CdcPersonDisplay(null, null, null);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("personKey", personKey);
        params.put("personId", person.personId());
        params.put("name", person.name());
        params.put("anotherName", person.anotherName());
        params.put("displayName", person.displayName());
        session.run(
                """
                MERGE (p:%s {%s: $personKey})
                SET p.%s = $personId,
                    p.%s = $name,
                    p.anotherName = $anotherName,
                    p.displayName = $displayName
                """.formatted(
                        personType.label(),
                        personType.idProperty(),
                        personType.personIdProperty(),
                        personType.nameProperty()
                ),
                params
        );
    }

    private void mergeRoleEdge(
            Session session,
            CdcGraphSyncCatalog.NodeTypeDef personType,
            CdcGraphSyncCatalog.NodeTypeDef companyType,
            String personKey,
            String companyId,
            String roleLabel,
            CdcGraphSyncCatalog.RelationshipRuleDef rule,
            String sourceColumn
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("personKey", personKey);
        params.put("companyId", companyId);
        params.put("roleLabel", roleLabel);
        String setMeta = "";
        if (rule.stampSourceColumn() && sourceColumn != null) {
            params.put("sourceColumn", sourceColumn);
            setMeta = ", r.sourceColumn = $sourceColumn";
        }
        session.run(
                """
                MATCH (p:%s {%s: $personKey})
                MATCH (c:%s {%s: $companyId})
                MERGE (p)-[r:%s {role: $roleLabel}]->(c)
                """.formatted(
                        personType.label(), personType.idProperty(),
                        companyType.label(), companyType.idProperty(),
                        rule.relationshipType()
                ) + " SET r.role = $roleLabel" + setMeta,
                params
        );
    }

    private String resolveEndpointId(
            CdcGraphSyncCatalog.IdRefDef idRef,
            String nodeTypeKey,
            JsonNode row
    ) {
        if (idRef == null) {
            return null;
        }
        if (idRef.resolveEntityTable() != null) {
            return resolveEntityId(idRef, idRef.resolveEntityTable(), row);
        }
        return CdcRowFieldReader.firstText(row, idRef.columns());
    }

    private String resolveEntityId(CdcGraphSyncCatalog.IdRefDef idRef, String table, JsonNode row) {
        if (idRef != null && idRef.resolveEntityTable() != null) {
            table = idRef.resolveEntityTable();
        }
        return CdcEntityIdResolver.resolveEntityId(table, row);
    }
}

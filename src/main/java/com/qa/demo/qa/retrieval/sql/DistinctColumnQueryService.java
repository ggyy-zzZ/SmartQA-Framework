package com.qa.demo.qa.retrieval.sql;

import com.qa.demo.knowledge.EvidenceSchemaRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.SemanticSchemaColumnRef;
import com.qa.demo.qa.config.SemanticSchemaRegistry;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.domain.EnterpriseEnumLabelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * type_catalog 通用通路：按 semantic-schema 匹配列，对业务表执行 {@code SELECT DISTINCT}。
 */
@Service
public class DistinctColumnQueryService {

    private static final Logger log = LoggerFactory.getLogger(DistinctColumnQueryService.class);
    private static final double CATALOG_SCORE = 32.0;
    private static final String SCHEMA_ID = "catalog_v1";
    private static final String SOURCE = "mysql-distinct-column";

    private final QaAssistantProperties properties;
    private final SemanticSchemaRegistry schemaRegistry;
    private final EnterpriseEnumLabelService enumLabels;
    private final EvidenceSchemaRegistry evidenceSchemas;

    public DistinctColumnQueryService(
            QaAssistantProperties properties,
            SemanticSchemaRegistry schemaRegistry,
            EnterpriseEnumLabelService enumLabels,
            EvidenceSchemaRegistry evidenceSchemas
    ) {
        this.properties = properties;
        this.schemaRegistry = schemaRegistry;
        this.enumLabels = enumLabels;
        this.evidenceSchemas = evidenceSchemas;
    }

    public Optional<SemanticSchemaColumnRef> resolveColumn(String question) {
        return schemaRegistry.matchDistinctColumn(question);
    }

    public List<ContextChunk> retrieve(String question, int limit) {
        if (!properties.isMysqlEnabled() || question == null || question.isBlank()) {
            return List.of();
        }
        Optional<SemanticSchemaColumnRef> columnRef = resolveColumn(question);
        if (columnRef.isEmpty()) {
            return List.of();
        }
        SemanticSchemaColumnRef ref = columnRef.get();
        int capped = Math.max(1, Math.min(limit, properties.getSqlQueryMaxRows()));
        String sql = buildDistinctSql(ref, capped);
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.setMaxRows(capped);
            try (ResultSet rs = statement.executeQuery(sql)) {
                Set<String> seenRaw = new LinkedHashSet<>();
                Set<String> seenDisplay = new LinkedHashSet<>();
                List<ContextChunk> chunks = new ArrayList<>();
                while (rs.next() && chunks.size() < capped) {
                    String raw = safe(rs.getString("val"));
                    if (raw.isBlank() || !seenRaw.add(raw)) {
                        continue;
                    }
                    ContextChunk chunk = toCatalogChunk(ref, raw);
                    if (!seenDisplay.add(chunk.displayLabel())) {
                        continue;
                    }
                    chunks.add(chunk);
                }
                return chunks;
            }
        } catch (Exception ex) {
            log.warn("distinct column query failed entity={} column={}: {}",
                    ref.entityId(), ref.column(), ex.getMessage());
            return List.of();
        }
    }

    private ContextChunk toCatalogChunk(SemanticSchemaColumnRef ref, String rawValue) {
        String display = ref.enumField() == null || ref.enumField().isBlank()
                ? rawValue
                : enumLabels.label(ref.enumField(), rawValue);
        String anchorId = ref.entityId() + ":" + ref.column() + "#" + rawValue;
        String snippet = formatCatalogSnippet(ref.label(), display);
        return new ContextChunk(
                anchorId,
                display,
                ContextChunk.KIND_SYSTEM,
                ref.column(),
                snippet,
                CATALOG_SCORE,
                SOURCE,
                SCHEMA_ID
        );
    }

    private String formatCatalogSnippet(String entryType, String entryName) {
        String formatted = evidenceSchemas.formatSnippet(
                SCHEMA_ID,
                Map.of("entryType", entryType, "entryName", entryName)
        );
        if (formatted != null && !formatted.isBlank()) {
            return formatted;
        }
        return "条目类型=" + entryType + "; 名称=" + entryName;
    }

    private static String buildDistinctSql(SemanticSchemaColumnRef ref, int limit) {
        String column = ref.column();
        String table = ref.table();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT `").append(column).append("` AS val FROM `").append(table).append("`");
        sql.append(" WHERE `").append(column).append("` IS NOT NULL");
        sql.append(" AND TRIM(`").append(column).append("`) <> ''");
        if (ref.softDeleteColumn() != null && !ref.softDeleteColumn().isBlank()) {
            sql.append(" AND `").append(ref.softDeleteColumn()).append("` = 0");
        }
        sql.append(" ORDER BY 1 LIMIT ").append(limit);
        return sql.toString();
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

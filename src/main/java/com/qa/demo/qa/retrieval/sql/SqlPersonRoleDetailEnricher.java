package com.qa.demo.qa.retrieval.sql;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase2：按图谱边界 companyId 从 MySQL 拉取任职明细（source=mysql-detail）。
 */
@Component
public class SqlPersonRoleDetailEnricher {

    private static final Logger log = LoggerFactory.getLogger(SqlPersonRoleDetailEnricher.class);

    private final QaAssistantProperties properties;
    private final SqlPersonRoleRetriever personRoleRetriever;

    public SqlPersonRoleDetailEnricher(QaAssistantProperties properties, SqlPersonRoleRetriever personRoleRetriever) {
        this.properties = properties;
        this.personRoleRetriever = personRoleRetriever;
    }

    public List<ContextChunk> enrichFromBoundaries(
            String question,
            RetrievalPlan plan,
            List<ContextChunk> boundaries,
            int topK
    ) {
        if (boundaries == null || boundaries.isEmpty()) {
            return List.of();
        }
        Set<String> companyIds = new LinkedHashSet<>();
        for (ContextChunk chunk : boundaries) {
            if (chunk == null || chunk.anchorId() == null || chunk.anchorId().isBlank()) {
                continue;
            }
            String source = chunk.source() == null ? "" : chunk.source().toLowerCase(Locale.ROOT);
            if (source.contains("neo4j")) {
                companyIds.add(chunk.anchorId().trim());
            }
        }
        if (companyIds.isEmpty()) {
            return List.of();
        }
        IntentDecision intent = plan != null ? plan.intent() : null;
        List<ContextChunk> sqlChunks;
        try (Connection connection = DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        )) {
            sqlChunks = personRoleRetriever.retrieve(connection, question, intent, topK);
        } catch (Exception e) {
            log.debug("[PersonRoleEnricher] failed: {}", e.getMessage());
            return List.of();
        }
        List<ContextChunk> enriched = new ArrayList<>();
        for (ContextChunk chunk : sqlChunks) {
            if (chunk == null || chunk.anchorId() == null) {
                continue;
            }
            if (!companyIds.contains(chunk.anchorId().trim())) {
                continue;
            }
            enriched.add(new ContextChunk(
                    chunk.anchorId(),
                    chunk.displayLabel(),
                    chunk.entityKind(),
                    chunk.field(),
                    chunk.snippet(),
                    Math.max(chunk.score(), 24.0),
                    "mysql-detail",
                    chunk.evidenceSchema()
            ));
        }
        return enriched;
    }
}

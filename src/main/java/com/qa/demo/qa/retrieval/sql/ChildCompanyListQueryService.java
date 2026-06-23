package com.qa.demo.qa.retrieval.sql;

import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.SemanticSchemaChildCompanyRef;
import com.qa.demo.qa.config.SemanticSchemaEnumFilter;
import com.qa.demo.qa.config.SemanticSchemaRegistry;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.domain.EnterpriseEnumLabelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * 母公司/总部下属主体列表：按 semantic-schema 子公司列 + 可选枚举筛选查库。
 */
@Service
public class ChildCompanyListQueryService {

    private static final Logger log = LoggerFactory.getLogger(ChildCompanyListQueryService.class);

    private final QaAssistantProperties properties;
    private final SemanticSchemaRegistry schemaRegistry;
    private final EnterpriseCanonicalFactsRegistry canonicalFactsRegistry;
    private final EnterpriseEnumLabelService enumLabels;

    public ChildCompanyListQueryService(
            QaAssistantProperties properties,
            SemanticSchemaRegistry schemaRegistry,
            EnterpriseCanonicalFactsRegistry canonicalFactsRegistry,
            EnterpriseEnumLabelService enumLabels
    ) {
        this.properties = properties;
        this.schemaRegistry = schemaRegistry;
        this.canonicalFactsRegistry = canonicalFactsRegistry;
        this.enumLabels = enumLabels;
    }

    public List<ContextChunk> retrieve(String question, int limit) {
        if (!properties.isMysqlEnabled() || question == null || question.isBlank()) {
            return List.of();
        }
        OptionalInt parentId = canonicalFactsRegistry.resolveCompanyAnchorId(question);
        if (parentId.isEmpty()) {
            return List.of();
        }
        Optional<SemanticSchemaChildCompanyRef> childRef = schemaRegistry.findChildCompanyReference();
        if (childRef.isEmpty()) {
            return List.of();
        }
        List<SemanticSchemaEnumFilter> filters = schemaRegistry.matchEnumAttributeFilters(question);
        int capped = Math.max(1, Math.min(limit, properties.getSqlQueryMaxRows()));
        return query(childRef.get(), filters, parentId.getAsInt(), capped);
    }

    private List<ContextChunk> query(
            SemanticSchemaChildCompanyRef ref,
            List<SemanticSchemaEnumFilter> filters,
            int parentId,
            int limit
    ) {
        SqlParts parts = buildSqlParts(ref, filters, limit);
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(parts.sql())) {
            ps.setInt(1, parentId);
            for (int i = 0; i < parts.filterValues().size(); i++) {
                ps.setString(i + 2, parts.filterValues().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<ContextChunk> chunks = new ArrayList<>();
                int row = 0;
                while (rs.next() && row < limit) {
                    row++;
                    String companyId = safe(rs.getString("company_id"));
                    String companyName = safe(rs.getString("company_name"));
                    String operatingStatus = safe(rs.getString("operating_status"));
                    if (companyId.isBlank()) {
                        companyId = "row-" + row;
                    }
                    if (companyName.isBlank()) {
                        companyName = companyId;
                    }
                    String statusLabel = "";
                    for (SemanticSchemaEnumFilter filter : filters) {
                        if (filter.column().equals("operating_status")) {
                            statusLabel = enumLabels.label(filter.enumField(), operatingStatus);
                            break;
                        }
                    }
                    String snippet = "company_name=" + companyName
                            + "; operating_status=" + operatingStatus
                            + (statusLabel.isBlank() ? "" : "; operating_status_label=" + statusLabel)
                            + "; parent_company_id=" + parentId;
                    chunks.add(ContextChunk.ofCompany(
                            companyId,
                            companyName,
                            "structured_child_company_list",
                            snippet,
                            24.0 - row * 0.05,
                            "mysql-structured-child_company"
                    ));
                }
                return chunks;
            }
        } catch (Exception ex) {
            log.warn("ChildCompanyListQueryService query failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private record SqlParts(String sql, List<String> filterValues) {
    }

    private SqlParts buildSqlParts(SemanticSchemaChildCompanyRef ref, List<SemanticSchemaEnumFilter> filters, int limit) {
        List<String> filterValues = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id AS company_id, company_name, operating_status FROM `")
                .append(ref.childTable()).append("` WHERE `")
                .append(ref.childColumn()).append("` = ?");
        if (ref.softDeleteColumn() != null && !ref.softDeleteColumn().isBlank()) {
            sql.append(" AND `").append(ref.softDeleteColumn()).append("` = 0");
        }
        for (SemanticSchemaEnumFilter filter : filters) {
            if (filter.matchedCodes().isEmpty()) {
                continue;
            }
            String placeholders = filter.matchedCodes().stream().map(c -> "?").collect(Collectors.joining(", "));
            sql.append(" AND `").append(filter.column()).append("` IN (").append(placeholders).append(")");
            filterValues.addAll(filter.matchedCodes());
        }
        sql.append(" ORDER BY company_name LIMIT ").append(limit);
        return new SqlParts(sql.toString(), filterValues);
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

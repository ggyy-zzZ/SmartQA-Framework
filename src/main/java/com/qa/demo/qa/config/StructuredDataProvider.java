package com.qa.demo.qa.config;

import com.qa.demo.qa.config.BusinessRulesConfig.ProjectionColumnConfig;
import com.qa.demo.qa.config.BusinessRulesConfig.RoleColumnConfig;
import com.qa.demo.qa.config.BusinessRulesConfig.StructuredQueryConfig;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.domain.EnterpriseEnumLabelService;
import com.qa.demo.qa.retrieval.personcert.PersonCertificateStewardship;
import com.qa.demo.qa.retrieval.structured.BusinessCompanyScopeResolver;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 配置化的业务库结构化查询（表/列/枚举均来自 business-rules.json）。
 */
@Component
public class StructuredDataProvider {

    private static final double ROW_SCORE = 28.0;

    private final QaAssistantProperties properties;
    private final BusinessRulesConfig config;
    private final EnterpriseEnumLabelService enumLabels;
    private final BusinessCompanyScopeResolver companyScopeResolver;

    public StructuredDataProvider(
            QaAssistantProperties properties,
            BusinessRulesConfig config,
            EnterpriseEnumLabelService enumLabels,
            BusinessCompanyScopeResolver companyScopeResolver
    ) {
        this.properties = properties;
        this.config = config;
        this.enumLabels = enumLabels;
        this.companyScopeResolver = companyScopeResolver;
    }

    public List<ContextChunk> queryByEmployeeIds(String configId, Set<Integer> employeeIds, int maxRows) {
        Optional<StructuredQueryConfig> optConfig = findConfig(configId);
        if (optConfig.isEmpty() || employeeIds == null || employeeIds.isEmpty()) {
            return List.of();
        }
        StructuredQueryConfig queryConfig = optConfig.get();
        if (queryConfig.isCompanyScope()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxRows, 128));
        try (Connection connection = openBusinessConnection()) {
            Map<String, String> companyNames = loadCompanyLookup(queryConfig);
            List<StewardshipRow> rows = executeEmployeeRoleQuery(connection, queryConfig, employeeIds, limit);
            return stewardshipToChunks(rows, queryConfig, companyNames);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public List<ContextChunk> queryByCompanyIds(
            String configId,
            Set<String> companyIds,
            boolean activeCertificatesOnly,
            int maxRows
    ) {
        Optional<StructuredQueryConfig> optConfig = findConfig(configId);
        if (optConfig.isEmpty() || companyIds == null || companyIds.isEmpty()) {
            return List.of();
        }
        StructuredQueryConfig queryConfig = optConfig.get();
        if (!queryConfig.isCompanyScope()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxRows, 256));
        try (Connection connection = openBusinessConnection()) {
            Map<String, String> companyNames = loadCompanyLookup(queryConfig);
            List<InstanceRow> rows = executeCompanyInstanceQuery(
                    connection, queryConfig, companyIds, activeCertificatesOnly, limit);
            return instancesToChunks(rows, queryConfig, companyNames);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @Deprecated
    public List<ContextChunk> query(String configId, Set<Integer> entityIds, String entityIdColumn, int maxRows) {
        return queryByEmployeeIds(configId, entityIds, maxRows);
    }

    public Optional<StructuredQueryConfig> findConfig(String configId) {
        if (configId == null || configId.isBlank()) {
            return Optional.empty();
        }
        return config.getDataSources().getStructuredQueries().stream()
                .filter(c -> configId.equals(c.getId()))
                .findFirst();
    }

    public Optional<StructuredQueryConfig> findConfigForQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return Optional.empty();
        }
        return config.getDataSources().getStructuredQueries().stream()
                .filter(c -> c.appliesToQueryType(queryType))
                .findFirst();
    }

    private Connection openBusinessConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }

    private Map<String, String> loadCompanyLookup(StructuredQueryConfig queryConfig) {
        if (queryConfig.getSupplementalTables().stream().anyMatch(t -> "company".equalsIgnoreCase(t))) {
            return companyScopeResolver.loadCompanyNames();
        }
        return Map.of();
    }

    private List<StewardshipRow> executeEmployeeRoleQuery(
            Connection connection,
            StructuredQueryConfig queryConfig,
            Set<Integer> employeeIds,
            int limit
    ) throws SQLException {
        List<StewardshipRow> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(queryConfig.getEntityIdColumn());
        for (RoleColumnConfig rc : queryConfig.getRoleColumns()) {
            sql.append(", ").append(rc.getColumn());
        }
        sql.append(", ").append(queryConfig.getScopeColumn());
        if (hasProjectionOrLegacy(queryConfig, "certificate_type")) {
            sql.append(", certificate_type");
        }
        sql.append(", status FROM ").append(queryConfig.getTable());
        sql.append(" WHERE ").append(queryConfig.getDeleteflagColumn()).append(" = 0");

        try (PreparedStatement ps = connection.prepareStatement(sql.toString());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next() && result.size() < limit) {
                String recordId = String.valueOf(rs.getObject(queryConfig.getEntityIdColumn()));
                String companyId = String.valueOf(rs.getObject(queryConfig.getScopeColumn()));
                String certType = resolveCertType(rs, queryConfig);
                for (RoleColumnConfig rc : queryConfig.getRoleColumns()) {
                    for (int empId : parseIdList(rs.getString(rc.getColumn()))) {
                        if (!employeeIds.contains(empId)) {
                            continue;
                        }
                        String statusLabel = normalizeStatus(rs.getObject("status"), queryConfig);
                        result.add(new StewardshipRow(
                                recordId, companyId, rc.getLabel(), certType, statusLabel, empId
                        ));
                    }
                }
            }
        }
        return result;
    }

    private List<InstanceRow> executeCompanyInstanceQuery(
            Connection connection,
            StructuredQueryConfig queryConfig,
            Set<String> companyIds,
            boolean activeCertificatesOnly,
            int limit
    ) throws SQLException {
        List<InstanceRow> result = new ArrayList<>();
        List<String> activeValues = resolveActiveStatusValues(queryConfig);
        String placeholders = buildPlaceholders(companyIds.size());
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(queryConfig.getEntityIdColumn()).append(", ").append(queryConfig.getScopeColumn());
        boolean hasStatus = false;
        for (ProjectionColumnConfig proj : queryConfig.getProjections()) {
            sql.append(", ").append(proj.getColumn());
            if ("status".equalsIgnoreCase(proj.getColumn())) {
                hasStatus = true;
            }
        }
        if (!hasStatus) {
            sql.append(", status");
        }
        sql.append(" FROM ").append(queryConfig.getTable());
        sql.append(" WHERE ").append(queryConfig.getDeleteflagColumn()).append(" = 0");
        sql.append(" AND ").append(queryConfig.getScopeColumn()).append(" IN (").append(placeholders).append(")");
        if (activeCertificatesOnly && !activeValues.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < activeValues.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("status = ?");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY ").append(queryConfig.getScopeColumn()).append(", ")
                .append(queryConfig.getEntityIdColumn()).append(" LIMIT ?");

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String companyId : companyIds) {
                ps.setString(idx++, companyId);
            }
            if (activeCertificatesOnly && !activeValues.isEmpty()) {
                for (String value : activeValues) {
                    ps.setString(idx++, value);
                }
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new InstanceRow(
                            String.valueOf(rs.getObject(queryConfig.getEntityIdColumn())),
                            String.valueOf(rs.getObject(queryConfig.getScopeColumn())),
                            projectRow(rs, queryConfig)
                    ));
                }
            }
        }
        return result;
    }

    private List<String> resolveActiveStatusValues(StructuredQueryConfig queryConfig) {
        if (!queryConfig.getStatusActiveValues().isEmpty()) {
            return queryConfig.getStatusActiveValues();
        }
        return config.getCertificateRetrieval().getActiveCertificateStatusValues();
    }

    private List<ContextChunk> stewardshipToChunks(
            List<StewardshipRow> rows,
            StructuredQueryConfig queryConfig,
            Map<String, String> companyNames
    ) {
        List<ContextChunk> chunks = new ArrayList<>();
        String source = "mysql-structured-" + queryConfig.getId();
        for (StewardshipRow row : rows) {
            String companyName = companyNames.getOrDefault(row.companyId(), "（公司名称未加载）");
            PersonCertificateStewardship stewardship = new PersonCertificateStewardship(
                    row.certType(),
                    companyName,
                    row.roleLabel(),
                    row.statusLabel(),
                    row.recordId(),
                    row.companyId(),
                    String.valueOf(row.employeeId())
            );
            chunks.add(ContextChunk.ofCompany(
                    row.recordId(),
                    companyName,
                    queryConfig.getDescription(),
                    stewardship.toEvidenceLine(null),
                    ROW_SCORE,
                    source,
                    PersonCertificateStewardship.SCHEMA_ID
            ));
        }
        return chunks;
    }

    private List<ContextChunk> instancesToChunks(
            List<InstanceRow> rows,
            StructuredQueryConfig queryConfig,
            Map<String, String> companyNames
    ) {
        List<ContextChunk> chunks = new ArrayList<>();
        String source = "mysql-structured-" + queryConfig.getId();
        for (InstanceRow row : rows) {
            String companyName = companyNames.getOrDefault(row.companyId(), "（公司名称未加载）");
            String certType = row.projected().getOrDefault("证照类型", "");
            String status = row.projected().getOrDefault("状态", "");
            String evidence = String.format("登记证照 | %s | %s | 状态: %s", certType, companyName, status);
            chunks.add(ContextChunk.ofCompany(
                    row.recordId(),
                    companyName,
                    queryConfig.getDescription(),
                    evidence,
                    ROW_SCORE,
                    source,
                    PersonCertificateStewardship.SCHEMA_ID
            ));
        }
        return chunks;
    }

    private Map<String, String> projectRow(ResultSet rs, StructuredQueryConfig queryConfig) throws SQLException {
        Map<String, String> projected = new LinkedHashMap<>();
        for (ProjectionColumnConfig proj : queryConfig.getProjections()) {
            String raw = rs.getString(proj.getColumn());
            String label = proj.getLabel() != null ? proj.getLabel() : proj.getColumn();
            if (proj.getEnumField() != null && !proj.getEnumField().isBlank()) {
                projected.put(label, enumLabels.label(proj.getEnumField(), raw));
            } else if ("status".equalsIgnoreCase(proj.getColumn())) {
                projected.put(label, normalizeStatus(raw, queryConfig));
            } else {
                projected.put(label, raw == null ? "" : raw);
            }
        }
        if (!projected.containsKey("状态")) {
            projected.put("状态", normalizeStatus(rs.getObject("status"), queryConfig));
        }
        String typeLabel = projected.get("证照类型");
        if (typeLabel == null || typeLabel.isBlank()) {
            projected.put("证照类型", resolveCertType(rs, queryConfig));
        }
        return projected;
    }

    private String resolveCertType(ResultSet rs, StructuredQueryConfig queryConfig) throws SQLException {
        if (!hasProjectionOrLegacy(queryConfig, "certificate_type")) {
            return "";
        }
        String raw = rs.getString("certificate_type");
        String enumField = queryConfig.getEnumMappings().getOrDefault("cert_type", "certificateTypes");
        return enumLabels.label(enumField, raw == null ? "" : raw);
    }

    private static boolean hasProjectionOrLegacy(StructuredQueryConfig config, String column) {
        if (config.getEnumMappings().containsKey("cert_type")) {
            return true;
        }
        return config.getProjections().stream().anyMatch(p -> column.equalsIgnoreCase(p.getColumn()));
    }

    private static List<Integer> parseIdList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(item);
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return ids;
    }

    private static String normalizeStatus(Object value, StructuredQueryConfig queryConfig) {
        if (value == null) {
            return "未知";
        }
        String text = String.valueOf(value).trim();
        String normalized = queryConfig.getStatusNormalization().get(text);
        return normalized != null ? normalized : text;
    }

    private static String buildPlaceholders(int size) {
        int n = Math.max(1, size);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    private record StewardshipRow(
            String recordId,
            String companyId,
            String roleLabel,
            String certType,
            String statusLabel,
            int employeeId
    ) {
    }

    private record InstanceRow(String recordId, String companyId, Map<String, String> projected) {
    }
}

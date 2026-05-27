package com.qa.demo.qa.config;

import com.qa.demo.qa.config.BusinessRulesConfig.StructuredQueryConfig;
import com.qa.demo.qa.config.BusinessRulesConfig.RoleColumnConfig;
import com.qa.demo.qa.core.ContextChunk;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * 配置化的数据源 Provider。
 * <p>
 * 所有结构化数据查询（MySQL/业务库）都通过此组件执行，
 * 查询逻辑（表名、字段、角色配置）从 business-rules.json 读取。
 * <p>
 * 原则：
 * <ul>
 *   <li>不硬编码表名、字段名、角色标签</li>
 *   <li>新增数据源只需在 business-rules.json 中配置</li>
 *   <li>状态归一化、枚举映射均可配置</li>
 * </ul>
 */
@Component
public class StructuredDataProvider {

    private final QaAssistantProperties properties;
    private final BusinessRulesConfig config;

    public StructuredDataProvider(QaAssistantProperties properties, BusinessRulesConfig config) {
        this.properties = properties;
        this.config = config;
    }

    /**
     * 根据数据源配置ID执行查询。
     *
     * @param configId 数据源配置ID (e.g., "person_stewardship")
     * @param entityIds 实体ID列表（如员工ID列表）
     * @param entityIdColumn 实体ID列名
     * @param maxRows 最大返回条数
     * @return ContextChunk 列表
     */
    public List<ContextChunk> query(String configId, Set<Integer> entityIds, String entityIdColumn, int maxRows) {
        Optional<StructuredQueryConfig> optConfig = findConfig(configId);
        if (optConfig.isEmpty()) {
            return List.of();
        }
        StructuredQueryConfig queryConfig = optConfig.get();
        if (entityIds == null || entityIds.isEmpty()) {
            return List.of();
        }

        int limit = Math.max(1, Math.min(maxRows, 128));

        try (Connection connection = openBusinessConnection()) {
            Map<String, String> companyNames = loadLookupTable(connection, queryConfig);
            List<QueryResultRow> rows = executeQuery(connection, queryConfig, entityIds, entityIdColumn, limit);
            return toContextChunks(rows, queryConfig, companyNames);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 根据配置ID获取结构化查询配置。
     */
    public Optional<StructuredQueryConfig> findConfig(String configId) {
        if (configId == null || configId.isBlank()) {
            return Optional.empty();
        }
        return config.getDataSources().getStructuredQueries().stream()
                .filter(c -> configId.equals(c.getId()))
                .findFirst();
    }

    private Connection openBusinessConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }

    private Map<String, String> loadLookupTable(Connection connection, StructuredQueryConfig config) {
        Map<String, String> map = new LinkedHashMap<>();
        // 查找 company 表作为 lookup
        for (String table : config.getSupplementalTables()) {
            if ("company".equalsIgnoreCase(table)) {
                String sql = "SELECT company_id, company_name FROM company WHERE deleteflag = 0";
                try (PreparedStatement ps = connection.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = String.valueOf(rs.getObject("company_id"));
                        String name = rs.getString("company_name");
                        if (name != null && !name.isBlank()) {
                            map.put(id, name.trim());
                        }
                    }
                } catch (SQLException ignored) {
                    // table structure may vary
                }
                break;
            }
        }
        return map;
    }

    private List<QueryResultRow> executeQuery(
            Connection connection,
            StructuredQueryConfig config,
            Set<Integer> entityIds,
            String entityIdColumn,
            int limit
    ) throws SQLException {
        List<QueryResultRow> result = new ArrayList<>();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(config.getEntityIdColumn());
        for (RoleColumnConfig rc : config.getRoleColumns()) {
            sql.append(", ").append(rc.getColumn());
        }
        // 添加其他必要字段
        sql.append(", company_id");
        if (config.getEnumMappings().containsKey("cert_type")) {
            sql.append(", certificate_type");
        }
        sql.append(", status");
        sql.append(" FROM ").append(config.getTable());
        sql.append(" WHERE ").append(config.getDeleteflagColumn()).append(" = 0");

        try (PreparedStatement ps = connection.prepareStatement(sql.toString());
             ResultSet rs = ps.executeQuery()) {

            while (rs.next() && result.size() < limit) {
                String recordId = String.valueOf(rs.getObject(config.getEntityIdColumn()));
                String companyId = String.valueOf(rs.getObject("company_id"));
                String companyName = ""; // 将在 toContextChunks 中补充

                for (RoleColumnConfig rc : config.getRoleColumns()) {
                    String idListRaw = rs.getString(rc.getColumn());
                    List<Integer> parsedIds = parseIdList(idListRaw);

                    for (int empId : parsedIds) {
                        if (!entityIds.contains(empId)) {
                            continue;
                        }
                        String statusLabel = normalizeStatus(rs.getObject("status"), config.getStatusNormalization());

                        result.add(new QueryResultRow(
                                recordId,
                                companyId,
                                rc.getLabel(),
                                statusLabel,
                                empId
                        ));
                    }
                }
            }
        }

        return result;
    }

    private List<ContextChunk> toContextChunks(
            List<QueryResultRow> rows,
            StructuredQueryConfig config,
            Map<String, String> companyNames
    ) {
        List<ContextChunk> chunks = new ArrayList<>();
        double rowScore = 28.0;
        String schemaId = "structured_query_v1";
        String source = "mysql-structured-" + config.getId();

        for (QueryResultRow row : rows) {
            String companyName = companyNames.getOrDefault(row.companyId(), "");
            String displayLabel = row.displayLabel(companyName);

            chunks.add(ContextChunk.ofCompany(
                    row.recordId(),
                    displayLabel,
                    config.getDescription(),
                    row.toEvidenceLine(),
                    rowScore,
                    source,
                    schemaId
            ));
        }

        return chunks;
    }

    private static List<Integer> parseIdList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) continue;
            try {
                int id = Integer.parseInt(item);
                if (id > 0) ids.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return ids;
    }

    private static String normalizeStatus(Object value, Map<String, String> normalization) {
        if (value == null) return "未知";
        String text = String.valueOf(value).trim();
        String normalized = normalization.get(text);
        return normalized != null ? normalized : text;
    }

    // 内部记录结构
    private record QueryResultRow(
            String recordId,
            String companyId,
            String roleLabel,
            String statusLabel,
            int employeeId
    ) {
        String displayLabel(String companyName) {
            if (companyName == null || companyName.isBlank()) {
                return "（公司名称未加载）";
            }
            return companyName;
        }

        String toEvidenceLine() {
            return String.format("%s | %s | 状态: %s",
                    roleLabel, recordId, statusLabel);
        }
    }
}
package com.qa.demo.qa.retrieval.personcert;



import com.qa.demo.knowledge.EvidenceSchemaRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.domain.CertificateSealEnumCatalog;

import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按员工唯一标识查询证照职责（运行时读业务库 tdcomp）。
 * <p>
 * 原则：{@code employee_id} / {@code company_id} / {@code certificate_management.id} 仅作关联键；
 * 枚举码在边界译为中文标签；证据 snippet 只含业务属性，不含类型码或 ID 解释。
 *
 * @deprecated 建议迁移到 {@link com.qa.demo.qa.config.StructuredDataProvider} 配置化查询，
 *             此实现保留作为过渡，后续删除。
 */
@Deprecated
@Service
public class PersonCertificateQueryService {

    private static final String SOURCE = "mysql-person-certificate";
    private static final String COMPANY_SOURCE = "mysql-company-certificate";
    private static final double ROW_SCORE = 28.0;

    private final QaAssistantProperties properties;
    private final CertificateSealEnumCatalog enumCatalog;
    private final EvidenceSchemaRegistry evidenceSchemas;

    public PersonCertificateQueryService(
            QaAssistantProperties properties,
            CertificateSealEnumCatalog enumCatalog,
            EvidenceSchemaRegistry evidenceSchemas
    ) {
        this.properties = properties;
        this.enumCatalog = enumCatalog;
        this.evidenceSchemas = evidenceSchemas;
    }

    public List<ContextChunk> retrieve(Integer personEmployeeId, String personDisplayName, int maxRows) {
        int limit = Math.max(1, Math.min(maxRows, 128));

        try (Connection connection = openBusinessConnection()) {
            Set<Integer> employeeIds = resolveEmployeeIds(connection, personEmployeeId, personDisplayName);
            if (employeeIds.isEmpty()) {
                return List.of();
            }
            Map<String, String> companyNames = loadCompanyNames(connection);
            List<PersonCertificateStewardship> rows = matchStewardships(connection, employeeIds, companyNames, limit);
            return toContextChunks(rows, SOURCE);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 按公司名批量查询登记证照（用于多轮追问「这些主体有哪些证照」）。
     */
    public List<ContextChunk> retrieveByCompanyNames(
            List<String> companyNames,
            boolean activeCompaniesOnly,
            int maxRows
    ) {
        if (companyNames == null || companyNames.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxRows, 256));
        try (Connection connection = openBusinessConnection()) {
            Map<String, String> allCompanyNames = loadCompanyNames(connection);
            Set<String> companyIds = resolveCompanyIds(connection, companyNames, activeCompaniesOnly);
            if (companyIds.isEmpty()) {
                return List.of();
            }
            List<PersonCertificateStewardship> rows = loadCertificatesForCompanies(
                    connection, companyIds, allCompanyNames, limit);
            return toContextChunks(rows, COMPANY_SOURCE);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Connection openBusinessConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }

    private Set<Integer> resolveEmployeeIds(Connection connection, Integer personEmployeeId, String personDisplayName) throws SQLException {
        java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
        if (personEmployeeId != null && personEmployeeId > 0) {
            ids.add(personEmployeeId);
            return ids;
        }
        if (personDisplayName == null || personDisplayName.isBlank()) {
            return ids;
        }
        return resolveEmployeeIdsByDisplayName(connection, personDisplayName.trim());
    }

    private Set<Integer> resolveEmployeeIdsByDisplayName(Connection connection, String canonicalName) throws SQLException {
        java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
        String sql = "SELECT id FROM employee WHERE deleteflag = 0 AND name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, canonicalName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("id"));
                }
            }
        }
        return ids;
    }

    private Map<String, String> loadCompanyNames(Connection connection) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
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
        }
        return map;
    }

    private List<PersonCertificateStewardship> matchStewardships(
            Connection connection,
            Set<Integer> employeeIds,
            Map<String, String> companyNames,
            int limit
    ) throws SQLException {
        List<PersonCertificateStewardship> result = new ArrayList<>();
        String sql = """
                SELECT id, company_id, certificate_type, status, valid_to,
                       supervisors, certification_keepers, executors
                FROM certificate_management
                WHERE deleteflag = 0
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next() && result.size() < limit) {
                String certRecordId = String.valueOf(rs.getObject("id"));
                String companyId = String.valueOf(rs.getObject("company_id"));
                String typeCode = rs.getString("certificate_type");
                String certTypeLabel = enumCatalog.resolveCertificateLabel(typeCode == null ? "" : typeCode);
                String statusLabel = normalizeStatus(rs.getObject("status"));
                String companyName = companyNames.getOrDefault(companyId, "");

                appendRoleMatches(result, employeeIds, certRecordId, companyId, companyName, certTypeLabel, statusLabel, "证照监管人", rs.getString("supervisors"));
                appendRoleMatches(result, employeeIds, certRecordId, companyId, companyName, certTypeLabel, statusLabel, "证照保管人", rs.getString("certification_keepers"));
                appendRoleMatches(result, employeeIds, certRecordId, companyId, companyName, certTypeLabel, statusLabel, "证照执行人", rs.getString("executors"));
            }
        }
        return result;
    }

    private void appendRoleMatches(
            List<PersonCertificateStewardship> result,
            Set<Integer> employeeIds,
            String certRecordId,
            String companyId,
            String companyName,
            String certTypeLabel,
            String statusLabel,
            String roleLabel,
            String idListRaw
    ) {
        for (int employeeId : parseIdList(idListRaw)) {
            if (!employeeIds.contains(employeeId)) {
                continue;
            }
            result.add(new PersonCertificateStewardship(
                    certTypeLabel,
                    companyName.isBlank() ? "（公司名称未加载）" : companyName,
                    roleLabel,
                    statusLabel,
                    certRecordId,
                    companyId,
                    String.valueOf(employeeId)
            ));
        }
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

    private static String normalizeStatus(Object value) {
        if (value == null) return "未知";
        String text = String.valueOf(value).trim();
        if ("0".equals(text) || "有效".equalsIgnoreCase(text)) return "有效";
        if ("1".equals(text) || "无效".equalsIgnoreCase(text) || "失效".equalsIgnoreCase(text)) return "失效";
        return text;
    }

    private Set<String> resolveCompanyIds(
            Connection connection,
            List<String> companyNames,
            boolean activeOnly
    ) throws SQLException {
        Set<String> ids = new java.util.LinkedHashSet<>();
        String statusClause = activeOnly ? " AND operating_status = 0" : "";
        String exactSql = """
                SELECT company_id FROM company
                WHERE deleteflag = 0%s AND company_name = ?
                LIMIT 1
                """.formatted(statusClause);
        String likeSql = """
                SELECT company_id FROM company
                WHERE deleteflag = 0%s AND company_name LIKE ?
                LIMIT 3
                """.formatted(statusClause);
        for (String raw : companyNames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            try (PreparedStatement ps = connection.prepareStatement(exactSql)) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(String.valueOf(rs.getObject("company_id")));
                    }
                }
            }
            if (ids.size() >= 64) {
                break;
            }
            try (PreparedStatement ps = connection.prepareStatement(likeSql)) {
                ps.setString(1, "%" + name + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(String.valueOf(rs.getObject("company_id")));
                    }
                }
            }
            if (ids.size() >= 64) {
                break;
            }
        }
        return ids;
    }

    private List<PersonCertificateStewardship> loadCertificatesForCompanies(
            Connection connection,
            Set<String> companyIds,
            Map<String, String> companyNames,
            int limit
    ) throws SQLException {
        if (companyIds.isEmpty()) {
            return List.of();
        }
        String placeholders = buildPlaceholders(companyIds.size());
        String sql = """
                SELECT id, company_id, certificate_type, status
                FROM certificate_management
                WHERE deleteflag = 0 AND company_id IN (%s)
                ORDER BY company_id, id
                """.formatted(placeholders);
        List<PersonCertificateStewardship> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            for (String companyId : companyIds) {
                ps.setString(idx++, companyId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < limit) {
                    String certRecordId = String.valueOf(rs.getObject("id"));
                    String companyId = String.valueOf(rs.getObject("company_id"));
                    String typeCode = rs.getString("certificate_type");
                    String certTypeLabel = enumCatalog.resolveCertificateLabel(typeCode == null ? "" : typeCode);
                    String statusLabel = normalizeStatus(rs.getObject("status"));
                    String companyName = companyNames.getOrDefault(companyId, "");
                    result.add(new PersonCertificateStewardship(
                            certTypeLabel,
                            companyName.isBlank() ? "（公司名称未加载）" : companyName,
                            "登记证照",
                            statusLabel,
                            certRecordId,
                            companyId,
                            ""
                    ));
                }
            }
        }
        return result;
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

    private List<ContextChunk> toContextChunks(List<PersonCertificateStewardship> rows, String source) {
        List<ContextChunk> chunks = new ArrayList<>();
        for (PersonCertificateStewardship row : rows) {
            chunks.add(ContextChunk.ofCompany(
                    row.anchorId(),
                    row.displayLabel(),
                    "人物证照",
                    row.toEvidenceLine(evidenceSchemas),
                    ROW_SCORE,
                    source,
                    PersonCertificateStewardship.SCHEMA_ID
            ));
        }
        return chunks;
    }
}
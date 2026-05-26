package com.qa.demo.qa.retrieval.personcert;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.domain.CertificateSealEnumCatalog;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按人物查询证照职责（运行时读业务库 tdcomp）。
 * <p>
 * 原则：employee_id / certificate_type 数字码仅作关联键；证据 snippet 只展示中文业务属性。
 */
@Service
public class PersonCertificateQueryService {

    private static final String SOURCE = "mysql-person-certificate";
    private static final double ROW_SCORE = 28.0;

    private final QaAssistantProperties properties;
    private final CertificateSealEnumCatalog enumCatalog;

    public PersonCertificateQueryService(
            QaAssistantProperties properties,
            CertificateSealEnumCatalog enumCatalog
    ) {
        this.properties = properties;
        this.enumCatalog = enumCatalog;
    }

    public List<ContextChunk> retrieve(String personName, int maxRows) {
        if (personName == null || personName.isBlank()) {
            return List.of();
        }
        String anchor = personName.trim();
        int limit = Math.max(1, Math.min(maxRows, 128));
        try (Connection connection = openBusinessConnection()) {
            Set<Integer> employeeIds = resolveEmployeeIds(connection, anchor);
            if (employeeIds.isEmpty()) {
                return List.of();
            }
            Map<String, String> companyNames = loadCompanyNames(connection);
            List<PersonCertificateStewardship> rows = matchStewardships(connection, employeeIds, companyNames, limit);
            return toContextChunks(rows);
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

    private Set<Integer> resolveEmployeeIds(Connection connection, String personName) throws SQLException {
        Set<Integer> ids = new LinkedHashSet<>();
        String sql = """
                SELECT id FROM employee
                WHERE deleteflag = 0 AND (name = ? OR name LIKE ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, personName);
            ps.setString(2, "%" + personName + "%");
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
        String sql = """
                SELECT company_id, company_name FROM company WHERE deleteflag = 0
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = String.valueOf(rs.getObject("company_id"));
                String name = rs.getString("company_name");
                if (name != null && !name.isBlank()) {
                    map.put(id, name.trim());
                }
            }
        } catch (SQLException ex) {
            // company 表结构因环境而异时，匹配阶段用 company_id 占位
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
                String certId = String.valueOf(rs.getObject("id"));
                String companyId = String.valueOf(rs.getObject("company_id"));
                String typeCode = rs.getString("certificate_type");
                String certTypeLabel = enumCatalog.resolveCertificateLabel(typeCode == null ? "" : typeCode);
                String statusLabel = normalizeStatus(rs.getObject("status"));
                String companyName = companyNames.getOrDefault(companyId, "公司#" + companyId);

                appendRoleMatches(
                        result, employeeIds, certId, companyId, companyName, typeCode, certTypeLabel,
                        statusLabel, "证照监管人", rs.getString("supervisors"));
                appendRoleMatches(
                        result, employeeIds, certId, companyId, companyName, typeCode, certTypeLabel,
                        statusLabel, "证照保管人", rs.getString("certification_keepers"));
                appendRoleMatches(
                        result, employeeIds, certId, companyId, companyName, typeCode, certTypeLabel,
                        statusLabel, "证照执行人", rs.getString("executors"));
            }
        }
        return result;
    }

    private void appendRoleMatches(
            List<PersonCertificateStewardship> result,
            Set<Integer> employeeIds,
            String certId,
            String companyId,
            String companyName,
            String typeCode,
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
                    companyName,
                    roleLabel,
                    statusLabel,
                    certId,
                    companyId,
                    String.valueOf(employeeId),
                    typeCode == null ? "" : typeCode
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
            if (item.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(item);
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // skip non-numeric tokens
            }
        }
        return ids;
    }

    private static String normalizeStatus(Object value) {
        if (value == null) {
            return "未知";
        }
        String text = String.valueOf(value).trim();
        if ("0".equals(text) || "有效".equalsIgnoreCase(text)) {
            return "有效";
        }
        if ("1".equals(text) || "无效".equalsIgnoreCase(text) || "失效".equalsIgnoreCase(text)) {
            return "失效";
        }
        return text;
    }

    private List<ContextChunk> toContextChunks(List<PersonCertificateStewardship> rows) {
        List<ContextChunk> chunks = new ArrayList<>();
        for (PersonCertificateStewardship row : rows) {
            chunks.add(new ContextChunk(
                    row.companyId(),
                    row.companyName(),
                    "人物证照",
                    row.toEvidenceLine(),
                    ROW_SCORE,
                    SOURCE
            ));
        }
        return chunks;
    }
}

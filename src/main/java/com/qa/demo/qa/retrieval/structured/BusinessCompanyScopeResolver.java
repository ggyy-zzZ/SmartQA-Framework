package com.qa.demo.qa.retrieval.structured;

import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 将公司名 hints 解析为 company_id（配置化经营状态过滤，避免硬编码 operating_status=0）。
 */
@Component
public class BusinessCompanyScopeResolver {

    private final QaAssistantProperties properties;
    private final BusinessRulesConfig businessRules;

    public BusinessCompanyScopeResolver(QaAssistantProperties properties, BusinessRulesConfig businessRules) {
        this.properties = properties;
        this.businessRules = businessRules;
    }

    public Set<String> resolveCompanyIds(List<String> companyNames, Optional<Boolean> activeCompaniesOnly) {
        if (companyNames == null || companyNames.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        try (Connection connection = openBusinessConnection()) {
            String statusClause = buildOperatingStatusClause(activeCompaniesOnly);
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
        } catch (SQLException ignored) {
            return Set.of();
        }
        return ids;
    }

    public Map<String, String> loadCompanyNames() {
        Map<String, String> map = new LinkedHashMap<>();
        try (Connection connection = openBusinessConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT company_id, company_name FROM company WHERE deleteflag = 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = String.valueOf(rs.getObject("company_id"));
                String name = rs.getString("company_name");
                if (name != null && !name.isBlank()) {
                    map.put(id, name.trim());
                }
            }
        } catch (SQLException ignored) {
            // empty
        }
        return map;
    }

    private String buildOperatingStatusClause(Optional<Boolean> activeCompaniesOnly) {
        if (activeCompaniesOnly.isEmpty()) {
            return "";
        }
        List<String> codes = businessRules.getCertificateRetrieval().getActiveCompanyOperatingStatusCodes();
        if (codes == null || codes.isEmpty()) {
            return "";
        }
        String inList = String.join(", ", codes.stream().map(c -> "'" + c.replace("'", "") + "'").toList());
        return activeCompaniesOnly.get()
                ? " AND operating_status IN (" + inList + ")"
                : " AND operating_status NOT IN (" + inList + ")";
    }

    private Connection openBusinessConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }
}

package com.qa.demo.qa.retrieval.sql;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * role/list 问句的规则 SQL（按 semantic-schema 角色列 JOIN employee），不依赖 LLM 生成 SQL。
 */
@Service
public class RoleListSqlQueryService {

    private static final Logger log = LoggerFactory.getLogger(RoleListSqlQueryService.class);

    private final QaAssistantProperties properties;

    public RoleListSqlQueryService(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public List<ContextChunk> tryRetrieve(IntentDecision intent, int limit) {
        if (!properties.isMysqlEnabled() || intent == null || !intent.hasPersonFocus()) {
            return List.of();
        }
        String personName = intent.personName() == null ? "" : intent.personName().trim();
        if (personName.isBlank()) {
            return List.of();
        }
        String roleColumn = resolveRoleColumn(intent.roleFocus());
        if (roleColumn == null) {
            return List.of();
        }
        int capped = Math.max(1, Math.min(limit, properties.getSqlQueryMaxRows()));
        String sql = "SELECT c.id AS company_id, c.company_name AS company_name, e.name AS legal_rep_name "
                + "FROM company c INNER JOIN employee e ON c." + roleColumn + " = e.id "
                + "WHERE c.deleteflag = 0 AND e.name = ? "
                + "ORDER BY c.company_name LIMIT " + capped;
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, personName);
            try (ResultSet rs = ps.executeQuery()) {
                List<ContextChunk> chunks = new ArrayList<>();
                int row = 0;
                while (rs.next() && row < capped) {
                    row++;
                    String companyId = safe(rs.getString("company_id"));
                    String companyName = safe(rs.getString("company_name"));
                    String repName = safe(rs.getString("legal_rep_name"));
                    if (companyId.isBlank()) {
                        companyId = "row-" + row;
                    }
                    if (companyName.isBlank()) {
                        companyName = companyId;
                    }
                    String snippet = "company_name=" + companyName
                            + "; legal_rep_name=" + repName
                            + "; role_column=" + roleColumn;
                    chunks.add(ContextChunk.ofCompany(
                            companyId,
                            companyName,
                            "structured_role_list",
                            snippet,
                            20.0 - row * 0.1,
                            "mysql-structured-role"
                    ));
                }
                return chunks;
            }
        } catch (Exception e) {
            log.warn("RoleListSqlQueryService failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String resolveRoleColumn(String roleFocus) {
        if (roleFocus == null || roleFocus.isBlank() || "any".equalsIgnoreCase(roleFocus)) {
            return "legal_rep_id";
        }
        return switch (roleFocus.trim().toLowerCase(Locale.ROOT)) {
            case "legal_rep" -> "legal_rep_id";
            case "director" -> "chairman_exec_director_id";
            case "supervisor" -> "company_supervisor_id";
            case "shareholder" -> null;
            default -> null;
        };
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

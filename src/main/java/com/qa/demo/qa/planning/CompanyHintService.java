package com.qa.demo.qa.planning;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 公司指称检测与澄清候选（MySQL 业务库），不依赖 Neo4j 检索。
 */
@Service
public class CompanyHintService {

    private final QuestionEntityExtractor entityExtractor;
    private final QaAssistantProperties properties;

    public CompanyHintService(QuestionEntityExtractor entityExtractor, QaAssistantProperties properties) {
        this.entityExtractor = entityExtractor;
        this.properties = properties;
    }

    public boolean hasExplicitCompanyHint(String question) {
        return !entityExtractor.extractCompanyHints(question).isEmpty();
    }

    public List<CompanyCandidate> suggestCompanyCandidates(String question, int limit) {
        int top = Math.max(1, Math.min(limit, 8));
        List<String> hints = entityExtractor.extractCompanyHints(question);
        if (!hints.isEmpty()) {
            return queryByHints(hints, top);
        }
        return queryTopCompanies(top);
    }

    private List<CompanyCandidate> queryByHints(List<String> hints, int top) {
        Set<String> seen = new LinkedHashSet<>();
        List<CompanyCandidate> out = new ArrayList<>();
        String sql = """
                SELECT company_id, company_name, operating_status
                FROM company
                WHERE deleteflag = 0 AND company_name LIKE ?
                ORDER BY company_name
                LIMIT ?
                """;
        try (Connection connection = openConnection()) {
            for (String hint : hints) {
                if (hint == null || hint.isBlank() || out.size() >= top) {
                    continue;
                }
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, "%" + hint.trim() + "%");
                    ps.setInt(2, top);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next() && out.size() < top) {
                            String id = String.valueOf(rs.getObject("company_id"));
                            if (seen.add(id)) {
                                out.add(new CompanyCandidate(
                                        id,
                                        rs.getString("company_name"),
                                        String.valueOf(rs.getObject("operating_status"))
                                ));
                            }
                        }
                    }
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return out;
    }

    private List<CompanyCandidate> queryTopCompanies(int top) {
        List<CompanyCandidate> out = new ArrayList<>();
        String sql = """
                SELECT company_id, company_name, operating_status
                FROM company
                WHERE deleteflag = 0 AND company_name IS NOT NULL AND company_name <> ''
                ORDER BY company_name
                LIMIT ?
                """;
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, top);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CompanyCandidate(
                            String.valueOf(rs.getObject("company_id")),
                            rs.getString("company_name"),
                            String.valueOf(rs.getObject("operating_status"))
                    ));
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return out;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }
}

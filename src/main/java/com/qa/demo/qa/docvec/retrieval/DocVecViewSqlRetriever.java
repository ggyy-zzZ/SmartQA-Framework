package com.qa.demo.qa.docvec.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.docvec.config.DocVecProperties;
import com.qa.demo.qa.docvec.routing.DocVecRouteDecision;
import com.qa.demo.qa.docvec.session.DocVecSessionSnapshot;
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
import java.util.stream.Collectors;

/**
 * 对 tdcomp 问答视图 / 证照表执行参数化 SQL（实验路径专用）。
 */
@Service
public class DocVecViewSqlRetriever {

    private static final Logger log = LoggerFactory.getLogger(DocVecViewSqlRetriever.class);

    private final QaAssistantProperties assistantProperties;
    private final DocVecProperties docVecProperties;

    public DocVecViewSqlRetriever(QaAssistantProperties assistantProperties, DocVecProperties docVecProperties) {
        this.assistantProperties = assistantProperties;
        this.docVecProperties = docVecProperties;
    }

    public List<ContextChunk> retrieve(DocVecRouteDecision route, DocVecSessionSnapshot session) {
        if (!docVecProperties.isSqlEnabled() || route == null || route.mode() != com.qa.demo.qa.docvec.routing.DocVecRetrievalMode.SQL) {
            return List.of();
        }
        return switch (route.queryType()) {
            case PERSON_ROLE_LIST -> queryPersonRoles(route.personName(), route.roleLabel());
            case PERSON_ROLE_REGION_FILTER -> queryPersonRolesInRegion(
                    route.personName(), route.roleLabel(), route.regionKeyword());
            case REGION_COMPANY_LIST -> queryRegionCompanies(route.regionKeyword());
            case COMPANY_COUNT -> queryCompanyCount(route.operatingStatusFilter());
            case CERTIFICATE_HOLDER_LIST -> queryCertificateHolders(route.certificateTypeId(), route.certificateTypeName());
            case PRIOR_LIST_REGION_FILTER -> queryPriorCompaniesInRegion(session, route.regionKeyword());
            default -> retrieveLegacySlots(route);
        };
    }

    private List<ContextChunk> retrieveLegacySlots(DocVecRouteDecision route) {
        if (route.countQuery()) {
            return queryCompanyCount(route.operatingStatusFilter());
        }
        if (route.personName() != null && !route.personName().isBlank()) {
            return queryPersonRoles(route.personName(), route.roleLabel());
        }
        if (route.regionKeyword() != null && !route.regionKeyword().isBlank()) {
            return queryRegionCompanies(route.regionKeyword());
        }
        return List.of();
    }

    private List<ContextChunk> queryPersonRoles(String personName, String roleLabel) {
        String role = roleLabel == null || roleLabel.isBlank() ? "法定代表人" : roleLabel;
        int limit = docVecProperties.getSqlMaxRows();
        String sql = """
                SELECT company_id, 公司名称, 经营状态, 角色
                FROM v_person_company_roles
                WHERE 人员姓名 = ? AND 角色 = ?
                ORDER BY 公司名称
                LIMIT %d
                """.formatted(limit);
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, personName);
            ps.setString(2, role);
            return mapCompanyRows(ps, "view_person_roles", personName + "/" + role);
        } catch (Exception e) {
            log.warn("person role sql failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ContextChunk> queryPersonRolesInRegion(String personName, String roleLabel, String regionKeyword) {
        String role = roleLabel == null || roleLabel.isBlank() ? "法定代表人" : roleLabel;
        String region = regionKeyword == null ? "" : regionKeyword.trim();
        if (personName == null || personName.isBlank() || region.isBlank()) {
            return List.of();
        }
        int limit = docVecProperties.getSqlMaxRows();
        String sql = """
                SELECT r.company_id, r.公司名称, p.注册地区, r.经营状态, r.角色
                FROM v_person_company_roles r
                INNER JOIN v_company_profile p ON r.company_id = p.company_id
                WHERE r.人员姓名 = ? AND r.角色 = ? AND p.注册地区 LIKE ?
                ORDER BY r.公司名称
                LIMIT %d
                """.formatted(limit);
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, personName);
            ps.setString(2, role);
            ps.setString(3, "%" + region + "%");
            return mapProfileJoinRows(ps, "view_person_role_region", personName + "/" + role + "/" + region);
        } catch (Exception e) {
            log.warn("person role region sql failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ContextChunk> queryRegionCompanies(String regionKeyword) {
        int limit = docVecProperties.getSqlMaxRows();
        String sql = """
                SELECT company_id, 公司名称, 注册地区, 经营状态, 法定代表人
                FROM v_company_profile
                WHERE 注册地区 LIKE ?
                ORDER BY 公司名称
                LIMIT %d
                """.formatted(limit);
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + regionKeyword + "%");
            return mapProfileRows(ps, "view_region_list", regionKeyword);
        } catch (Exception e) {
            log.warn("region list sql failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ContextChunk> queryPriorCompaniesInRegion(DocVecSessionSnapshot session, String regionKeyword) {
        if (session == null || regionKeyword == null || regionKeyword.isBlank()) {
            return List.of();
        }
        List<String> ids = session.priorCompanyIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        int limit = docVecProperties.getSqlMaxRows();
        List<String> useIds = ids.size() > limit ? ids.subList(0, limit) : ids;
        String placeholders = useIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT company_id, 公司名称, 注册地区, 经营状态, 法定代表人
                FROM v_company_profile
                WHERE 注册地区 LIKE ? AND company_id IN (%s)
                ORDER BY 公司名称
                LIMIT %d
                """.formatted(placeholders, limit);
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + regionKeyword.trim() + "%");
            for (int i = 0; i < useIds.size(); i++) {
                ps.setString(i + 2, useIds.get(i));
            }
            return mapProfileRows(ps, "view_prior_region_filter", regionKeyword);
        } catch (Exception e) {
            log.warn("prior region filter sql failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ContextChunk> queryCertificateHolders(int certificateTypeId, String certificateTypeName) {
        if (certificateTypeId <= 0) {
            log.warn("certificate list missing type id for {}", certificateTypeName);
            return List.of();
        }
        int limit = docVecProperties.getSqlMaxRows();
        String sql = """
                SELECT c.id AS company_id, c.company_name AS 公司名称, c.reg_province_region AS 注册地区,
                       CASE c.operating_status
                           WHEN 0 THEN '设立中' WHEN 1 THEN '存续' WHEN 2 THEN '迁出'
                           WHEN 3 THEN '注销' WHEN 4 THEN '吊销' WHEN 5 THEN '停业'
                           ELSE CONCAT('未知状态(', c.operating_status, ')') END AS 经营状态,
                       ? AS 证照类型
                FROM certificate_management cm
                INNER JOIN company c ON c.id = cm.company_id AND c.deleteflag = 0
                WHERE cm.deleteflag = 0 AND cm.certificate_type = ?
                ORDER BY c.company_name
                LIMIT %d
                """.formatted(limit);
        String label = certificateTypeName == null ? "" : certificateTypeName.trim();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, label);
            ps.setInt(2, certificateTypeId);
            return mapCertificateRows(ps, "view_certificate_holders", label);
        } catch (Exception e) {
            log.warn("certificate holder sql failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ContextChunk> queryCompanyCount(String operatingStatus) {
        String sql;
        if (operatingStatus == null || operatingStatus.isBlank()) {
            sql = "SELECT COUNT(*) AS cnt FROM v_company_profile";
            return mapCountResult(sql, null, "全部");
        }
        sql = "SELECT COUNT(*) AS cnt FROM v_company_profile WHERE 经营状态 = ?";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, operatingStatus);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return List.of();
                }
                int cnt = rs.getInt("cnt");
                String snippet = "统计结果：经营状态=" + operatingStatus + " 的公司共 " + cnt + " 家";
                return List.of(systemChunk(snippet, "sql_aggregate_v1"));
            }
        } catch (Exception e) {
            log.warn("count sql failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ContextChunk> mapCountResult(String sql, String param, String label) {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return List.of();
                }
                int cnt = rs.getInt("cnt");
                String snippet = "统计结果：" + label + " 公司共 " + cnt + " 家";
                return List.of(systemChunk(snippet, "sql_aggregate_v1"));
            }
        } catch (Exception e) {
            log.warn("count sql failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ContextChunk> mapCompanyRows(PreparedStatement ps, String sourceTag, String queryLabel)
            throws Exception {
        List<ContextChunk> chunks = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            int row = 0;
            while (rs.next()) {
                row++;
                String companyId = safe(rs.getString("company_id"));
                String companyName = safe(rs.getString("公司名称"));
                String status = safe(rs.getString("经营状态"));
                String role = safe(rs.getString("角色"));
                String snippet = "公司名称=" + companyName + "; 经营状态=" + status + "; 角色=" + role;
                chunks.add(companyChunk(companyId, companyName, snippet, sourceTag, "sql_role_list_v1", row));
            }
        }
        if (chunks.isEmpty()) {
            log.info("view sql empty for {}", queryLabel);
        }
        return chunks;
    }

    private List<ContextChunk> mapProfileJoinRows(PreparedStatement ps, String sourceTag, String queryLabel)
            throws Exception {
        List<ContextChunk> chunks = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            int row = 0;
            while (rs.next()) {
                row++;
                String companyId = safe(rs.getString("company_id"));
                String companyName = safe(rs.getString("公司名称"));
                String region = safe(rs.getString("注册地区"));
                String status = safe(rs.getString("经营状态"));
                String role = safe(rs.getString("角色"));
                String snippet = "公司名称=" + companyName
                        + "; 注册地区=" + region
                        + "; 经营状态=" + status
                        + "; 角色=" + role;
                chunks.add(companyChunk(companyId, companyName, snippet, sourceTag, "sql_role_region_v1", row));
            }
        }
        if (chunks.isEmpty()) {
            log.info("view sql empty for {}", queryLabel);
        }
        return chunks;
    }

    private List<ContextChunk> mapProfileRows(PreparedStatement ps, String sourceTag, String queryLabel)
            throws Exception {
        List<ContextChunk> chunks = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            int row = 0;
            while (rs.next()) {
                row++;
                String companyId = safe(rs.getString("company_id"));
                String companyName = safe(rs.getString("公司名称"));
                String region = safe(rs.getString("注册地区"));
                String status = safe(rs.getString("经营状态"));
                String legalRep = safe(rs.getString("法定代表人"));
                String snippet = "公司名称=" + companyName
                        + "; 注册地区=" + region
                        + "; 经营状态=" + status
                        + "; 法定代表人=" + legalRep;
                chunks.add(companyChunk(companyId, companyName, snippet, sourceTag, "sql_region_list_v1", row));
            }
        }
        if (chunks.isEmpty()) {
            log.info("view sql empty for region {}", queryLabel);
        }
        return chunks;
    }

    private List<ContextChunk> mapCertificateRows(PreparedStatement ps, String sourceTag, String queryLabel)
            throws Exception {
        List<ContextChunk> chunks = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            int row = 0;
            while (rs.next()) {
                row++;
                String companyId = safe(rs.getString("company_id"));
                String companyName = safe(rs.getString("公司名称"));
                String region = safe(rs.getString("注册地区"));
                String status = safe(rs.getString("经营状态"));
                String certType = safe(rs.getString("证照类型"));
                String snippet = "公司名称=" + companyName
                        + "; 证照类型=" + certType
                        + "; 注册地区=" + region
                        + "; 经营状态=" + status;
                chunks.add(companyChunk(companyId, companyName, snippet, sourceTag, "sql_certificate_list_v1", row));
            }
        }
        if (chunks.isEmpty()) {
            log.info("certificate sql empty for {}", queryLabel);
        }
        return chunks;
    }

    private static ContextChunk companyChunk(
            String companyId,
            String companyName,
            String snippet,
            String sourceTag,
            String schema,
            int row
    ) {
        return new ContextChunk(
                companyId.isBlank() ? "row-" + row : companyId,
                companyName,
                ContextChunk.KIND_COMPANY,
                "视图SQL",
                snippet,
                20.0 - row * 0.01,
                sourceTag,
                schema
        );
    }

    private static ContextChunk systemChunk(String snippet, String schema) {
        return new ContextChunk(
                "count",
                "公司数量统计",
                ContextChunk.KIND_SYSTEM,
                "聚合统计",
                snippet,
                20.0,
                "docvec-view-sql",
                schema
        );
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                assistantProperties.getBusinessMysqlUrl(),
                assistantProperties.getBusinessMysqlUsername(),
                assistantProperties.getBusinessMysqlPassword()
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

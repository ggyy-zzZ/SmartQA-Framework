package com.qa.demo.qa.retrieval;

import com.qa.demo.knowledge.EvidenceSchemaRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.domain.PersonNameParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * 员工基础信息服务：启动时全量加载员工 id/name/another_name 构建查询索引，
 * 供问答时快速解析人名/花名到具体员工。
 */
@Service
public class EmployeeBaseKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeBaseKnowledgeService.class);

    private final QaAssistantProperties properties;
    private final EvidenceSchemaRegistry evidenceSchemas;

    /**
     * name → employee id
     */
    private final Map<String, Integer> nameToId = new HashMap<>();

    /**
     * another_name (花名) → employee id
     */
    private final Map<String, Integer> anotherNameToId = new HashMap<>();

    /**
     * employee id → full record (name, another_name)
     */
    private final Map<Integer, EmployeeRecord> idToRecord = new HashMap<>();

    public EmployeeBaseKnowledgeService(QaAssistantProperties properties, EvidenceSchemaRegistry evidenceSchemas) {
        this.properties = properties;
        this.evidenceSchemas = evidenceSchemas;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 重新从业务库加载员工索引（全量同步后可调用）。 */
    public synchronized void reload() {
        load();
    }

    /**
     * 初始化加载员工数据（启动时调用）。
     */
    private synchronized void load() {
        nameToId.clear();
        anotherNameToId.clear();
        idToRecord.clear();

        String sql = buildEmployeeSelectSql();
        int limit = properties.getEmployeeBaseLimit();
        if (limit > 0) {
            sql += " LIMIT " + limit;
        }

        try (Connection conn = openBusinessConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            int count = 0;
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                String anotherName = null;
                try {
                    anotherName = rs.getString("another_name");
                } catch (Exception ignored) {
                    // 业务库无花名列时仅索引姓名
                }

                // 索引 name → id
                if (name != null && !name.isBlank()) {
                    String key = name.trim().toLowerCase();
                    nameToId.put(key, id);
                }

                // 索引 another_name → id
                if (anotherName != null && !anotherName.isBlank()) {
                    String key = anotherName.trim().toLowerCase();
                    anotherNameToId.put(key, id);
                }

                // 记录 id → 基本信息
                idToRecord.put(id, new EmployeeRecord(id, name, anotherName));
                count++;
            }
            log.info("EmployeeBaseKnowledgeService loaded {} records (name: {}, another_name: {})",
                    count, nameToId.size(), anotherNameToId.size());
        } catch (Exception e) {
            log.error("Failed to load employee base knowledge: {}", e.getMessage());
        }
    }

    /**
     * 根据人名（姓名或花名）解析到 employee id。
     * 先查花名，再查姓名。
     */
    public Integer resolveToEmployeeId(String personName) {
        if (personName == null || personName.isBlank()) {
            return null;
        }
        String key = personName.trim().toLowerCase();

        // 先查花名
        Integer id = anotherNameToId.get(key);
        if (id != null) {
            return id;
        }

        // 再查姓名
        return nameToId.get(key);
    }

    /**
     * 将敬称/模糊指称解析为 employee 表中的规范姓名；精确命中或唯一前缀匹配时返回全名，否则返回原串。
     */
    public String resolveCanonicalName(String personHint) {
        if (personHint == null || personHint.isBlank()) {
            return "";
        }
        String trimmed = personHint.trim();
        Integer exactId = resolveToEmployeeId(trimmed);
        if (exactId != null) {
            EmployeeRecord record = getEmployeeById(exactId);
            if (record != null && record.name() != null && !record.name().isBlank()) {
                return record.name().trim();
            }
        }
        if (!PersonNameParser.hasHonorificSuffix(trimmed)) {
            return trimmed;
        }
        String core = PersonNameParser.stripHonorific(trimmed);
        if (core.isBlank() || core.equals(trimmed)) {
            return trimmed;
        }
        List<String> candidates = new ArrayList<>();
        for (EmployeeRecord record : idToRecord.values()) {
            String name = record.name();
            if (name != null && name.startsWith(core)) {
                candidates.add(name.trim());
            }
        }
        candidates = candidates.stream().distinct().sorted().toList();
        if (candidates.isEmpty()) {
            return trimmed;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        List<String> fullNames = candidates.stream().filter(n -> n.length() > core.length()).toList();
        if (fullNames.size() == 1) {
            return fullNames.get(0);
        }
        return trimmed;
    }

    /**
     * 根据 employee id 获取完整记录。
     */
    public EmployeeRecord getEmployeeById(int id) {
        return idToRecord.get(id);
    }

    /**
     * 获取所有员工记录。
     */
    public Collection<EmployeeRecord> getAllEmployees() {
        return idToRecord.values();
    }

    /**
     * 获取已加载的员工数量。
     */
    public int size() {
        return idToRecord.size();
    }

    private String buildEmployeeSelectSql() {
        if (hasEmployeeColumn("another_name")) {
            return "SELECT id, name, another_name FROM employee";
        }
        return "SELECT id, name FROM employee";
    }

    private boolean hasEmployeeColumn(String columnName) {
        String sql = """
                SELECT 1 FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND COLUMN_NAME = ?
                LIMIT 1
                """;
        try (Connection conn = openBusinessConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private Connection openBusinessConnection() throws Exception {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }

  /**
     * 面向生成的员工身份证据（字段名由 evidence schema 定义，不含内部 ID）。
     */
    public String formatIdentityEvidence(EmployeeRecord record) {
        if (record == null) {
            return "";
        }
        return evidenceSchemas.formatEmployeeIdentity(record.name(), record.anotherName());
    }

    public record EmployeeRecord(int id, String name, String anotherName) {
    }
}
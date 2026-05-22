package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
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

    public EmployeeBaseKnowledgeService(QaAssistantProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        load();
    }

    /**
     * 初始化加载员工数据（启动时调用）。
     */
    public synchronized void load() {
        nameToId.clear();
        anotherNameToId.clear();
        idToRecord.clear();

        String sql = "SELECT id, name, another_name FROM employee";
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
                String anotherName = rs.getString("another_name");

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

    private Connection openBusinessConnection() throws Exception {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }

    public record EmployeeRecord(int id, String name, String anotherName) {
    }
}
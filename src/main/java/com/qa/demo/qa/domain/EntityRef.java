package com.qa.demo.qa.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * 多轮会话中的实体引用：存储检索到的结构化实体，供后续轮次使用。
 * <p>
 * 例如首轮检索到"戴科彬担任法人的26家公司"，第二轮可以以这26家为范围检索证照。
 *
 * @param id       实体ID（如公司ID）
 * @param name     实体名称（如公司名）
 * @param type     实体类型：company | person | certificate | 其他
 * @param status   状态（如存续、吊销）
 * @param metadata 其他元数据（如角色类型、法律代表人等）
 */
public record EntityRef(
        String id,
        String name,
        String type,
        String status,
        Map<String, String> metadata
) {
    public static final String TYPE_COMPANY = "company";
    public static final String TYPE_PERSON = "person";
    public static final String TYPE_CERTIFICATE = "certificate";

    public EntityRef(String id, String name, String type) {
        this(id, name, type, null, new HashMap<>());
    }

    public EntityRef(String id, String name, String type, String status) {
        this(id, name, type, status, new HashMap<>());
    }

    public EntityRef with(String key, String value) {
        Map<String, String> m = new HashMap<>(metadata);
        m.put(key, value);
        return new EntityRef(id, name, type, status, m);
    }

    public String get(String key) {
        return metadata.get(key);
    }

    public static EntityRef company(String id, String name) {
        return new EntityRef(id, name, TYPE_COMPANY);
    }

    public static EntityRef company(String id, String name, String status) {
        return new EntityRef(id, name, TYPE_COMPANY, status);
    }

    public static EntityRef person(String id, String name) {
        return new EntityRef(id, name, TYPE_PERSON);
    }

    public static EntityRef certificate(String id, String name) {
        return new EntityRef(id, name, TYPE_CERTIFICATE);
    }
}
package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.store.EnumCatalogRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 将 tdcomp 枚举码解析为中文标签（classpath:qa/enterprise-enums.json，可被 assistant 库枚举覆盖）。
 */
@Service
public class EnterpriseEnumLabelService {

    private final ObjectMapper objectMapper;
    private final EnumCatalogRepository enumCatalogRepository;
    private final QaAssistantProperties properties;
    private volatile Map<String, Map<String, String>> classpathDicts = Map.of();

    public EnterpriseEnumLabelService(
            ObjectMapper objectMapper,
            EnumCatalogRepository enumCatalogRepository,
            QaAssistantProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.enumCatalogRepository = enumCatalogRepository;
        this.properties = properties;
    }

    @PostConstruct
    void loadClasspathCatalog() {
        try (InputStream in = new ClassPathResource("qa/enterprise-enums.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                if (entry.getValue().isObject()) {
                    loaded.put(entry.getKey(), readDict(entry.getValue()));
                }
            });
            classpathDicts = Map.copyOf(loaded);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load qa/enterprise-enums.json", e);
        }
    }

    /**
     * @param dictCode 与 enterprise-enums.json 顶层键一致，如 operatingStatus、mainType
     * @param raw      库内原始码；空则返回空串
     */
    public String label(String dictCode, String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String key = raw.trim();
        Map<String, String> dict = resolveDict(dictCode);
        if (dict.isEmpty()) {
            return key;
        }
        String hit = dict.get(key);
        if (hit != null && !hit.isBlank()) {
            return hit;
        }
        hit = dict.get(key.toLowerCase(Locale.ROOT));
        if (hit != null && !hit.isBlank()) {
            return hit;
        }
        hit = dict.get("code_" + key);
        if (hit != null && !hit.isBlank()) {
            return hit;
        }
        return key;
    }

    private Map<String, String> resolveDict(String dictCode) {
        if (dictCode == null || dictCode.isBlank()) {
            return Map.of();
        }
        String scope = properties.getConfigScope();
        if (enumCatalogRepository.hasDict(scope, dictCode)) {
            return enumCatalogRepository.entryMap(scope, dictCode);
        }
        return classpathDicts.getOrDefault(dictCode, Map.of());
    }

    private static Map<String, String> readDict(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry ->
                map.put(entry.getKey(), entry.getValue().asText("").trim())
        );
        return map;
    }
}

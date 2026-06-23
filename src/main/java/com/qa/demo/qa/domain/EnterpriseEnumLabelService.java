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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** 返回枚举字典全部条目，供语义 Schema 摘要等场景使用。 */
    public Map<String, String> dictEntries(String dictCode) {
        return Map.copyOf(resolveDict(dictCode));
    }

    /**
     * 问句包含某枚举中文标签时，返回对应码值列表（可多码同标签）。
     */
    public List<String> codesMatchingQuestionLabels(String dictCode, String question) {
        if (dictCode == null || dictCode.isBlank() || question == null || question.isBlank()) {
            return List.of();
        }
        Map<String, String> dict = resolveDict(dictCode);
        if (dict.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        dict.forEach((code, label) -> {
            if (label == null || label.isBlank()) {
                return;
            }
            if (question.contains(label.trim())) {
                codes.add(code.trim());
            }
        });
        return List.copyOf(codes);
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

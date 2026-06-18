package com.qa.demo.qa.retrieval.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.knowledge.EvidenceSchemaRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.store.EnumCatalogRepository;
import com.qa.demo.qa.core.ContextChunk;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按检索目录配置拉取 catalog 类证据（枚举标签等），通用 retriever 实现。
 */
@Component
public class CatalogEvidenceRetriever {

    private static final double CATALOG_SCORE = 32.0;

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final EvidenceSchemaRegistry evidenceSchemas;
    private final EnumCatalogRepository enumCatalogRepository;
    private final QaAssistantProperties properties;

    public CatalogEvidenceRetriever(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            EvidenceSchemaRegistry evidenceSchemas,
            EnumCatalogRepository enumCatalogRepository,
            QaAssistantProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.evidenceSchemas = evidenceSchemas;
        this.enumCatalogRepository = enumCatalogRepository;
        this.properties = properties;
    }

    public List<ContextChunk> retrieve(List<RetrievalCatalogConfig.DimensionDef> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return List.of();
        }
        List<ContextChunk> chunks = new ArrayList<>();
        for (RetrievalCatalogConfig.DimensionDef dim : dimensions) {
            if (dim == null || dim.getRetriever() == null) {
                continue;
            }
            String type = dim.getRetriever().getType();
            if (type == null || type.isBlank()) {
                continue;
            }
            if ("enum_labels".equalsIgnoreCase(type.trim())) {
                chunks.addAll(retrieveEnumLabels(dim));
            }
        }
        return chunks;
    }

    private List<ContextChunk> retrieveEnumLabels(RetrievalCatalogConfig.DimensionDef dim) {
        RetrievalCatalogConfig.RetrieverDef retriever = dim.getRetriever();
        String resourcePath = retriever.getResource();
        String jsonField = retriever.getJsonField();
        if (resourcePath == null || resourcePath.isBlank() || jsonField == null || jsonField.isBlank()) {
            return List.of();
        }
        try {
            Set<String> uniqueLabels = new LinkedHashSet<>();
            String scope = properties.getConfigScope();
            if (enumCatalogRepository.hasDict(scope, jsonField)) {
                uniqueLabels.addAll(enumCatalogRepository.uniqueLabels(scope, jsonField));
            } else {
                Resource resource = resourceLoader.getResource(resourcePath);
                if (!resource.exists()) {
                    resource = new ClassPathResource(resourcePath.replace("classpath:", ""));
                }
                try (InputStream in = resource.getInputStream()) {
                    JsonNode root = objectMapper.readTree(in);
                    JsonNode fieldNode = root.path(jsonField);
                    if (fieldNode.isMissingNode() || fieldNode.isNull()) {
                        return List.of();
                    }
                    collectEnumLabels(fieldNode, uniqueLabels);
                }
            }
            if (!uniqueLabels.isEmpty()) {
                String schemaId = dim.getEvidenceSchema() == null ? "catalog_v1" : dim.getEvidenceSchema();
                String source = dim.getSource() == null ? "catalog-enum" : dim.getSource();
                String anchorId = retriever.getAnchorId() == null ? dim.getDimensionId() : retriever.getAnchorId();
                String displayLabel = retriever.getDisplayLabel() == null ? dim.getDimensionId() : retriever.getDisplayLabel();
                String entryType = displayLabel;
                List<ContextChunk> chunks = new ArrayList<>();
                for (String label : uniqueLabels) {
                    String entryAnchor = anchorId + "#" + label;
                    String snippet = formatCatalogSnippet(schemaId, entryType, label);
                    chunks.add(new ContextChunk(
                            entryAnchor,
                            label,
                            ContextChunk.KIND_SYSTEM,
                            "entryName",
                            snippet,
                            CATALOG_SCORE,
                            source,
                            schemaId
                    ));
                }
                return chunks;
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.of();
    }

    /** 支持 code→label 平铺对象，以及 operatingStatuses 式分组数组。 */
    private static void collectEnumLabels(JsonNode fieldNode, Set<String> uniqueLabels) {
        if (fieldNode == null || fieldNode.isNull()) {
            return;
        }
        if (fieldNode.isArray()) {
            fieldNode.forEach(item -> {
                String label = item.asText("").trim();
                if (!label.isBlank()) {
                    uniqueLabels.add(label);
                }
            });
            return;
        }
        if (!fieldNode.isObject()) {
            return;
        }
        fieldNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            if (value.isArray()) {
                value.forEach(item -> {
                    String label = item.asText("").trim();
                    if (!label.isBlank()) {
                        uniqueLabels.add(label);
                    }
                });
            } else {
                String label = value.asText("").trim();
                if (!label.isBlank()) {
                    uniqueLabels.add(label);
                }
            }
        });
    }

    private String formatCatalogSnippet(String schemaId, String entryType, String entryName) {
        Map<String, String> values = Map.of(
                "entryType", entryType,
                "entryName", entryName
        );
        String formatted = evidenceSchemas.formatSnippet(schemaId, values);
        if (formatted != null && !formatted.isBlank()) {
            return formatted;
        }
        return "条目类型=" + entryType + "; 名称=" + entryName;
    }
}

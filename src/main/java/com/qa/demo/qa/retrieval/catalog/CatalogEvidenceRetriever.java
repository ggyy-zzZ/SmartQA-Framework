package com.qa.demo.qa.retrieval.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.knowledge.EvidenceSchemaRegistry;
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

    public CatalogEvidenceRetriever(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            EvidenceSchemaRegistry evidenceSchemas
    ) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.evidenceSchemas = evidenceSchemas;
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
            Resource resource = resourceLoader.getResource(resourcePath);
            if (!resource.exists()) {
                resource = new ClassPathResource(resourcePath.replace("classpath:", ""));
            }
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                JsonNode fieldNode = root.path(jsonField);
                if (!fieldNode.isObject()) {
                    return List.of();
                }
                Set<String> uniqueLabels = new LinkedHashSet<>();
                fieldNode.fields().forEachRemaining(entry -> {
                    String label = entry.getValue().asText("").trim();
                    if (!label.isBlank()) {
                        uniqueLabels.add(label);
                    }
                });
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

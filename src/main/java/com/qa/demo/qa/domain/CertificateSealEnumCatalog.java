package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 证照类型、印章类型枚举与中文标签（classpath:qa/certificate-seal-enums.json），
 * 与业务库枚举及 {@code scripts/enterprise_pipeline/schema_field_maps.py} 对齐。
 */
public class CertificateSealEnumCatalog {

    private static final Pattern COLON_SUFFIX = Pattern.compile("^([^:/]+)(:.*)$");
    private static final Pattern PAREN_SUFFIX = Pattern.compile("^([^:(/]+)\\(([^)]*)\\)$");

    private final Map<String, String> certificateLabels;
    private final Map<String, String> sealLabels;

    public CertificateSealEnumCatalog(Map<String, String> certificateLabels, Map<String, String> sealLabels) {
        this.certificateLabels = Map.copyOf(certificateLabels);
        this.sealLabels = Map.copyOf(sealLabels);
    }

    public static CertificateSealEnumCatalog loadDefault(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("qa/certificate-seal-enums.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            return new CertificateSealEnumCatalog(
                    readMap(root.path("certificateTypes")),
                    readMap(root.path("sealTypes"))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load qa/certificate-seal-enums.json", ex);
        }
    }

    private static Map<String, String> readMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return map;
        }
        node.fields().forEachRemaining(entry ->
                map.put(entry.getKey(), entry.getValue().asText(""))
        );
        return map;
    }

    public String resolveCertificateLabel(String raw) {
        return resolve(raw, certificateLabels);
    }

    public String resolveSealLabel(String raw) {
        return resolve(raw, sealLabels);
    }

    /**
     * 格式化图谱 collect 出的证照/印章列表字符串，将类型码替换为中文标签。
     */
    public String formatCertificateListForSnippet(String rawList) {
        return formatListTokens(rawList, certificateLabels);
    }

    public String formatSealListForSnippet(String rawList) {
        return formatListTokens(rawList, sealLabels);
    }

    private static String formatListTokens(String rawList, Map<String, String> labels) {
        if (rawList == null || rawList.isBlank() || "[]".equals(rawList.trim())) {
            return rawList == null ? "" : rawList;
        }
        String body = rawList.trim();
        if (!body.startsWith("[")) {
            return formatToken(body, labels);
        }
        String inner = body.substring(1, body.length() - 1).trim();
        if (inner.isEmpty()) {
            return "[]";
        }
        String[] tokens = inner.split(",\\s*");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(formatToken(tokens[i].trim(), labels));
        }
        return sb.append("]").toString();
    }

    private static String formatToken(String token, Map<String, String> labels) {
        if (token.isEmpty()) {
            return token;
        }
        var paren = PAREN_SUFFIX.matcher(token);
        if (paren.matches()) {
            return resolve(paren.group(1), labels) + "(" + paren.group(2) + ")";
        }
        var colon = COLON_SUFFIX.matcher(token);
        if (colon.matches()) {
            return resolve(colon.group(1), labels) + colon.group(2);
        }
        return resolve(token, labels);
    }

    private static String resolve(String raw, Map<String, String> labels) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        String direct = labels.get(text);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        direct = labels.get(lower);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        return text;
    }
}

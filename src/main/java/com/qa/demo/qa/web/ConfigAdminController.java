package com.qa.demo.qa.web;

import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.config.store.ConfigBundleRepository;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小配置发布 API：写入 qa_config_bundle 并刷新内存缓存。
 */
@RestController
@RequestMapping("/qa/admin/config")
public class ConfigAdminController {

    private final QaAssistantProperties properties;
    private final ConfigBundleRepository configBundleRepository;
    private final AssistantConfigJsonLoader configJsonLoader;

    public ConfigAdminController(
            QaAssistantProperties properties,
            ConfigBundleRepository configBundleRepository,
            AssistantConfigJsonLoader configJsonLoader
    ) {
        this.properties = properties;
        this.configBundleRepository = configBundleRepository;
        this.configJsonLoader = configJsonLoader;
    }

    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publish(@RequestBody PublishRequest request) {
        if (request == null || request.configKey() == null || request.configKey().isBlank()
                || request.contentJson() == null || request.contentJson().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "configKey and contentJson required"));
        }
        String scope = request.scope() == null || request.scope().isBlank()
                ? properties.getConfigScope() : request.scope().trim();
        try {
            String hash = sha256(request.contentJson());
            configBundleRepository.upsertActive(scope, request.configKey().trim(), request.contentJson(), hash);
            configJsonLoader.clearCache();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("scope", scope);
            body.put("configKey", request.configKey());
            body.put("contentHash", hash);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/refresh-cache")
    public ResponseEntity<Map<String, Object>> refreshCache() {
        configJsonLoader.clearCache();
        return ResponseEntity.ok(Map.of("ok", true, "message", "config cache cleared"));
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public record PublishRequest(String scope, String configKey, String contentJson) {
    }
}

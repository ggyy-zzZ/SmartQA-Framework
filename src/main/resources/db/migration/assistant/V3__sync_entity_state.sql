-- EKSP 实体级同步状态（本地验证 MVP Sprint 1）
-- 与表级 sync_tracking 互补：按 domain + entity_type + entity_id 追踪 upsert 水位与内容 hash

CREATE TABLE IF NOT EXISTS `sync_entity_state` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `domain` VARCHAR(64) NOT NULL,
  `entity_type` VARCHAR(64) NOT NULL,
  `entity_id` VARCHAR(128) NOT NULL,
  `content_hash` VARCHAR(64) DEFAULT NULL,
  `last_sync_batch_id` VARCHAR(64) DEFAULT NULL,
  `last_synced_at` TIMESTAMP NULL DEFAULT NULL,
  `sync_status` VARCHAR(32) NOT NULL DEFAULT 'active',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_domain_entity` (`domain`, `entity_type`, `entity_id`),
  KEY `idx_domain_status_synced` (`domain`, `sync_status`, `last_synced_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

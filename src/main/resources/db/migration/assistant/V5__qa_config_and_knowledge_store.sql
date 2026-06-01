-- 知识配置与镜像入 assistant 库（评审见 docs/knowledge-mysql-migration-plan.md）
-- scope 区分 enterprise / crm 等；配置整包 JSON；枚举关系表；快照与文档 chunk；审计事件

CREATE TABLE IF NOT EXISTS `qa_config_bundle` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `scope` VARCHAR(64) NOT NULL DEFAULT 'enterprise',
  `config_key` VARCHAR(128) NOT NULL,
  `version` INT NOT NULL DEFAULT 1,
  `content_json` LONGTEXT NOT NULL,
  `content_hash` CHAR(64) DEFAULT NULL,
  `is_active` TINYINT(1) NOT NULL DEFAULT 0,
  `remark` VARCHAR(512) DEFAULT NULL,
  `created_by` VARCHAR(64) DEFAULT 'system',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` TIMESTAMP NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_key_version` (`scope`, `config_key`, `version`),
  KEY `idx_scope_key_active` (`scope`, `config_key`, `is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `qa_enum_dict` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `scope` VARCHAR(64) NOT NULL DEFAULT 'enterprise',
  `dict_code` VARCHAR(128) NOT NULL,
  `dict_name` VARCHAR(255) DEFAULT NULL,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_dict` (`scope`, `dict_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `qa_enum_entry` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `dict_id` BIGINT UNSIGNED NOT NULL,
  `entry_key` VARCHAR(128) NOT NULL,
  `entry_label` VARCHAR(512) NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `metadata_json` JSON DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_key` (`dict_id`, `entry_key`),
  KEY `idx_dict_sort` (`dict_id`, `sort_order`),
  CONSTRAINT `fk_enum_entry_dict` FOREIGN KEY (`dict_id`) REFERENCES `qa_enum_dict` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `qa_entity_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `scope` VARCHAR(64) NOT NULL DEFAULT 'enterprise',
  `domain` VARCHAR(64) NOT NULL DEFAULT 'org_master',
  `entity_type` VARCHAR(64) NOT NULL DEFAULT 'company',
  `entity_id` VARCHAR(128) NOT NULL,
  `payload_json` LONGTEXT NOT NULL,
  `content_hash` CHAR(64) NOT NULL,
  `source_db` VARCHAR(128) DEFAULT 'tdcomp',
  `batch_id` VARCHAR(64) DEFAULT NULL,
  `synced_neo4j_at` TIMESTAMP NULL DEFAULT NULL,
  `synced_qdrant_at` TIMESTAMP NULL DEFAULT NULL,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_domain_entity` (`scope`, `domain`, `entity_type`, `entity_id`),
  KEY `idx_scope_batch` (`scope`, `batch_id`),
  KEY `idx_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `qa_document_corpus` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `scope` VARCHAR(64) NOT NULL DEFAULT 'enterprise',
  `corpus_code` VARCHAR(128) NOT NULL DEFAULT 'enterprise_mysql_compiled',
  `title` VARCHAR(255) DEFAULT NULL,
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_corpus` (`scope`, `corpus_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `qa_document_chunk` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `corpus_id` BIGINT UNSIGNED NOT NULL,
  `chunk_key` VARCHAR(128) NOT NULL,
  `anchor_id` VARCHAR(128) DEFAULT NULL,
  `display_label` VARCHAR(512) DEFAULT NULL,
  `content_text` MEDIUMTEXT NOT NULL,
  `token_estimate` INT DEFAULT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_corpus_chunk` (`corpus_id`, `chunk_key`),
  KEY `idx_corpus_sort` (`corpus_id`, `sort_order`),
  CONSTRAINT `fk_chunk_corpus` FOREIGN KEY (`corpus_id`) REFERENCES `qa_document_corpus` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `qa_audit_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_type` VARCHAR(64) NOT NULL,
  `turn_id` VARCHAR(64) DEFAULT NULL,
  `scope` VARCHAR(64) DEFAULT NULL,
  `payload_json` JSON NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_created` (`event_type`, `created_at`),
  KEY `idx_turn` (`turn_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

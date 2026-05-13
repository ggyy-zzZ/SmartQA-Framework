-- Flyway：与 data/sql/mysql/assistant_bootstrap.sql 中待沉淀/反馈表对齐（IF NOT EXISTS 便于与手工初始化混用）

CREATE TABLE IF NOT EXISTS `qa_pending_knowledge` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pending_id` VARCHAR(64) NOT NULL,
  `turn_id` VARCHAR(64) NOT NULL,
  `question` TEXT NOT NULL,
  `intent` VARCHAR(64) NOT NULL,
  `retrieval_source` VARCHAR(128) NOT NULL,
  `deposit_reason` VARCHAR(64) NOT NULL,
  `evidence_json` LONGTEXT,
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pending_id` (`pending_id`),
  KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `qa_user_feedback` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `feedback_id` VARCHAR(64) NOT NULL,
  `turn_id` VARCHAR(64) NOT NULL,
  `useful` TINYINT(1) NOT NULL,
  `comment` VARCHAR(2000) DEFAULT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feedback_id` (`feedback_id`),
  KEY `idx_turn_id` (`turn_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

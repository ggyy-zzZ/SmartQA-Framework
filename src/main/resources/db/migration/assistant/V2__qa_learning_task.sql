-- V2: 批量学习任务跟踪表
-- 用于跟踪 CSV 批量学习的全流程：任务创建 → 分析 → 方案生成 → 执行 → 完成

CREATE TABLE IF NOT EXISTS `qa_learning_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id` VARCHAR(64) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL COMMENT 'csv_batch/mysql_schema/markdown等',
  `status` VARCHAR(32) NOT NULL DEFAULT 'created' COMMENT 'created/analyzing/planned/executing/completed/failed',
  `file_count` INT NOT NULL DEFAULT 0,
  `total_rows` BIGINT NOT NULL DEFAULT 0,
  `scope` VARCHAR(32) NOT NULL DEFAULT 'enterprise',
  `plan_json` LONGTEXT COMMENT '学习方案 JSON',
  `analysis_json` LONGTEXT COMMENT '分析结果 JSON',
  `error_message` TEXT,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每个 CSV 文件的学习状态明细
CREATE TABLE IF NOT EXISTS `qa_learning_task_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id` VARCHAR(64) NOT NULL,
  `filename` VARCHAR(255) NOT NULL,
  `table_name` VARCHAR(128) NOT NULL,
  `row_count` INT NOT NULL DEFAULT 0,
  `column_count` INT NOT NULL DEFAULT 0,
  `strategy_type` VARCHAR(32) NOT NULL DEFAULT 'FULL_LEARN' COMMENT 'FULL_LEARN/SAMPLE_THEN_FULL/KEY_COLUMNS_ONLY',
  `strategy_note` VARCHAR(512),
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/learning/completed/failed/skipped',
  `knowledge_id` VARCHAR(64) COMMENT '学习后生成的知识ID',
  `error_message` TEXT,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_knowledge_id` (`knowledge_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
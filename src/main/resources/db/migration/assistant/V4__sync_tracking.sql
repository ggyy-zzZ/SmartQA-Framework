-- 表级同步追踪（与 sync_entity_state 互补；ScheduledSync 历史兼容）

CREATE TABLE IF NOT EXISTS `sync_tracking` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_host` VARCHAR(128) NOT NULL,
  `source_port` INT NOT NULL DEFAULT 3306,
  `source_db` VARCHAR(128) NOT NULL,
  `table_name` VARCHAR(128) NOT NULL,
  `last_sync_time` TIMESTAMP NULL DEFAULT NULL,
  `last_row_count` BIGINT NOT NULL DEFAULT 0,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sync_source_table` (`source_host`, `source_port`, `source_db`, `table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

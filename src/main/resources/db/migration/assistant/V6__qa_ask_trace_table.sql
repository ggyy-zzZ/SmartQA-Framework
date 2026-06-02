-- 可检索问答追踪表：用于排查 queryType 路由、闸门拒绝与证据计数问题

CREATE TABLE IF NOT EXISTS `qa_ask_trace` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `turn_id` VARCHAR(64) DEFAULT NULL,
  `conversation_id` VARCHAR(64) DEFAULT NULL,
  `scope` VARCHAR(64) DEFAULT NULL,
  `question` VARCHAR(1024) DEFAULT NULL,
  `intent` VARCHAR(64) DEFAULT NULL,
  `query_type` VARCHAR(64) DEFAULT NULL,
  `retrieval_source` VARCHAR(128) DEFAULT NULL,
  `route` VARCHAR(128) DEFAULT NULL,
  `can_answer` TINYINT(1) NOT NULL DEFAULT 0,
  `answer_gate_reject_reason` VARCHAR(128) DEFAULT NULL,
  `evidence_count` INT NOT NULL DEFAULT 0,
  `knowledge_deposit_triggered` TINYINT(1) NOT NULL DEFAULT 0,
  `payload_json` JSON NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_turn_id` (`turn_id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_query_type_created` (`query_type`, `created_at`),
  KEY `idx_reject_reason_created` (`answer_gate_reject_reason`, `created_at`),
  KEY `idx_can_answer_created` (`can_answer`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

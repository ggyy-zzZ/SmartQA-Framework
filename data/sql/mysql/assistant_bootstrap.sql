-- assistant schema：主动学习落库 + 与 SqlQueryService / MysqlContextService 对齐的示例业务表
-- 用法（已存在库 assistant）：mysql -u root -p assistant < data/sql/mysql/assistant_bootstrap.sql

SET NAMES utf8mb4;

-- 主动学习 MySQL 通道（与 ActiveLearningService 一致；应用也会在首次写入时尝试建表）
CREATE TABLE IF NOT EXISTS `qa_active_knowledge` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `knowledge_id` VARCHAR(64) NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `content` LONGTEXT NOT NULL,
  `source_type` VARCHAR(64) NOT NULL,
  `source_name` VARCHAR(255) NOT NULL,
  `trigger_type` VARCHAR(64) NOT NULL,
  `scope` VARCHAR(32) NOT NULL DEFAULT 'enterprise',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_id` (`knowledge_id`),
  KEY `idx_qak_scope_created` (`scope`, `created_at`),
  KEY `idx_qak_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- SqlQueryService 人员-角色预检依赖固定表名 employee / company
CREATE TABLE IF NOT EXISTS `employee` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `department` VARCHAR(128) DEFAULT NULL,
  `email` VARCHAR(128) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_employee_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `company` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `company_name` VARCHAR(255) NOT NULL,
  `status` VARCHAR(32) DEFAULT NULL,
  `entity_type` VARCHAR(64) DEFAULT NULL,
  `entity_category` VARCHAR(128) DEFAULT NULL,
  `registered_address` VARCHAR(512) DEFAULT NULL,
  `business_scope` TEXT,
  `deleteflag` TINYINT NOT NULL DEFAULT 0,
  `legal_rep_id` INT DEFAULT NULL,
  `manager_id` INT DEFAULT NULL,
  `financial_manager_id` INT DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_company_name` (`company_name`),
  KEY `idx_company_deleteflag` (`deleteflag`),
  KEY `idx_company_legal_rep` (`legal_rep_id`),
  CONSTRAINT `fk_company_legal_rep` FOREIGN KEY (`legal_rep_id`) REFERENCES `employee` (`id`)
    ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT `fk_company_manager` FOREIGN KEY (`manager_id`) REFERENCES `employee` (`id`)
    ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT `fk_company_finance` FOREIGN KEY (`financial_manager_id`) REFERENCES `employee` (`id`)
    ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 可重复执行：固定主键示例数据
INSERT IGNORE INTO `employee` (`id`, `name`, `department`, `email`) VALUES
  (1, '张三', '管理部', 'zhangsan@example.local'),
  (2, '李四', '财务部', 'lisi@example.local');

INSERT IGNORE INTO `company` (
  `id`, `company_name`, `status`, `entity_type`, `entity_category`,
  `registered_address`, `business_scope`, `deleteflag`,
  `legal_rep_id`, `manager_id`, `financial_manager_id`
) VALUES (
  1,
  '示例科技有限公司',
  '存续',
  '有限责任公司',
  '科学研究与技术服务',
  '上海市浦东新区示例路1号',
  '技术开发、技术咨询、技术服务；软件销售。',
  0,
  1,
  2,
  2
);

-- 待沉淀队列：未知意图 / 证据不足时写入，供后续人工或离线消费（与 jsonl 并行）
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

-- 点赞/点踩持久化（与文件日志并行）
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

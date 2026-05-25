-- 清空 assistant 库中的问答系统数据与业务表（保留表结构，不插入 demo 样例）
-- 业务问答数据请走 Neo4j / Qdrant（playground「一键重建」）或 tdcomp 等业务库，勿依赖本库 company/employee 假数据
-- 用法: mysql -u root -p assistant < data/sql/mysql/assistant_reset_data.sql

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE `qa_user_feedback`;
TRUNCATE TABLE `qa_pending_knowledge`;
TRUNCATE TABLE `qa_active_knowledge`;
TRUNCATE TABLE `company`;
TRUNCATE TABLE `employee`;

SET FOREIGN_KEY_CHECKS = 1;

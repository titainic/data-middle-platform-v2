-- Immutable governance policy and assessment metadata; no Hive mutation.
CREATE TABLE IF NOT EXISTS dp_warehouse_quality_policy (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 space_id BIGINT NOT NULL,
 name VARCHAR(128) NOT NULL,
 version INT NOT NULL,
 definition_json LONGTEXT NOT NULL,
 creator_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_quality_policy_version(space_id,name,version),
 CONSTRAINT chk_quality_policy_definition CHECK(JSON_VALID(definition_json))
);
CREATE TABLE IF NOT EXISTS dp_warehouse_quality_assessment (
 id CHAR(36) NOT NULL PRIMARY KEY,
 batch_id CHAR(36) NOT NULL,
 policy_id BIGINT NOT NULL,
 attempt INT NOT NULL,
 status VARCHAR(24) NOT NULL,
 creator_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 finished_at DATETIME(6) NULL,
 message TEXT NULL,
 UNIQUE KEY uk_quality_assessment_attempt(batch_id,policy_id,attempt)
);
CREATE TABLE IF NOT EXISTS dp_warehouse_quality_assessment_task (
 assessment_id CHAR(36) NOT NULL,
 dataset_id BIGINT NOT NULL,
 model_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 log_id BIGINT NULL,
 planned_json LONGTEXT NOT NULL,
 blocking_rule_ids LONGTEXT NOT NULL,
 input_fingerprint CHAR(64) NOT NULL,
 row_count BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 rule_hits BIGINT NULL,
 blocking_hits BIGINT NULL,
 message TEXT NULL,
 PRIMARY KEY(assessment_id,dataset_id),
 UNIQUE KEY uk_assessment_native_task(task_id),
 UNIQUE KEY uk_assessment_native_log(log_id),
 CONSTRAINT chk_assessment_plan CHECK(JSON_VALID(planned_json)),
 CONSTRAINT chk_assessment_blocking_rules CHECK(JSON_VALID(blocking_rule_ids))
);

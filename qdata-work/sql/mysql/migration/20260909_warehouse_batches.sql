-- Additive platform metadata only. No Hive statement or automatic cleanup.
CREATE TABLE IF NOT EXISTS dp_warehouse_batch (
 id CHAR(36) NOT NULL PRIMARY KEY,
 space_id BIGINT NOT NULL,
 name VARCHAR(128) NOT NULL,
 request_key CHAR(64) NOT NULL,
 attempt INT NOT NULL,
 retry_of CHAR(36) NULL,
 window_start DATE NOT NULL,
 window_end DATE NOT NULL,
 status VARCHAR(24) NOT NULL,
 request_json LONGTEXT NOT NULL,
 plan_json LONGTEXT NOT NULL,
 input_json LONGTEXT NOT NULL,
 task_id BIGINT NULL,
 task_version INT NULL,
 active_instance_id BIGINT NULL,
 lease_owner VARCHAR(64) NULL,
 create_by VARCHAR(64) NOT NULL,
 creator_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 started_at DATETIME(6) NULL,
 finished_at DATETIME(6) NULL,
 message TEXT NULL,
 UNIQUE KEY uk_warehouse_batch_attempt(space_id,request_key,attempt),
 UNIQUE KEY uk_warehouse_batch_task(task_id),
 CONSTRAINT chk_batch_request CHECK(JSON_VALID(request_json)),
 CONSTRAINT chk_batch_plan CHECK(JSON_VALID(plan_json)),
 CONSTRAINT chk_batch_input CHECK(JSON_VALID(input_json))
);
CREATE TABLE IF NOT EXISTS dp_warehouse_batch_step (
 batch_id CHAR(36) NOT NULL,
 ordinal INT NOT NULL,
 template_model_id BIGINT NOT NULL,
 template_revision INT NOT NULL,
 model_id BIGINT NULL,
 dataset_id BIGINT NOT NULL,
 node_code VARCHAR(128) NULL,
 definition_json LONGTEXT NOT NULL,
 dependencies_json LONGTEXT NOT NULL,
 status VARCHAR(24) NOT NULL,
 instance_id BIGINT NULL,
 row_count BIGINT NULL,
 output_fingerprint CHAR(64) NULL,
 started_at DATETIME(6) NULL,
 finished_at DATETIME(6) NULL,
 message TEXT NULL,
 PRIMARY KEY(batch_id,ordinal),
 UNIQUE KEY uk_warehouse_batch_model(model_id),
 CONSTRAINT chk_batch_step_definition CHECK(JSON_VALID(definition_json)),
 CONSTRAINT chk_batch_step_dependencies CHECK(JSON_VALID(dependencies_json))
);
CREATE TABLE IF NOT EXISTS dp_warehouse_batch_submission (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 space_id BIGINT NOT NULL,
 batch_id CHAR(36) NOT NULL,
 request_key CHAR(64) NOT NULL,
 outcome VARCHAR(24) NOT NULL,
 source_json LONGTEXT NOT NULL,
 create_by VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_warehouse_submission_batch(batch_id),
 CONSTRAINT chk_batch_submission_sources CHECK(JSON_VALID(source_json))
);
SET @warehouse_task_column=IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dpp_etl_task' AND column_name='warehouse_batch_id')=0,
 'ALTER TABLE dpp_etl_task ADD COLUMN warehouse_batch_id CHAR(36) NULL COMMENT ''Immutable warehouse batch reference''',
 'SELECT 1');
PREPARE warehouse_batch_migration FROM @warehouse_task_column;
EXECUTE warehouse_batch_migration;
DEALLOCATE PREPARE warehouse_batch_migration;
SET @warehouse_canonical_column=IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dp_warehouse_batch' AND column_name='canonical_key')=0,
 'ALTER TABLE dp_warehouse_batch ADD COLUMN canonical_key CHAR(64) NULL, ADD INDEX idx_batch_canonical(space_id,canonical_key)',
 'SELECT 1');
PREPARE warehouse_batch_migration FROM @warehouse_canonical_column;
EXECUTE warehouse_batch_migration;
DEALLOCATE PREPARE warehouse_batch_migration;

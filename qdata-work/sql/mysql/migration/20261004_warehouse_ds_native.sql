-- DolphinScheduler owns the 20-node DAG. The callbacks execute one guarded qData step each.
-- Metadata only; no Hive or HDFS cleanup or overwrite.
CREATE TABLE IF NOT EXISTS dp_warehouse_ds_native_run (
 batch_id CHAR(36) NOT NULL PRIMARY KEY,
 task_id BIGINT NOT NULL,
 task_instance_id BIGINT NOT NULL,
 project_code VARCHAR(64) NOT NULL,
 workflow_code VARCHAR(64) NOT NULL,
 instance_id BIGINT NULL,
 lease_owner CHAR(36) NOT NULL,
 state VARCHAR(24) NOT NULL,
 message VARCHAR(512) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 KEY idx_ds_native_state(state,updated_at),
 UNIQUE KEY uk_ds_native_instance(project_code,instance_id)
);

CREATE TABLE IF NOT EXISTS dp_warehouse_ds_native_node (
 batch_id CHAR(36) NOT NULL,
 ordinal INT NOT NULL,
 ds_node_code VARCHAR(64) NOT NULL,
 token_sha256 CHAR(64) NOT NULL,
 PRIMARY KEY(batch_id,ordinal),
 UNIQUE KEY uk_ds_native_node(batch_id,ds_node_code)
);

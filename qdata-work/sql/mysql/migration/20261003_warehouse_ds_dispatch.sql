-- Metadata-only dispatch ledger. Never deletes or overwrites Hive/HDFS data.
CREATE TABLE IF NOT EXISTS dp_warehouse_ds_dispatch (
 batch_id CHAR(36) NOT NULL PRIMARY KEY,
 task_id BIGINT NOT NULL,
 project_code VARCHAR(64) NOT NULL,
 workflow_code VARCHAR(64) NOT NULL,
 instance_id BIGINT NOT NULL,
 state VARCHAR(24) NOT NULL,
 message VARCHAR(512) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_warehouse_ds_instance(project_code,instance_id)
);

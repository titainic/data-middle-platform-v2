-- Quality execution evidence in platform MySQL; no Hive mutation or cleanup.
CREATE TABLE IF NOT EXISTS dpp_quality_execution_snapshot (
 log_id BIGINT NOT NULL PRIMARY KEY,
 task_id BIGINT NOT NULL,
 definition_json LONGTEXT NOT NULL,
 definition_sha256 CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_quality_execution_task(task_id,log_id),
 CONSTRAINT chk_quality_execution_definition CHECK(JSON_VALID(definition_json))
);

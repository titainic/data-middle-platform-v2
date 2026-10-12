-- Additive, retained provenance for server-created CSV/Excel input artifacts.
CREATE TABLE IF NOT EXISTS sys_development_file_artifact (
    execution_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    execution_path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_resource VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    project_id BIGINT NOT NULL,
    owner_user_id BIGINT NULL,
    origin VARCHAR(12) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_dev_file_project (project_id),
    KEY ix_dev_file_source (source_hash,project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Additive metadata only. Never removes or rewrites uploaded files or Hive data.
CREATE TABLE IF NOT EXISTS sys_uploaded_file (
 resource_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 resource_key VARCHAR(1024) COLLATE utf8mb4_bin NOT NULL,
 owner_user_id BIGINT NULL,
 purpose VARCHAR(24) NOT NULL DEFAULT 'UPLOAD',
 bound_flag TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(resource_hash), KEY idx_uploaded_owner(owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS sys_uploaded_file_ref (
 kind VARCHAR(16) NOT NULL,
 child_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 resource_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(kind,child_id), KEY idx_uploaded_ref_hash(resource_hash), KEY idx_uploaded_ref_asset(asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

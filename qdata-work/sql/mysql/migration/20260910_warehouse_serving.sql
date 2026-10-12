-- Versioned serving metadata only; warehouse data is retained.
CREATE TABLE IF NOT EXISTS dp_warehouse_serving_target (
 space_id BIGINT NOT NULL PRIMARY KEY,
 datasource_id BIGINT NOT NULL,
 database_name VARCHAR(128) NOT NULL,
 connection_fingerprint CHAR(64) NOT NULL,
 replicas INT NOT NULL DEFAULT 3,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE IF NOT EXISTS dp_warehouse_serving_product (
 space_id BIGINT NOT NULL,
 dataset_id BIGINT NOT NULL,
 contract_version INT NOT NULL DEFAULT 1,
 table_name VARCHAR(128) NOT NULL,
 schema_json LONGTEXT NOT NULL,
 api_id BIGINT NULL,
 PRIMARY KEY(space_id,dataset_id,contract_version),
 UNIQUE KEY uk_serving_api(api_id),
 UNIQUE KEY uk_serving_product_table(space_id,table_name),
 CONSTRAINT chk_serving_schema CHECK(JSON_VALID(schema_json))
);
CREATE TABLE IF NOT EXISTS dp_warehouse_serving_release (
 id CHAR(36) NOT NULL PRIMARY KEY,
 space_id BIGINT NOT NULL,
 batch_id CHAR(36) NOT NULL,
 assessment_id CHAR(36) NOT NULL,
 contract_version INT NOT NULL DEFAULT 1,
 status VARCHAR(24) NOT NULL,
 ever_published BOOLEAN NOT NULL DEFAULT FALSE,
 creator_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 finished_at DATETIME(6) NULL,
 message TEXT NULL,
 INDEX idx_serving_batch(space_id,batch_id,assessment_id)
);
CREATE TABLE IF NOT EXISTS dp_warehouse_serving_release_product (
 release_id CHAR(36) NOT NULL,
 dataset_id BIGINT NOT NULL,
 contract_version INT NOT NULL DEFAULT 1,
 model_id BIGINT NOT NULL,
 row_count BIGINT NULL,
 fingerprint CHAR(64) NULL,
 status VARCHAR(24) NOT NULL,
 PRIMARY KEY(release_id,dataset_id,contract_version)
);
CREATE TABLE IF NOT EXISTS dp_warehouse_serving_head (
 space_id BIGINT NOT NULL,
 contract_version INT NOT NULL DEFAULT 1,
 release_id CHAR(36) NOT NULL,
 PRIMARY KEY(space_id,contract_version),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE IF NOT EXISTS dp_warehouse_serving_scope (
 client_id BIGINT NOT NULL,
 space_id BIGINT NOT NULL,
 locations_json LONGTEXT NOT NULL,
 columns_json LONGTEXT NOT NULL,
 masked_columns_json LONGTEXT NOT NULL,
 PRIMARY KEY(client_id,space_id),
 CONSTRAINT chk_serving_locations CHECK(JSON_VALID(locations_json)),
 CONSTRAINT chk_serving_columns CHECK(JSON_VALID(columns_json)),
 CONSTRAINT chk_serving_masked_columns CHECK(JSON_VALID(masked_columns_json))
);
CREATE TABLE IF NOT EXISTS dp_warehouse_serving_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 space_id BIGINT NOT NULL,
 release_id CHAR(36) NOT NULL,
 previous_release_id CHAR(36) NULL,
 actor_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

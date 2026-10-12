-- Additive platform metadata migration. No Hive DDL/DML and no data deletion.
-- Apply to a verified backup/restore candidate before deployment.
CREATE TABLE IF NOT EXISTS dp_warehouse_engine (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(128) NOT NULL,
 datasource_id BIGINT NOT NULL,
 catalog_key VARCHAR(128) NOT NULL COMMENT 'Explicitly registered Hive catalog identity',
 connection_fingerprint CHAR(64) NOT NULL COMMENT 'Non-secret Hive routing identity',
 revision INT NOT NULL DEFAULT 1,
 create_by VARCHAR(64), created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_warehouse_engine_source(datasource_id),
 UNIQUE KEY uk_warehouse_engine_connection(connection_fingerprint),
 UNIQUE KEY uk_warehouse_engine_catalog(catalog_key)
);
CREATE TABLE IF NOT EXISTS dp_warehouse_space (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(128) NOT NULL,
 project_id BIGINT NOT NULL,
 environment_code VARCHAR(32) NOT NULL,
 engine_id BIGINT NOT NULL,
 databases_json LONGTEXT NOT NULL COMMENT 'ODS/DWD/DWS/DWT/ADS database bindings',
 revision INT NOT NULL DEFAULT 1,
 create_by VARCHAR(64), created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_warehouse_space(project_id,environment_code,name),
 CONSTRAINT chk_warehouse_space_databases CHECK (JSON_VALID(databases_json))
);
CREATE TABLE IF NOT EXISTS dp_warehouse_namespace (
 engine_id BIGINT NOT NULL,
 database_name VARCHAR(128) NOT NULL,
 space_id BIGINT NOT NULL,
 layer_code VARCHAR(8) NOT NULL,
 PRIMARY KEY(engine_id,database_name),
 UNIQUE KEY uk_warehouse_namespace_layer(space_id,layer_code)
);
CREATE TABLE IF NOT EXISTS dp_warehouse_dataset (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 space_id BIGINT NOT NULL,
 code VARCHAR(128) NOT NULL,
 name VARCHAR(256) NOT NULL,
 layer_code VARCHAR(8) NOT NULL,
 create_by VARCHAR(64), created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_warehouse_dataset(space_id,layer_code,code)
);
CREATE TABLE IF NOT EXISTS dp_warehouse_materialization (
 model_id BIGINT NOT NULL PRIMARY KEY,
 dataset_id BIGINT NOT NULL,
 engine_id BIGINT NOT NULL,
 database_name VARCHAR(128) NOT NULL,
 table_name VARCHAR(128) NOT NULL,
 UNIQUE KEY uk_warehouse_physical_table(engine_id,database_name,table_name)
);

-- Backfill and column additions are guarded for repeatable execution on MySQL 8.
SET @add_asset_database = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='da_asset' AND column_name='database_name')=0,
 'ALTER TABLE da_asset ADD COLUMN database_name VARCHAR(128) NULL COMMENT ''Physical database; independent of connection default''',
 'SELECT 1');
PREPARE warehouse_migration FROM @add_asset_database;
EXECUTE warehouse_migration;
DEALLOCATE PREPARE warehouse_migration;
SET @add_asset_dataset = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='da_asset' AND column_name='dataset_id')=0,
 'ALTER TABLE da_asset ADD COLUMN dataset_id BIGINT NULL COMMENT ''Logical warehouse dataset identity''',
 'SELECT 1');
PREPARE warehouse_migration FROM @add_asset_dataset;
EXECUTE warehouse_migration;
DEALLOCATE PREPARE warehouse_migration;
SET @add_model_database = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dp_model' AND column_name='database_name')=0,
 'ALTER TABLE dp_model ADD COLUMN database_name VARCHAR(128) NULL COMMENT ''Physical database''',
 'SELECT 1');
PREPARE warehouse_migration FROM @add_model_database;
EXECUTE warehouse_migration;
DEALLOCATE PREPARE warehouse_migration;
SET @add_model_dataset = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dp_model' AND column_name='dataset_id')=0,
 'ALTER TABLE dp_model ADD COLUMN dataset_id BIGINT NULL COMMENT ''Logical warehouse dataset identity''',
 'SELECT 1');
PREPARE warehouse_migration FROM @add_model_dataset;
EXECUTE warehouse_migration;
DEALLOCATE PREPARE warehouse_migration;

UPDATE dp_model m JOIN da_datasource s ON s.id=m.datasource_id
SET m.database_name=LOWER(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(s.datasource_config),s.datasource_config,'{}'),'$.dbname')))
WHERE m.del_flag='0' AND m.database_name IS NULL AND m.warehouse_config IS NOT NULL AND JSON_VALID(m.warehouse_config)
  AND UPPER(s.datasource_type)='HIVE' AND JSON_VALID(s.datasource_config)
  AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(s.datasource_config),s.datasource_config,'{}'),'$.dbname')) REGEXP '^[A-Za-z_][A-Za-z0-9_]*$';
UPDATE dp_model SET warehouse_config=JSON_SET(warehouse_config,'$.databaseName',database_name)
WHERE del_flag='0' AND warehouse_config IS NOT NULL AND JSON_VALID(warehouse_config) AND database_name IS NOT NULL
  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(warehouse_config),warehouse_config,'{}'),'$.databaseName')),'null') IN ('null','');
UPDATE da_asset a JOIN da_datasource s ON s.id=a.datasource_id
SET a.database_name=LOWER(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(s.datasource_config),s.datasource_config,'{}'),'$.dbname')))
WHERE a.del_flag='0' AND a.database_name IS NULL AND UPPER(s.datasource_type)='HIVE' AND JSON_VALID(s.datasource_config)
  AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(s.datasource_config),s.datasource_config,'{}'),'$.dbname')) REGEXP '^[A-Za-z_][A-Za-z0-9_]*$';

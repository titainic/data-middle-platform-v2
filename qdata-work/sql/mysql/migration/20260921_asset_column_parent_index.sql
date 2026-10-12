-- Catalog metadata only. Preserve all existing rows and column types.
-- asset_id is LONGTEXT in the existing schema; all normal asset IDs fit in 64 characters.
SET @qdata_asset_column_index_present = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='da_asset_column' AND index_name='idx_da_asset_column_parent');
SET @qdata_asset_column_index_sql = IF(@qdata_asset_column_index_present=0, 'ALTER TABLE da_asset_column ADD INDEX idx_da_asset_column_parent (asset_id(64), del_flag)', 'SELECT 1');
PREPARE qdata_asset_column_index_statement FROM @qdata_asset_column_index_sql;
EXECUTE qdata_asset_column_index_statement;
DEALLOCATE PREPARE qdata_asset_column_index_statement;

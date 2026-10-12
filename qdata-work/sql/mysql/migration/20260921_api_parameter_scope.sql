SET @qdata_param_index_present=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='da_asset_api_param' AND index_name='idx_da_api_param_scope');
SET @qdata_param_index_sql=IF(@qdata_param_index_present=0,'ALTER TABLE da_asset_api_param ADD INDEX idx_da_api_param_scope(api_id,del_flag,parent_id)','SELECT 1');
PREPARE qdata_param_index_statement FROM @qdata_param_index_sql;
EXECUTE qdata_param_index_statement;
DEALLOCATE PREPARE qdata_param_index_statement;

SET @qdata_api_slot_index_present=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='da_asset_api' AND index_name='idx_da_api_asset_scope');
SET @qdata_api_slot_index_sql=IF(@qdata_api_slot_index_present=0,'ALTER TABLE da_asset_api ADD INDEX idx_da_api_asset_scope(asset_id,del_flag)','SELECT 1');
PREPARE qdata_api_slot_index_statement FROM @qdata_api_slot_index_sql;
EXECUTE qdata_api_slot_index_statement;
DEALLOCATE PREPARE qdata_api_slot_index_statement;

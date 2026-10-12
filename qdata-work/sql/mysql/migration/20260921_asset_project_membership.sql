SET @qdata_relation_index=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='da_asset_project_rel' AND index_name='idx_da_asset_project_membership');
SET @qdata_relation_sql=IF(@qdata_relation_index=0,'ALTER TABLE da_asset_project_rel ADD INDEX idx_da_asset_project_membership(asset_id,project_id,valid_flag,del_flag)','SELECT 1');
PREPARE qdata_relation_statement FROM @qdata_relation_sql;
EXECUTE qdata_relation_statement;
DEALLOCATE PREPARE qdata_relation_statement;

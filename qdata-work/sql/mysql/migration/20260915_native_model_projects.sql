-- Platform metadata only. Physical Hive data is never touched.
SET @qdata_model_project_sql=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dp_model' AND column_name='project_id')=0,
'ALTER TABLE dp_model ADD COLUMN project_id BIGINT NULL COMMENT ''Explicit owning project; legacy unassigned models remain private''','SELECT 1');
PREPARE qdata_model_project_migration FROM @qdata_model_project_sql; EXECUTE qdata_model_project_migration; DEALLOCATE PREPARE qdata_model_project_migration;
SET @qdata_model_project_sql=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dp_model' AND index_name='idx_dp_model_project')=0,
'CREATE INDEX idx_dp_model_project ON dp_model(project_id,del_flag)','SELECT 1');
PREPARE qdata_model_project_migration FROM @qdata_model_project_sql; EXECUTE qdata_model_project_migration; DEALLOCATE PREPARE qdata_model_project_migration;
UPDATE dp_model m JOIN dp_warehouse_space s ON s.id=JSON_UNQUOTE(JSON_EXTRACT(m.warehouse_config,'$.spaceId'))
SET m.project_id=s.project_id,m.update_time=m.update_time WHERE m.project_id IS NULL;

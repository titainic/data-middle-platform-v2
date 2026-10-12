-- Additive MySQL metadata only. Keep all existing definitions, issues, snapshots and logs.
SET @qdata_quality_project_sql=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dpp_quality_task' AND column_name='project_id')=0,
'ALTER TABLE dpp_quality_task ADD COLUMN project_id BIGINT NULL COMMENT ''Explicit quality task project; legacy unresolved tasks remain private''','SELECT 1');
PREPARE qdata_quality_project_migration FROM @qdata_quality_project_sql; EXECUTE qdata_quality_project_migration; DEALLOCATE PREPARE qdata_quality_project_migration;
SET @qdata_quality_project_sql=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dpp_quality_task' AND index_name='idx_quality_task_project')=0,
'CREATE INDEX idx_quality_task_project ON dpp_quality_task(project_id,del_flag)','SELECT 1');
PREPARE qdata_quality_project_migration FROM @qdata_quality_project_sql; EXECUTE qdata_quality_project_migration; DEALLOCATE PREPARE qdata_quality_project_migration;
UPDATE dpp_quality_task t JOIN da_asset a ON a.id=t.asset_id JOIN dp_warehouse_dataset d ON d.id=a.dataset_id JOIN dp_warehouse_space s ON s.id=d.space_id
SET t.project_id=s.project_id,t.update_time=t.update_time WHERE t.project_id IS NULL;
UPDATE dpp_quality_task t JOIN (
 SELECT asset_id,MIN(project_id) project_id FROM da_asset_project_rel WHERE del_flag='0' AND valid_flag='1' GROUP BY asset_id HAVING COUNT(DISTINCT project_id)=1
) a ON a.asset_id=t.asset_id SET t.project_id=a.project_id,t.update_time=t.update_time WHERE t.project_id IS NULL;

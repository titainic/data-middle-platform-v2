-- Additive MySQL metadata migration. Never modifies Hive data or historical definitions.
SET @qdata_mc_project_sql=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mc_task' AND column_name='project_id')=0,
'ALTER TABLE mc_task ADD COLUMN project_id BIGINT NULL COMMENT ''Explicit collection task project; unresolved legacy tasks remain private''','SELECT 1');
PREPARE qdata_mc_project_migration FROM @qdata_mc_project_sql; EXECUTE qdata_mc_project_migration; DEALLOCATE PREPARE qdata_mc_project_migration;
SET @qdata_mc_project_sql=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mc_task' AND index_name='idx_mc_task_project')=0,
'CREATE INDEX idx_mc_task_project ON mc_task(project_id,del_flag)','SELECT 1');
PREPARE qdata_mc_project_migration FROM @qdata_mc_project_sql; EXECUTE qdata_mc_project_migration; DEALLOCATE PREPARE qdata_mc_project_migration;
UPDATE mc_task t JOIN (
 SELECT datasource_id,MIN(project_id) project_id FROM da_datasource_project_rel WHERE valid_flag='1' GROUP BY datasource_id HAVING COUNT(DISTINCT project_id)=1
) r ON r.datasource_id=t.datasource_id JOIN att_project p ON p.id=r.project_id AND p.del_flag='0' AND p.valid_flag='1'
SET t.project_id=r.project_id,t.update_time=t.update_time WHERE t.project_id IS NULL;

-- Additive platform metadata only; no Hive/HDFS operation.
SET @qdata_service_project_sql=IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND LOWER(table_name)='ds_api' AND LOWER(column_name)='project_id')=0,
 'ALTER TABLE ds_api ADD COLUMN project_id BIGINT NULL COMMENT ''Owning project; null retains private legacy ownership''',
 'SELECT 1');
PREPARE qdata_service_project_migration FROM @qdata_service_project_sql;
EXECUTE qdata_service_project_migration;
DEALLOCATE PREPARE qdata_service_project_migration;
SET @qdata_service_project_sql=IF(
 (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND LOWER(table_name)='ds_api' AND index_name='idx_ds_api_project')=0,
 'CREATE INDEX idx_ds_api_project ON ds_api(project_id,del_flag)',
 'SELECT 1');
PREPARE qdata_service_project_migration FROM @qdata_service_project_sql;
EXECUTE qdata_service_project_migration;
DEALLOCATE PREPARE qdata_service_project_migration;
-- Infer only unambiguous, already-declared warehouse product ownership.
UPDATE ds_api a JOIN (
 SELECT p.api_id,MIN(s.project_id) project_id
 FROM dp_warehouse_serving_product p JOIN dp_warehouse_space s ON s.id=p.space_id
 WHERE p.api_id IS NOT NULL
 GROUP BY p.api_id HAVING COUNT(DISTINCT s.project_id)=1
) owned ON owned.api_id=a.id
SET a.project_id=owned.project_id,a.update_time=a.update_time WHERE a.project_id IS NULL;

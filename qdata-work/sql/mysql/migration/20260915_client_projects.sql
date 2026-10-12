-- Additive platform metadata only; no Hive/HDFS operation.
SET @qdata_client_project_sql=IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND LOWER(table_name)='att_client' AND LOWER(column_name)='project_id')=0,
 'ALTER TABLE att_client ADD COLUMN project_id BIGINT NULL COMMENT ''Owning project; null retains private legacy ownership''',
 'SELECT 1');
PREPARE qdata_client_project_migration FROM @qdata_client_project_sql;
EXECUTE qdata_client_project_migration;
DEALLOCATE PREPARE qdata_client_project_migration;
SET @qdata_client_project_sql=IF(
 (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND LOWER(table_name)='att_client' AND index_name='idx_att_client_project')=0,
 'CREATE INDEX idx_att_client_project ON att_client(project_id,del_flag)',
 'SELECT 1');
PREPARE qdata_client_project_migration FROM @qdata_client_project_sql;
EXECUTE qdata_client_project_migration;
DEALLOCATE PREPARE qdata_client_project_migration;

-- Platform MySQL metadata only. Legacy rows remain DolphinScheduler until separately verified.
SET @qdata_project_scheduler_sql=IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND LOWER(table_name)='att_project' AND LOWER(column_name)='scheduler_type')=0,
 'ALTER TABLE att_project ADD COLUMN scheduler_type VARCHAR(32) NOT NULL DEFAULT ''DOLPHINSCHEDULER'' COMMENT ''Project scheduler ownership''',
 'SELECT 1');
PREPARE qdata_project_scheduler_migration FROM @qdata_project_scheduler_sql;
EXECUTE qdata_project_scheduler_migration;
DEALLOCATE PREPARE qdata_project_scheduler_migration;

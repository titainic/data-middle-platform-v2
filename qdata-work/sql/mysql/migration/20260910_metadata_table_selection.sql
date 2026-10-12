-- Additive platform metadata only. Existing Hive objects and metadata references are retained.
SET @mc_selection_sql=IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND LOWER(table_name)='mc_task' AND LOWER(column_name)='table_allowlist')=0,
 'ALTER TABLE mc_task ADD COLUMN table_allowlist LONGTEXT NULL COMMENT ''Exact Hive table selection''',
 'SELECT 1');
PREPARE mc_selection_migration FROM @mc_selection_sql;
EXECUTE mc_selection_migration;
DEALLOCATE PREPARE mc_selection_migration;
SET @mc_selection_sql=IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND LOWER(table_name)='mc_task_instance' AND LOWER(column_name)='table_allowlist')=0,
 'ALTER TABLE mc_task_instance ADD COLUMN table_allowlist LONGTEXT NULL COMMENT ''Frozen exact table selection''',
 'SELECT 1');
PREPARE mc_selection_migration FROM @mc_selection_sql;
EXECUTE mc_selection_migration;
DEALLOCATE PREPARE mc_selection_migration;

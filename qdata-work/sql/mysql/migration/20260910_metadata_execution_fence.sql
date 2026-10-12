-- Platform metadata fencing only; no Hive statement.
SET @mc_fence_sql=IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND LOWER(table_name)='mc_task' AND LOWER(column_name)='active_instance_id')=0,
 'ALTER TABLE mc_task ADD COLUMN active_instance_id BIGINT NULL COMMENT ''Current fenced collection attempt''',
 'SELECT 1');
PREPARE mc_fence_migration FROM @mc_fence_sql;
EXECUTE mc_fence_migration;
DEALLOCATE PREPARE mc_fence_migration;

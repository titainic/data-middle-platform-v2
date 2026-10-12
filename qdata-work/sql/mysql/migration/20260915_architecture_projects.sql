-- Additive MySQL metadata only. Existing graph JSON, revision and timestamps are unchanged.
SET @qdata_architecture_sql=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dp_architecture_document' AND column_name='project_id')=0,
'ALTER TABLE dp_architecture_document ADD COLUMN project_id BIGINT NULL COMMENT ''Explicit owning project; null keeps legacy private document''','SELECT 1');
PREPARE qdata_architecture_migration FROM @qdata_architecture_sql; EXECUTE qdata_architecture_migration; DEALLOCATE PREPARE qdata_architecture_migration;
SET @qdata_architecture_sql=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dp_architecture_document' AND column_name='creator_id')=0,
'ALTER TABLE dp_architecture_document ADD COLUMN creator_id BIGINT NULL COMMENT ''Authenticated creator''','SELECT 1');
PREPARE qdata_architecture_migration FROM @qdata_architecture_sql; EXECUTE qdata_architecture_migration; DEALLOCATE PREPARE qdata_architecture_migration;
SET @qdata_architecture_sql=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dp_architecture_document' AND index_name='idx_architecture_project')=0,
'CREATE INDEX idx_architecture_project ON dp_architecture_document(project_id,update_time)','SELECT 1');
PREPARE qdata_architecture_migration FROM @qdata_architecture_sql; EXECUTE qdata_architecture_migration; DEALLOCATE PREPARE qdata_architecture_migration;
-- Only map a unique exact username. Do not infer a project from a shared datasource.
UPDATE dp_architecture_document d JOIN (SELECT BINARY user_name username,MIN(user_id) user_id FROM system_user GROUP BY BINARY user_name HAVING COUNT(*)=1) u ON BINARY d.create_by=u.username
SET d.creator_id=u.user_id,d.update_time=d.update_time WHERE d.creator_id IS NULL;

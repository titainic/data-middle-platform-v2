-- Stage 7 candidate only: the managed-object binding witness requires bounded row locks.
-- Legacy qData tables have no indexes on these authorization edges. Never enable the
-- managed-object route until this migration has been applied and its exact index shape checked.

SET @mo_user_role_index = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'system_user_role'
    AND index_name = 'idx_mo_user_role'
);
SET @mo_user_role_sql = IF(@mo_user_role_index = 0,
  'ALTER TABLE system_user_role ADD INDEX idx_mo_user_role(user_id,role_id)',
  'SELECT 1');
PREPARE mo_user_role_statement FROM @mo_user_role_sql;
EXECUTE mo_user_role_statement;
DEALLOCATE PREPARE mo_user_role_statement;

SET @mo_role_menu_index = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'system_role_menu'
    AND index_name = 'idx_mo_role_menu'
);
SET @mo_role_menu_sql = IF(@mo_role_menu_index = 0,
  'ALTER TABLE system_role_menu ADD INDEX idx_mo_role_menu(role_id,menu_id)',
  'SELECT 1');
PREPARE mo_role_menu_statement FROM @mo_role_menu_sql;
EXECUTE mo_role_menu_statement;
DEALLOCATE PREPARE mo_role_menu_statement;

SET @mo_project_user_index = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'att_project_user_rel'
    AND index_name = 'idx_mo_project_user'
);
SET @mo_project_user_sql = IF(@mo_project_user_index = 0,
  'ALTER TABLE att_project_user_rel ADD INDEX idx_mo_project_user(project_id,user_id,id)',
  'SELECT 1');
PREPARE mo_project_user_statement FROM @mo_project_user_sql;
EXECUTE mo_project_user_statement;
DEALLOCATE PREPARE mo_project_user_statement;

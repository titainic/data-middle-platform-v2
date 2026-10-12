-- Platform MySQL metadata logs only. Preserve full field-change descriptions.
-- Capacity enlargement is backward compatible and does not touch Hive.
ALTER TABLE mc_table_log MODIFY COLUMN update_msg TEXT NULL COMMENT '变更说明';
ALTER TABLE mc_column_log MODIFY COLUMN update_msg TEXT NULL COMMENT '变更说明';

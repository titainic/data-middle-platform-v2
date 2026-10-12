-- Added disabled; the guarded controller release enables it after startup checks.
-- The classification page reads levels at mount; formal grading values are supplied later.
CREATE TABLE IF NOT EXISTS DG_DATA_LEVEL (
 ID BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 NAME VARCHAR(128) NOT NULL,
 SHORT_NAME VARCHAR(128) NULL,
 SENSITIVE_LEVEL BIGINT NULL,
 SORT_ORDER BIGINT NULL,
 DESCRIPTION VARCHAR(512) NULL,
 COLORS VARCHAR(32) NULL,
 VALID_FLAG VARCHAR(1) NOT NULL DEFAULT '1',
 DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
 CREATE_BY VARCHAR(32) NULL,
 CREATOR_ID BIGINT NULL,
 CREATE_TIME DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UPDATE_BY VARCHAR(32) NULL,
 UPDATER_ID BIGINT NULL,
 UPDATE_TIME DATETIME(6) NULL,
 REMARK VARCHAR(512) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
START TRANSACTION;
INSERT INTO system_menu(menu_name,parent_id,order_num,path,component,`query`,is_frame,is_cache,route_name,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '数据分类',2350,2,'dataCategory','dg/safety/dataCategory/index',NULL,1,0,NULL,'C','0','1','dg:dataCategory:list','folder-line','qdata-migration',UTC_TIMESTAMP(6),'Phase 6 read-only classification catalogue'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE parent_id=2350 AND path='dataCategory');
INSERT INTO system_role_menu(role_id,menu_id,project_id)
SELECT 7,m.menu_id,0 FROM system_menu m
WHERE m.parent_id=2350 AND m.path='dataCategory' AND m.component='dg/safety/dataCategory/index' AND m.perms='dg:dataCategory:list' AND m.status='1'
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=7 AND rm.menu_id=m.menu_id AND COALESCE(rm.project_id,0)=0);
COMMIT;

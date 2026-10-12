CREATE TABLE IF NOT EXISTS quality_error_data (
 id varchar(64) NOT NULL PRIMARY KEY,
 report_id varchar(64) NOT NULL,
 total_count int NOT NULL,
 error_count int NOT NULL,
 check_time datetime(3) NOT NULL,
 data_json longtext NOT NULL CHECK (JSON_VALID(data_json)),
 data_json_old longtext NOT NULL CHECK (JSON_VALID(data_json_old)),
 search_json json NOT NULL,
 json_data_old longtext NULL CHECK (JSON_VALID(json_data_old)),
 repair tinyint NOT NULL DEFAULT 0,
 remark text,
 KEY idx_quality_error_report_time (report_id,check_time,id),
 KEY idx_quality_error_report_repair (report_id,repair)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS quality_error_data_action (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 error_data_id varchar(64) NOT NULL,
 report_id varchar(64) NOT NULL,
 action varchar(32) NOT NULL,
 old_repair tinyint NOT NULL,
 new_repair tinyint NOT NULL,
 old_remark text,
 new_remark text,
 old_data longtext,
 new_data longtext,
 reason varchar(1000),
 operator_name varchar(64) NOT NULL,
 created_at bigint NOT NULL,
 KEY idx_quality_action_report (report_id,error_data_id,id),
 CONSTRAINT fk_quality_action_error FOREIGN KEY(error_data_id) REFERENCES quality_error_data(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO system_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time,remark)
SELECT 4,'暂缓修复','3','quality_log_data_repair','warning','N','0','admin',NOW(),'问题处理状态：暂缓修复'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data WHERE dict_type='quality_log_data_repair' AND dict_value='3');
UPDATE system_dict_data SET dict_label='待处理' WHERE dict_type='quality_log_data_repair' AND dict_value='0';
UPDATE system_dict_data SET dict_label='已修复' WHERE dict_type='quality_log_data_repair' AND dict_value='1';

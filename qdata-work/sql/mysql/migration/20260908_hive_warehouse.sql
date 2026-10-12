-- Apply once, after backing up qdata_v2. Existing DIM definitions remain historical.
ALTER TABLE dp_model ADD COLUMN warehouse_config LONGTEXT NULL COMMENT 'Hive five-layer definition, revision and lineage';
CREATE TABLE IF NOT EXISTS dp_warehouse_run (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 batch_id VARCHAR(64) NOT NULL, model_id BIGINT NOT NULL, revision INT NOT NULL,
 mode VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, ddl_created BOOLEAN NOT NULL DEFAULT FALSE,
 sql_text LONGTEXT, definition_json LONGTEXT, message TEXT, row_count BIGINT,
 create_by VARCHAR(64), started_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6),
 INDEX idx_model_run(model_id,id), INDEX idx_batch(batch_id)
);
INSERT INTO dm_data_layer(name,eng_name,owner_user_id,category,description,create_by)
SELECT '主题数据层','DWT',1,'2','围绕业务主题构建事实表与维度表，形成星型模型。加工来源仅为 DWS；主题维度关系单独管理。','admin'
WHERE NOT EXISTS(SELECT 1 FROM dm_data_layer WHERE eng_name='DWT' AND del_flag='0');
UPDATE dm_data_layer SET name='原始数据层', description='原始数据保持来源语义，不做业务清洗和改写；提供可追溯的贴源记录。' WHERE eng_name='ODS' AND del_flag='0';
UPDATE dm_data_layer SET name='清洗明细层', description='仅从 ODS 获取数据，进行清洗、类型与单位统一、质量提升，保留业务明细粒度。' WHERE eng_name='DWD' AND del_flag='0';
UPDATE dm_data_layer SET name='融合数据层', description='仅从 DWD 获取数据，完成多源汇聚、消歧、去重、关联及必要聚合；保留主题建模需要的明细。' WHERE eng_name='DWS' AND del_flag='0';
UPDATE dm_data_layer SET description='仅从 DWT 获取数据，根据报表、大屏和接口需求组织应用数据。' WHERE eng_name='ADS' AND del_flag='0';
UPDATE dm_data_layer SET valid_flag='0', remark=CONCAT(COALESCE(remark,''),' [2026-09-08] 旧 DIM 层保留历史引用；新维度模型在 DWT 建立。') WHERE eng_name='DIM' AND del_flag='0' AND valid_flag='1';

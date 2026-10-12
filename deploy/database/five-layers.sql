INSERT INTO dm_data_layer(id,name,eng_name,owner_user_id,category,description,create_by) VALUES
(1,'原始数据层','ODS',1,'1','原始数据保持来源语义，提供可追溯的贴源记录。','qdata-install'),
(3,'清洗明细层','DWD',1,'2','从 ODS 获取数据，清洗并统一类型与单位，保留明细粒度。','qdata-install'),
(4,'融合数据层','DWS',1,'2','从 DWD 汇聚、去重、关联，形成主题分析数据。','qdata-install'),
(6,'主题数据层','DWT',1,'2','从 DWS 按主题、事实和维度组织可复用的数据。','qdata-install'),
(5,'应用数据层','ADS',1,'3','从 DWT 组织报表、大屏和 API 所需的应用结果。','qdata-install');

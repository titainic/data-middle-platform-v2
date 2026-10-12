-- Correct only the shipped misleading descriptions; preserve codes, labels and custom descriptions.
UPDATE system_dict_data SET remark='记录业务事实的细粒度明细，例如单次水位观测、设备告警。',update_by='qdata-enterprise-migration',update_time=CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')
WHERE dict_type='table_type' AND dict_value='1' AND dict_label='明细表' AND remark='存放固定的基础档案信息，如“测站基本属性表”、“水库名录”等。';
UPDATE system_dict_data SET remark='按明确粒度汇总明细，例如测站日均水位、设备月告警次数。',update_by='qdata-enterprise-migration',update_time=CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')
WHERE dict_type='table_type' AND dict_value='2' AND dict_label='汇总表' AND remark='记录未经加工的原始流水记录，如“单次水位上报”、“设备报警日志”。';
UPDATE system_dict_data SET remark='描述分析对象的属性，例如测站、设备、区域档案。',update_by='qdata-enterprise-migration',update_time=CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')
WHERE dict_type='table_type' AND dict_value='3' AND dict_label='维度表' AND remark='按条件统计出来的阶段性结果，如“测站日降水量表”、“月均水位表”。';

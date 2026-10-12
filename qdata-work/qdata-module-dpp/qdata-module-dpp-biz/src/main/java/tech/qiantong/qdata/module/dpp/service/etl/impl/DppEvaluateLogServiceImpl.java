/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.module.dpp.service.etl.impl;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.core.text.Convert;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.httpClient.HeaderEntity;
import tech.qiantong.qdata.common.httpClient.HttpUtils;
import tech.qiantong.qdata.common.utils.DateUtils;
import tech.qiantong.qdata.common.utils.JSONUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.*;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.*;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEvaluateLogDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppQualityLogDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.qa.DppQualityTaskEvaluateDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.qa.DppQualityTaskObjDO;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEvaluateLogMapper;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEvaluateLogService;
import tech.qiantong.qdata.module.dpp.service.etl.IDppQualityLogService;
import tech.qiantong.qdata.module.dpp.service.qa.IDppQualityTaskEvaluateService;
import tech.qiantong.qdata.module.dpp.service.qa.IDppQualityTaskObjService;
import tech.qiantong.qdata.mybatis.core.query.LambdaQueryWrapperX;

/**
 * Evaluation Rule Result Service business layer processing
 *
 * @author qdata
 * @date 2025-07-21
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class DppEvaluateLogServiceImpl  extends ServiceImpl<DppEvaluateLogMapper,DppEvaluateLogDO> implements IDppEvaluateLogService {

    @Value("${path.quality_url}")
    private String url;
    @Resource
    private DppEvaluateLogMapper dppEvaluateLogMapper;

    @Resource
    @Lazy
    private IDppQualityLogService dppQualityLogService;
    @Resource
    @Lazy
    private IDppQualityTaskEvaluateService dppQualityTaskEvaluateService;
    @Resource
    @Lazy
    private IDppQualityTaskObjService dppQualityTaskObjService;
    @Resource
    @Lazy
    private IDaDatasourceApiService daDatasourceApiService;

    @Override
    public PageResult<DppEvaluateLogDO> getDppEvaluateLogPage(DppEvaluateLogPageReqVO pageReqVO) {
        return dppEvaluateLogMapper.selectPage(pageReqVO);
    }

    @Override
    public Long createDppEvaluateLog(DppEvaluateLogSaveReqVO createReqVO) {
        DppEvaluateLogDO dictType = BeanUtils.toBean(createReqVO, DppEvaluateLogDO.class);
        dppEvaluateLogMapper.insert(dictType);
        return dictType.getId();
    }

    @Override
    public int updateDppEvaluateLog(DppEvaluateLogSaveReqVO updateReqVO) {
        // Validate

        // Update evaluation rule result
        DppEvaluateLogDO updateObj = BeanUtils.toBean(updateReqVO, DppEvaluateLogDO.class);
        return dppEvaluateLogMapper.updateById(updateObj);
    }
    @Override
    public int removeDppEvaluateLog(Collection<Long> idList) {
        // Batch delete evaluation rule result
        return dppEvaluateLogMapper.deleteBatchIds(idList);
    }

    @Override
    public DppEvaluateLogDO getDppEvaluateLogById(Long id) {
        return dppEvaluateLogMapper.selectById(id);
    }

    @Override
    public List<DppEvaluateLogDO> getDppEvaluateLogList() {
        return dppEvaluateLogMapper.selectList();
    }

    @Override
    public Map<Long, DppEvaluateLogDO> getDppEvaluateLogMap() {
        List<DppEvaluateLogDO> dppEvaluateLogList = dppEvaluateLogMapper.selectList();
        return dppEvaluateLogList.stream()
                .collect(Collectors.toMap(
                        DppEvaluateLogDO::getId,
                        dppEvaluateLogDO -> dppEvaluateLogDO,
                        // Keep existing value
                        (existing, replacement) -> existing
                ));
    }

    @Override
    public Map<String, Object> sumTotalAndProblemTotalByTaskLogId(String taskLogId) {
        List<DppEvaluateLogDO> list = dppEvaluateLogMapper.selectList(new LambdaQueryWrapperX<DppEvaluateLogDO>()
                .eq(DppEvaluateLogDO::getTaskLogId, taskLogId)
                .eq(DppEvaluateLogDO::getValidFlag, "1")); // Add conditions if needed

        Long total = list.stream()
                .mapToLong(log -> log.getTotal() == null ? 0L : log.getTotal())
                .sum();

        Long problemTotal = list.stream()
                .mapToLong(log -> log.getProblemTotal() == null ? 0L : log.getProblemTotal())
                .sum();

        Map<String, Object> summary = new HashMap<>();
        summary.put("total", total);
        summary.put("problemTotal", problemTotal);
        return summary;
    }


    /**
     * Import evaluation rule result data
     *
     * @param importExcelList evaluation rule result data list
     * @param isUpdateSupport whether to support update; if already exists, update the data
     * @param operName operator user
     * @return result
     */
    @Override
    public String importDppEvaluateLog(List<DppEvaluateLogRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        if (StringUtils.isNull(importExcelList) || importExcelList.size() == 0) {
            throw new ServiceException("dpp.error.import.empty", "Import data cannot be empty!");
        }

        int successNum = 0;
        int failureNum = 0;
        List<String> successMessages = new ArrayList<>();
        List<String> failureMessages = new ArrayList<>();

        for (DppEvaluateLogRespVO respVO : importExcelList) {
            try {
                DppEvaluateLogDO dppEvaluateLogDO = BeanUtils.toBean(respVO, DppEvaluateLogDO.class);
                Long dppEvaluateLogId = respVO.getId();
                if (isUpdateSupport) {
                    if (dppEvaluateLogId != null) {
                        DppEvaluateLogDO existingDppEvaluateLog = dppEvaluateLogMapper.selectById(dppEvaluateLogId);
                        if (existingDppEvaluateLog != null) {
                            dppEvaluateLogMapper.updateById(dppEvaluateLogDO);
                            successNum++;
                            successMessages.add(MessageUtils.messageWithFallback("dpp.import.update.success",
                                    "Data update successful, ID {0} {1} record.", dppEvaluateLogId, MessageUtils.messageWithFallback("dpp.entity.evaluation.rule.result", "Evaluation rule result")));
                        } else {
                            failureNum++;
                            failureMessages.add(MessageUtils.messageWithFallback("dpp.import.update.fail",
                                    "Data update failed, ID {0} {1} record does not exist.", dppEvaluateLogId, MessageUtils.messageWithFallback("dpp.entity.evaluation.rule.result", "Evaluation rule result")));
                        }
                    } else {
                        failureNum++;
                        failureMessages.add(MessageUtils.messageWithFallback("dpp.import.update.id.missing",
                                "Data update failed, record ID does not exist."));
                    }
                } else {
                    QueryWrapper<DppEvaluateLogDO> queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("id", dppEvaluateLogId);
                    DppEvaluateLogDO existingDppEvaluateLog = dppEvaluateLogMapper.selectOne(queryWrapper);
                    if (existingDppEvaluateLog == null) {
                        dppEvaluateLogMapper.insert(dppEvaluateLogDO);
                        successNum++;
                        successMessages.add(MessageUtils.messageWithFallback("dpp.import.insert.success",
                                "Data insert successful, ID {0} {1} record.", dppEvaluateLogId, MessageUtils.messageWithFallback("dpp.entity.evaluation.rule.result", "Evaluation rule result")));
                    } else {
                        failureNum++;
                        failureMessages.add(MessageUtils.messageWithFallback("dpp.import.insert.fail",
                                "Data insert failed, ID {0} {1} record already exists.", dppEvaluateLogId, MessageUtils.messageWithFallback("dpp.entity.evaluation.rule.result", "Evaluation rule result")));
                    }
                }
            } catch (Exception e) {
                failureNum++;
                String errorMsg = MessageUtils.messageWithFallback("dpp.import.error.detail",
                "Data import failed, error: {0}", e.getMessage());
                failureMessages.add(errorMsg);
                log.error(errorMsg, e);
            }
        }
        StringBuilder resultMsg = new StringBuilder();
        if (failureNum > 0) {
            String failureDetails = String.join("<br/>", failureMessages);
            resultMsg.append(MessageUtils.messageWithFallback("dpp.import.result.fail",
                    "Import failed! {0} records have incorrect format, errors:<br/>{1}",
                    failureNum, failureDetails));
            throw new ServiceException("dpp.error.import.fail", resultMsg.toString(), resultMsg.toString());
        } else {
            resultMsg.append(MessageUtils.messageWithFallback("dpp.import.result.success",
                    "Congratulations! All data imported! Total: {0} records.", successNum));
        }
        return resultMsg.toString();
    }

    @Override
    public List<DppEvaluateLogStatisticsVO> statisticsEvaluateOne(Long id) {
        List<DppEvaluateLogStatisticsVO> dppEvaluateDimStatVOS = dppEvaluateLogMapper.selectDimStatsByTaskLogId(id);
        if(dppEvaluateDimStatVOS.isEmpty()){
            return new ArrayList<>();
        }
        DppQualityLogDO dppQualityLogDO = dppQualityLogService.selectPrevLogByIdWithWrapper(id);
        if(dppQualityLogDO == null){
            for (DppEvaluateLogStatisticsVO vo : dppEvaluateDimStatVOS) {
                vo.setTrendType(3L);
            }
            return dppEvaluateDimStatVOS;
        }
        List<DppEvaluateLogStatisticsVO> prevList = dppEvaluateLogMapper.selectDimStatsByTaskLogId(dppQualityLogDO.getId());
        if(prevList == null || prevList.isEmpty()){
            for (DppEvaluateLogStatisticsVO vo : dppEvaluateDimStatVOS) {
                vo.setTrendType(3L);
            }
            return dppEvaluateDimStatVOS;
        }

        // 4) Previous “problem proportion” baseline keyed by dimension
        Map<String, BigDecimal> prevProportionMap = new HashMap<>(prevList.size() * 2);
        for (DppEvaluateLogStatisticsVO vo : prevList) {
            BigDecimal val = vo.getProportion() == null ? BigDecimal.ZERO : vo.getProportion();
            prevProportionMap.put(String.valueOf(vo.getDimensionType()), val);
        }

        for (DppEvaluateLogStatisticsVO vo : dppEvaluateDimStatVOS) {
            String dim = String.valueOf(vo.getDimensionType());
            BigDecimal curProportion = vo.getProportion() == null ? BigDecimal.ZERO : vo.getProportion();
            BigDecimal prevProportion = prevProportionMap.get(dim);

            if (prevProportion == null) {
                vo.setTrendType(3L);
                continue;
            }

            int cmp = curProportion.compareTo(prevProportion);
            if (cmp > 0) {
                vo.setTrendType(1L);
            } else if (cmp < 0) {
                vo.setTrendType(2L);
            } else {
                vo.setTrendType(3L);
            }
        }
        return dppEvaluateDimStatVOS;
    }

//    @Override
//    public List<DppEvaluateLogStatisticsVO> statisticsEvaluateOne(Long id) {
//            List<DppEvaluateLogStatisticsVO> voList = new ArrayList<>();
//        DppQualityLogDO dppQualityLogById = dppQualityLogService.getDppQualityLogById(id);
//        DppQualityLogPageReqVO dppQualityLogPageReqVO = new DppQualityLogPageReqVO();
//        dppQualityLogPageReqVO.setQualityId(dppQualityLogById.getQualityId());
//        PageResult<DppQualityLogDO> dppQualityLogPage = dppQualityLogService.getDppQualityLogPage(dppQualityLogPageReqVO);
//        List<DppQualityLogDO> rows =  (List<DppQualityLogDO>) dppQualityLogPage.getRows();
//        DppQualityLogPageReqVO old = new DppQualityLogPageReqVO();
//        for (DppQualityLogDO row : rows) {
//            if (row.getCreateTime().getTime() <= dppQualityLogById.getCreateTime().getTime()) {
//                old = BeanUtils.toBean(row , DppQualityLogPageReqVO.class);
//                break;
//            }
//        }
//        // Latest
//        Long problemTotalAll = 0L;
//        List<DppEvaluateLogDO> taskLogId = dppEvaluateLogMapper.selectList("task_log_id", id);
//        Map<String, List<DppEvaluateLogDO>> collect = taskLogId.stream().collect(Collectors.groupingBy(s -> s.getDimensionType()));
//        for (DppEvaluateLogDO aDo : taskLogId) {
//            problemTotalAll += aDo.getProblemTotal();
//        }
//
//        // Previous
//        List<DppEvaluateLogDO> oldTaskLogId = dppEvaluateLogMapper.selectList("task_log_id", old.getId());
//        Map<String, List<DppEvaluateLogDO>> oldCollect = oldTaskLogId.stream().collect(Collectors.groupingBy(s -> s.getDimensionType()));
//        for (DppEvaluateLogDO aDo : oldTaskLogId) {
//
//        }
//        for (Map.Entry<String, List<DppEvaluateLogDO>> entry : collect.entrySet()) {
//            DppEvaluateLogStatisticsVO vo = new DppEvaluateLogStatisticsVO();
//            List<DppEvaluateLogDO> value = entry.getValue();
//            Long problemTotal = 0L;
//            for (DppEvaluateLogDO dppEvaluateLogDO : value) {
//                problemTotal += dppEvaluateLogDO.getProblemTotal();
//            }
//            vo.setDimensionType(entry.getKey());
//            vo.setProblemTotal(problemTotal);
//            vo.setSuccesTotal(Convert.toLong(value.size()));
//            vo.setProportion(BigDecimal.ZERO);
//            if (!problemTotal.equals(0L) && !problemTotalAll.equals(0L)) {
//                BigDecimal bigDecimal = new BigDecimal(problemTotal).divide(new BigDecimal(problemTotalAll) , 5, RoundingMode.HALF_UP);
//                BigDecimal subtract = bigDecimal.multiply(new BigDecimal(100));
//                vo.setProportion(subtract.setScale(2 , RoundingMode.HALF_UP));
//            }
//            List<DppEvaluateLogDO> dppEvaluateLogDOS = oldCollect.get(entry.getKey());
//            Long oldProblemTotal = 0L;
//            if (dppEvaluateLogDOS != null) {
//                for (DppEvaluateLogDO dppEvaluateLogDO : dppEvaluateLogDOS) {
//                    oldProblemTotal += dppEvaluateLogDO.getProblemTotal();
//                }
//                // Trend: 0=down, 1=up
//                if (problemTotal > oldProblemTotal) {
//                    vo.setTrendType(0L);
//                } else {
//                    vo.setTrendType(1L);
//                }
//            }
//
//            voList.add(vo);
//        }
//
//        return voList;
//    }

    @Resource
    private org.springframework.jdbc.core.JdbcTemplate trendJdbc;

    @Override
    public JSONObject statisticsEvaluateTow(Long id, Date deDate, Date oldDate, int type) {
        int days = switch (type) { case 0 -> 7; case 1 -> 15; case 2 -> 30;
            default -> throw new IllegalArgumentException("时间范围仅支持7、15或30天"); };
        var log = dppQualityLogService.getDppQualityLogById(id);
        if (log == null || log.getQualityId() == null) throw new IllegalArgumentException("质量执行记录不存在");
        LocalDate end = deDate == null ? LocalDate.now() : LocalDate.parse(DateUtils.parseDateToStr("yyyy-MM-dd", deDate));
        LocalDate start = end.minusDays(days - 1L), requestedStart = oldDate == null ? start : LocalDate.parse(DateUtils.parseDateToStr("yyyy-MM-dd", oldDate));
        if (requestedStart.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        LocalDate from = requestedStart.isAfter(start) ? requestedStart : start;
        // Count persisted rule evaluations, not quality scores. One data row checked by two rules counts twice.
        var rows = trendJdbc.queryForList("SELECT DATE(l.create_time) day, COALESCE(SUM(e.total),0) checked_count "
                + "FROM dpp_quality_log l JOIN dpp_evaluate_log e ON e.task_log_id=CONCAT(l.id,'') "
                + "WHERE l.quality_id=? AND l.del_flag='0' AND e.del_flag='0' AND l.create_time>=? AND l.create_time<? "
                + "GROUP BY DATE(l.create_time)", log.getQualityId(), from + " 00:00:00", end.plusDays(1) + " 00:00:00");
        Map<String, Long> counts = new HashMap<>();
        for (var row : rows) counts.put(String.valueOf(row.get("day")), new BigDecimal(String.valueOf(row.get("checked_count"))).longValueExact());
        List<String> titles = new ArrayList<>(); List<Long> values = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            titles.add(day.toString()); values.add(counts.getOrDefault(day.toString(), 0L));
        }
        JSONObject result = new JSONObject();result.put("title", titles);result.put("value", values);
        result.put("metric", "rule_check_rows");result.put("unit", "row_evaluations");result.put("dateBasis", "log_create_date");return result;
    }

    public List<DppQualityTaskObjRespVO> buildTaskObjRespList(DppQualityLogDO dppQualityLogById) {
        if (dppQualityLogById == null || dppQualityLogById.getQualityId() == null) {
            return Collections.emptyList();
        }

        DppQualityTaskObjPageReqVO reqVO = new DppQualityTaskObjPageReqVO();
        reqVO.setTaskId(JSONUtils.convertToLong(dppQualityLogById.getQualityId()));

        List<DppQualityTaskObjDO> lists = dppQualityTaskObjService.getDppQualityTaskObjList(reqVO);
        List<DppQualityTaskObjRespVO> result = new ArrayList<>();

        if (CollectionUtils.isNotEmpty(lists)) {
            for (DppQualityTaskObjDO objDO : lists) {
                DppQualityTaskObjRespVO bean = BeanUtils.toBean(objDO, DppQualityTaskObjRespVO.class);

                DaDatasourceRespDTO ds = daDatasourceApiService.getDatasourceById(objDO.getDatasourceId());
                if (ds != null) {
                    bean.setDatasourceType(ds.getDatasourceType());
                    bean.setDatasourceName(ds.getDatasourceName());
                }
                result.add(bean);
            }
        }
        return result;
    }

    @Override
    public List<DppEvaluateLogRespVO> statisticsEvaluateTable(Long id) {
        DppQualityLogDO dppQualityLogById = dppQualityLogService.getDppQualityLogById(id);
        if(dppQualityLogById == null){
            return new ArrayList<>();
        }
        List<DppEvaluateLogDO> taskLogId = dppEvaluateLogMapper.selectList("task_log_id", id);

        List<DppQualityTaskObjRespVO> newList = this.buildTaskObjRespList(dppQualityLogById);

        List<DppEvaluateLogRespVO> list = new ArrayList<>();
        Map<Long, DppQualityTaskObjRespVO> collect = newList.stream().collect(Collectors.toMap(s -> s.getId(), Function.identity()));
        for (DppEvaluateLogDO dppEvaluateLogDO : taskLogId) {
            Long evaluateId = Convert.toLong(dppEvaluateLogDO.getEvaluateId());
            DppQualityTaskEvaluateDO currentRule = evaluateId == null ? null : dppQualityTaskEvaluateService.getDppQualityTaskEvaluateById(evaluateId);
            DppQualityTaskObjRespVO dppQualityTaskObjRespVO = currentRule == null ? null : collect.get(currentRule.getObjId());

            DppEvaluateLogRespVO bean = BeanUtils.toBean(dppEvaluateLogDO, DppEvaluateLogRespVO.class);
            Long total = bean.getTotal() == null ? 0L : bean.getTotal();
            Long problemTotal = bean.getProblemTotal() == null ? 0L : bean.getProblemTotal();
            bean.setProportion(BigDecimal.ZERO);
            if (total > 0) {
                BigDecimal bigDecimal = new BigDecimal(problemTotal).divide(new BigDecimal(total) , 5, RoundingMode.HALF_UP);
                BigDecimal subtract = bigDecimal.multiply(new BigDecimal(100));
                bean.setProportion(subtract.setScale(2 , RoundingMode.HALF_UP));
            }

//            JSONObject jsonObject = JSONObject.parseObject(bean.getRule());
//            List<JSONObject> list1 = jsonObject.getList("evaColumns", JSONObject.class);
//            if (list1 != null) {
//                for (JSONObject object : list1) {
//                    String string = object.getString("name");
//                    if (bean.getColumnName().equals(string)) {
//                        bean.setColumnName(object.getString("label"));
//                    }
//                }
//            }
            // Historical results stay readable when the mutable rule is gone; a changed object is not their source.
            if (dppQualityTaskObjRespVO != null && Objects.equals(bean.getTableName(), dppQualityTaskObjRespVO.getTableName())) {
                bean.setDatasourceName(dppQualityTaskObjRespVO.getDatasourceName());
                bean.setDatasourceType(dppQualityTaskObjRespVO.getDatasourceType());
                bean.setDatasourceId(dppQualityTaskObjRespVO.getDatasourceId());
            }
            list.add(bean);
        }
        return list;
    }

    @Override
    public JSONObject pageErrorData(CheckErrorDataReqDTO checkErrorDataReqDTO) {
        List<HeaderEntity> headers = new ArrayList<>();
        HeaderEntity headerEntity = new HeaderEntity();
        headerEntity.setKey("Content-Type");
        headerEntity.setValue("application/json");
        headers.add(headerEntity);  // Set request headers
        try {
            String fullUrl = url + "/pageErrorData";

            // Convert object to JSON Map and send POST request (RequestBody)
            Map<String, Object> paramMap = JSONObject.parseObject(JSONObject.toJSONString(checkErrorDataReqDTO), Map.class);

            HttpUtils.ResponseObject responseObject = HttpUtils.sendPost(fullUrl, paramMap, headers);

            JSONObject json = JSONObject.parseObject(String.valueOf(responseObject.getBody()));
            if(json==null || json.getIntValue("code")!=200)throw new ServiceException(json==null?"质量服务未返回有效结果":json.getString("msg"));
            JSONObject data = json.getJSONObject("data");
            return data;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean updateErrorData(CheckErrorDataReqDTO checkErrorDataReqDTO) {
        checkErrorDataReqDTO.setOperator(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());
        List<HeaderEntity> headers = new ArrayList<>();
        HeaderEntity header = new HeaderEntity();
        header.setKey("Content-Type");
        header.setValue("application/json");
        headers.add(header);

        try {
            String fullUrl = url + "/updateErrorData";
            Map<String, Object> paramMap = JSONObject.parseObject(JSONObject.toJSONString(checkErrorDataReqDTO), Map.class);

            // Send POST request (with JSON request body)
            HttpUtils.ResponseObject responseObject = HttpUtils.sendPost(fullUrl, paramMap, headers);


            JSONObject result = JSONObject.parseObject(String.valueOf(responseObject.getBody()));
            if (result==null || result.getIntValue("code")!=200) throw new tech.qiantong.qdata.common.exception.ServiceException(result==null?"问题处理服务未返回有效结果":result.getString("msg"));
            return Boolean.TRUE.equals(result.getBoolean("data"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    @Override
    public List<Map<String,Object>> errorDataHistory(CheckErrorDataReqDTO dto) {
        var headers=new ArrayList<HeaderEntity>();
        var header=new HeaderEntity();header.setKey("Content-Type");header.setValue("application/json");headers.add(header);
        try {
            var response=HttpUtils.sendPost(url+"/errorDataHistory",JSONObject.parseObject(JSONObject.toJSONString(dto),Map.class),headers);
            var result=JSONObject.parseObject(String.valueOf(response.getBody()));
            if (result==null || result.getIntValue("code")!=200) throw new tech.qiantong.qdata.common.exception.ServiceException(result==null?"问题处理服务未返回有效结果":result.getString("msg"));
            return (List<Map<String,Object>>)(List<?>)result.getJSONArray("data").toJavaList(Map.class);
        } catch (IOException e) {throw new RuntimeException(e);}
    }

}

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

package tech.qiantong.qdata.quality.service.quality.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.constants.DbType;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.database.exception.DataQueryException;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.httpClient.HttpTaskLogger;
import tech.qiantong.qdata.common.utils.JSONUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.quality.controller.qa.vo.DppQualityLogSaveReqVO;
import tech.qiantong.qdata.quality.controller.qa.vo.DppQualityTaskRespVO;
import tech.qiantong.qdata.quality.controller.quality.vo.CheckErrorDataReqDTO;
import tech.qiantong.qdata.quality.controller.quality.vo.QualityRuleQueryReqDTO;
import tech.qiantong.qdata.quality.controller.quality.vo.ValidationSqlResult;
import tech.qiantong.qdata.quality.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskEvaluateDO;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskObjDO;
import tech.qiantong.qdata.quality.dal.dataobject.quality.CheckErrorData;
import tech.qiantong.qdata.quality.dal.dataobject.quality.QualityCheckResult;
import tech.qiantong.qdata.quality.dal.dataobject.quality.QualityRuleEntity;
import tech.qiantong.qdata.quality.repository.CheckErrorDataRepository;
import tech.qiantong.qdata.quality.service.datasource.IDaDatasourceQualityService;
import tech.qiantong.qdata.quality.service.qa.*;
import tech.qiantong.qdata.quality.service.quality.QualityTaskExecutorService;
import tech.qiantong.qdata.quality.utils.quality.QualitySqlGenerateFactory;
import tech.qiantong.qdata.quality.utils.quality.QualitySqlGenerator;
import tech.qiantong.qdata.quality.utils.quality.enums.CharacterValidationGenerator;
import tech.qiantong.qdata.redis.service.IRedisService;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import jakarta.annotation.Resource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Service
public class QualityTaskExecutorServiceImpl implements QualityTaskExecutorService {

//    @Autowired
//    private QualityRuleService qualityRuleService;

    @Autowired
    private QualitySqlGenerateFactory qualitySqlGenerateFactory;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Autowired
    private CheckErrorDataRepository checkErrorDataRepository;

    @Autowired
    private IDppQualityTaskService iDppQualityTaskService;

    @Autowired
    private IDppQualityTaskObjService iDppQualityTaskObjService;

    @Autowired
    private IDppQualityTaskEvaluateService iDppQualityTaskEvaluateService;

    @Autowired
    private IDppQualityLogService iDppQualityLogService;

    @Autowired
    private IDppEvaluateLogService iDppEvaluateLogService;

    @Resource
    @Lazy
    private IDaDatasourceQualityService iDaDatasourceQualityService;

    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor qualityTaskExecutor;

    @Autowired
    private IRedisService redisService;

    @Autowired
    private tech.qiantong.qdata.quality.service.quality.QualityExecutionSnapshots executionSnapshots;
    // Local file path prefix
    private static String prefixUrl;

    @Value("${file.job.log.qualitytask_prefix_url}")
    private void setPrefixUrl(String prefixUrl) {
        this.prefixUrl = prefixUrl;
    }


    @Override
    public void executeTask(String taskId) {
        String key = "executeQualityTask-" + taskId;
        final tech.qiantong.qdata.redis.service.OwnedRedisLease lease;
        try { lease=tech.qiantong.qdata.redis.service.OwnedRedisLease.acquire(redisService,key+":lease",120); }
        catch(IllegalStateException busy) { throw new ServiceException("quality.error.task.running", "Previous task is still running, please try again later"); }
        try {
            var plan=executionSnapshots.capture(Long.parseLong(taskId));
            redisService.set(key,"1",1200);
            qualityTaskExecutor.submit(()->executeQualityTask(plan,lease));
        } catch(RuntimeException failure) {
            try {
                recordPreparationFailure(taskId,failure);
                if(lease.isOwner())redisService.set(key,"3",300);
            } finally { lease.close(); }
            throw failure;
        }
    }

    private void recordPreparationFailure(String taskId,RuntimeException failure) {
        try {
            var task=iDppQualityTaskService.getDppQualityTaskById(Long.parseLong(taskId));
            if(task==null)return;
            var logger=new HttpTaskLogger(prefixUrl,"taskLog-preflight-"+IdUtil.simpleUUID()+".txt");
            try {
                logger.log("Quality preparation failed: "+failure.getMessage());
                var log=new DppQualityLogSaveReqVO(task);log.setSuccessFlag("1");log.setEndTime(DateUtil.date());log.setPath(logger.getFilePath());
                iDppQualityLogService.createDppQualityLog(log);
            } finally { logger.close(); }
        } catch(Exception unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Could not record quality preparation failure for task {}",taskId,unavailable);
        }
    }

    public void executeQualityTask(tech.qiantong.qdata.quality.service.quality.QualityExecutionSnapshots.Plan plan,
                                   tech.qiantong.qdata.redis.service.OwnedRedisLease lease) {
        String taskId=String.valueOf(plan.taskId()),key="executeQualityTask-"+taskId;
        HttpTaskLogger logger = null;
        Long taskLogId = null;
        List<DbQuery> openedQueries = new ArrayList<>();
        boolean failed = true;
        try {
            logger = new HttpTaskLogger(prefixUrl, "taskLog-" + IdUtil.simpleUUID() + ".txt");
            logger.log(MessageUtils.messageEn("quality.log.task.start"));
            DppQualityTaskRespVO task = plan.task();
            if (task == null) throw new IllegalStateException("Quality task does not exist: " + taskId);
            logger.log(MessageUtils.messageEn("quality.log.task.info", task.getTaskName()));
            String batch = DateUtil.format(new Date(), "yyyyMMddHHmmss");
            DppQualityLogSaveReqVO taskLog = new DppQualityLogSaveReqVO(task);
            taskLog.setPath(logger.getFilePath());
            taskLogId = iDppQualityLogService.createDppQualityLog(taskLog);
            executionSnapshots.persist(taskLogId,plan);
            executionSnapshots.validateProject(plan);
            lease.requireOwner();
            logger.log(MessageUtils.messageEn("quality.log.log.created", taskLogId));

            List<DppQualityTaskObjDO> objects = plan.objects();
            if (CollectionUtils.isEmpty(objects)) throw new IllegalStateException("Quality task has no inspection objects");
            boolean ruleFailed = false;
            int completedRules = 0;
            for (Map.Entry<Long, List<Long>> entry : groupIdsByDatasourceId(objects).entrySet()) {
                lease.requireOwner();
                DaDatasourceDO source = iDaDatasourceQualityService.getDaDatasourceById(entry.getKey());
                if (source == null) {
                    ruleFailed = true;
                    logger.log(MessageUtils.messageEn("quality.log.datasource.fetch.fail", entry.getKey()));
                    continue;
                }
                logger.log("Datasource " + source.getId() + " uses " + source.getDatasourceType()
                        + " at " + source.getIp() + ":" + source.getPort());
                DbQuery dbQuery;
                DbQueryProperty property = new DbQueryProperty(source.getDatasourceType(), source.getIp(),
                        source.getPort(), source.getDatasourceConfig());
                try {
                    dbQuery = dataSourceFactory.createDbQuery(property);
                    openedQueries.add(dbQuery);
                    if (!dbQuery.valid()) throw new IllegalStateException("Datasource connection validation failed");
                } catch (Exception | LinkageError e) {
                    ruleFailed = true;
                    logger.log(MessageUtils.messageEn("quality.log.datasource.connection.error", e.toString()));
                    continue;
                }
                List<DppQualityTaskEvaluateDO> rules = plan.rules().stream().filter(rule->entry.getValue().contains(rule.getObjId())).toList();
                for (DppQualityTaskEvaluateDO definition : rules) {
                    lease.requireOwner();
                    try {
                        QualityRuleEntity rule = new QualityRuleEntity(definition);
                        rule.setTaskLogId(taskLogId);
                        List<DbColumn> columns = dbQuery.getTableColumns(property, rule.getTableName());
                        if (CollectionUtils.isEmpty(columns)) throw new IllegalStateException("No visible columns for " + rule.getTableName());
                        rule.setShowErrorColumns(columns.stream().map(DbColumn::getColName).collect(Collectors.toList()));
                        rule.setDaDatasourceById(source);
                        QualityCheckResult result = new RuleExecutorTask(rule, batch, dbQuery,
                                qualitySqlGenerateFactory, checkErrorDataRepository, logger, iDppEvaluateLogService,
                                "Hive".equalsIgnoreCase(source.getDatasourceType()) ? objects.stream().filter(object->entry.getKey().equals(object.getDatasourceId()))
                                        .map(object->tech.qiantong.qdata.common.database.core.HiveTableIdentity.resolve(object.getTableName(),property.getDbName()).canonical())
                                        .collect(Collectors.toSet()) : Set.of(),lease::requireOwner).call();
                        lease.requireOwner();
                        if (result == null || StringUtils.isNotBlank(result.getErrorMessage())) {
                            ruleFailed = true;
                        } else {
                            completedRules++;
                        }
                    } catch (Exception | LinkageError e) {
                        ruleFailed = true;
                        logger.log("Rule " + definition.getId() + " failed: " + e.toString());
                    }
                }
            }
            failed = ruleFailed || completedRules == 0 || !lease.isOwner();
        } catch (Exception | LinkageError e) {
            if (logger != null) logger.log("Quality task failed: " + e.toString());
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Quality task {} failed", taskId, e);
        } finally {
            try {
                failed = failed || !lease.isOwner();
                if (taskLogId != null) {
                    updateQualityLog(taskLogId, failed ? "1" : "0");
                    executionSnapshots.finished(taskLogId,failed);
                }
            } finally {
                for (DbQuery query : openedQueries) {
                    try { query.close(); } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Failed to close quality datasource", e);
                    }
                }
                try {
                    if (logger != null) {
                        logger.log(MessageUtils.messageEn("quality.log.task.end"));
                        logger.close();
                    }
                } finally {
                    try { if(lease.isOwner())redisService.set(key, failed ? "3" : "2", 300); }
                    finally { lease.close(); }
                }
            }
        }
    }

    public static Map<Long, List<Long>> groupIdsByDatasourceId(List<DppQualityTaskObjDO> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }

        return list.stream()
                .filter(obj -> obj.getDatasourceId() != null && obj.getId() != null)
                .collect(Collectors.groupingBy(
                        DppQualityTaskObjDO::getDatasourceId,
                        Collectors.mapping(DppQualityTaskObjDO::getId, Collectors.toList())
                ));
    }


    /**
     * Update data quality log status (only update successFlag and end time)
     * @param id log ID
     * @param successFlag status flag (0: success, 1: failure; 2 is running)
     */
    public void updateQualityLog(Long id, String successFlag) {
        DppQualityLogSaveReqVO vo = new DppQualityLogSaveReqVO();
        vo.setId(id);
        vo.setSuccessFlag(successFlag);
        vo.setEndTime(DateUtil.date());

        iDppQualityLogService.updateDppQualityLog(vo);
    }

//
//    public void executeTask2(QualityRuleEntity taskId) {
// // 1. Query task details (simulation or from task table)
// // Contains data source information, table names, task batches, etc.
//        String batch = DateUtil.format(new Date(), "yyyyMMddHHmmss");
//
// // 2. Query quality rules (executable)
////        List<QualityRuleEntity> rules = qualityRuleService.getRulesByTaskId(taskId);
//        List<QualityRuleEntity> rules =new ArrayList<>();
//        rules.add(taskId);
//        if (CollectionUtils.isEmpty(rules)) {
//            return;
//        }
//
//        ExecutorService executor = Executors.newFixedThreadPool(5);
//
//        List<Future<QualityCheckResult>> futures = new ArrayList<>();
//        for (QualityRuleEntity rule : rules) {
//            DaDatasourceDO daDatasourceById = iDaDatasourceQualityService.getDaDatasourceById(JSONUtils.convertToLong(rule.getDataId()));
//            rule.setDaDatasourceById(daDatasourceById);
//            futures.add(executor.submit(new RuleExecutorTask(rule, batch, qualitySqlGenerateFactory, mongoTemplate,dataSourceFactory)));
//        }
//
//        try {
//            for (Future<QualityCheckResult> future : futures) {
//                QualityCheckResult result = future.get();
//                System.out.println(result.toString());
// //Save the result report, expandable logic
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        } finally {
//            executor.shutdown();
//        }
//    }



    static void validatePreviewPage(QualityRuleQueryReqDTO request) {
        int page=request.getPageNum()==null?1:request.getPageNum(),size=request.getPageSize()==null?20:request.getPageSize();
        if(page<1 || size<1 || size>200 || (long)page*size>20000)throw new IllegalArgumentException("规则抽样页大小须为1至200，抽样范围最多20000行");
        request.setPageNum(page);request.setPageSize(size);
    }
    static void verifyPreviewSql(DbQueryProperty property,String table,String sql) {
        if(!"Hive".equalsIgnoreCase(property.getDbType()))return;
        var target=tech.qiantong.qdata.common.database.core.HiveTableIdentity.resolve(table,property.getDbName()).canonical();
        var actual=tech.qiantong.qdata.common.database.core.HiveReadQuery.tables(sql,property.getDbName());
        if(!java.util.Set.of(target).containsAll(actual))throw new IllegalArgumentException("规则抽样SQL只能读取当前声明的稽查对象");
    }

    @Override
    public ValidationSqlResult generateValidationValidDataSql(QualityRuleQueryReqDTO queryReqDTO) {
        validatePreviewPage(queryReqDTO);
        DaDatasourceDO daDatasourceById = iDaDatasourceQualityService.getDaDatasourceById(JSONUtils.convertToLong(queryReqDTO.getDataId()));
        if (daDatasourceById == null){
            throw new ServiceException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");
        }
        DbQueryProperty dbQueryProperty = new DbQueryProperty(
                daDatasourceById.getDatasourceType(),
                daDatasourceById.getIp(),
                daDatasourceById.getPort(),
                daDatasourceById.getDatasourceConfig()
        );
        DbQuery dbQuery;
        try {
            dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
            if (!dbQuery.valid()) {
                throw new DataQueryException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");            }
        }catch (Exception e){
            throw new DataQueryException("db.error.datasource.realtime.error", "Error establishing real-time datasource connection!");
        }
        List<DbColumn> tableColumns = dbQuery.getTableColumns(dbQueryProperty, queryReqDTO.getTableName());
        List<String> showErrorColumns = tableColumns.stream()
                .map(DbColumn::getColName)
                .collect(Collectors.toList());
        ValidationSqlResult validationSqlResult = new ValidationSqlResult();
        //Store parameters
        validationSqlResult.setShowErrorColumns(tableColumns);

        QualitySqlGenerator generator = qualitySqlGenerateFactory.getGenerator(queryReqDTO.getRuleType());
        QualityRuleEntity rule = new QualityRuleEntity(queryReqDTO);
        rule.setShowErrorColumns(showErrorColumns);
        rule.setDaDatasourceById(daDatasourceById);
        String checkSql = generator.generateValidDataSql(rule,queryReqDTO.getLimit(),queryReqDTO.getOffset());
        verifyPreviewSql(dbQueryProperty,queryReqDTO.getTableName(),checkSql);
        // 2. Execute SQL
        try (Connection conn = dbQuery.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(30);stmt.setMaxRows(queryReqDTO.getLimit());
            List<JSONObject> errorList = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(checkSql)) {
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    for (String col : rule.getShowErrorColumns()) {
                        row.put(col, rs.getObject(col));
                    }
                    errorList.add(row);
                }
            }
            validationSqlResult.setDataList(errorList);
        }catch (Exception e){
            throw new DataQueryException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");
        }

        dbQuery.close();
        validationSqlResult.setLimit(queryReqDTO.getLimit());
        validationSqlResult.setOffset(queryReqDTO.getOffset());
        validationSqlResult.setPageNum(queryReqDTO.getPageNum());
        validationSqlResult.setPageSize(queryReqDTO.getPageSize());
        return validationSqlResult;
    }

    @Override
    public ValidationSqlResult generateValidationErrorDataSql(QualityRuleQueryReqDTO queryReqDTO) {
        validatePreviewPage(queryReqDTO);
        DaDatasourceDO daDatasourceById = iDaDatasourceQualityService.getDaDatasourceById(JSONUtils.convertToLong(queryReqDTO.getDataId()));
        if (daDatasourceById == null){
            throw new ServiceException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");
        }
        DbQueryProperty dbQueryProperty = new DbQueryProperty(
                daDatasourceById.getDatasourceType(),
                daDatasourceById.getIp(),
                daDatasourceById.getPort(),
                daDatasourceById.getDatasourceConfig()
        );
        DbQuery dbQuery;
        try {
            dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
            if (!dbQuery.valid()) {
                throw new DataQueryException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");            }
        }catch (Exception e){
            throw new DataQueryException("db.error.datasource.realtime.error", "Error establishing real-time datasource connection!");
        }
        List<DbColumn> tableColumns = dbQuery.getTableColumns(dbQueryProperty, queryReqDTO.getTableName());
        List<String> showErrorColumns = tableColumns.stream()
                .map(DbColumn::getColName)
                .collect(Collectors.toList());
        ValidationSqlResult validationSqlResult = new ValidationSqlResult();
        validationSqlResult.setShowErrorColumns(tableColumns);

        QualitySqlGenerator generator = qualitySqlGenerateFactory.getGenerator(queryReqDTO.getRuleType());
        QualityRuleEntity rule = new QualityRuleEntity(queryReqDTO);
        //Store parameters
        rule.setShowErrorColumns(showErrorColumns);
        rule.setDaDatasourceById(daDatasourceById);
        String checkSql = generator.generateErrorSql(rule);
        verifyPreviewSql(dbQueryProperty,queryReqDTO.getTableName(),checkSql);
        // 2. Execute SQL
        try (Connection conn = dbQuery.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(30);stmt.setMaxRows(queryReqDTO.getOffset()+queryReqDTO.getLimit());
            List<JSONObject> errorList = new ArrayList<>();
            int skipped=0;
            try (ResultSet rs = stmt.executeQuery(checkSql)) {
                while (rs.next()) {
                    if(skipped++<queryReqDTO.getOffset())continue;
                    if(errorList.size()>=queryReqDTO.getLimit())break;
                    JSONObject row = new JSONObject();
                    for (String col : rule.getShowErrorColumns()) {
                        row.put(col, rs.getObject(col));
                    }
                    errorList.add(row);
                }
            }
            validationSqlResult.setDataList(errorList);
        }catch (Exception e){
            throw new DataQueryException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");
        }
        return validationSqlResult;
    }

//    @Override
//    public Page<CheckErrorData> pageErrorData(PageRequest of,  CheckErrorDataReqDTO checkErrorDataReqDTO) {
//        CheckErrorData person =  this.convertFrom(checkErrorDataReqDTO);
//        Example<CheckErrorData> example = Example.of(person);
//        Page<CheckErrorData> page = checkErrorDataRepository.findAll(example, of);
// System.out.println("Total number of items: " + page.getTotalElements());
// System.out.println("Total number of pages: " + page.getTotalPages());
//        page.getContent().forEach(s -> {
//            s.setJsonData(JSONObject.parseObject(s.getDataJsonStr()));
//        });
//        return page;
//    }
//
//    public static CheckErrorData convertFrom(CheckErrorDataReqDTO dto) {
//        CheckErrorData data = new CheckErrorData();
//        data.setReportId(dto.getReportId());
//
//        if (dto.getRepair() != null){
//            data.setRepair(dto.getRepair());
//        }
//
//
//
//        return data;
//    }

    @Override
    public Page<CheckErrorData> pageErrorData(PageRequest pageRequest, CheckErrorDataReqDTO dto) {
        return checkErrorDataRepository.page(pageRequest, dto);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean updateErrorData(CheckErrorDataReqDTO dto) {
        if (dto.getReportId() == null || dto.getUpdateType() == null) return false;
        if (dto.getOperator()!=null && dto.getOperator().length()>64) throw new ServiceException("操作人名称过长");
        switch (dto.getUpdateType()) {
            case "1":
                if (dto.getUpdatedData()==null || dto.getUpdatedData().isEmpty() || dto.getOldData()==null) return false;
                String id = MapUtils.getString(dto.getOldData(),"id");
                CheckErrorData current = checkErrorDataRepository.getByIdForUpdate(dto.getReportId(),id);
                if (current==null) return false;
                if (current.getRepair()!=0) throw new ServiceException("请先将问题重新打开为待处理，再提交修复");
                // Use the persisted snapshot when matching the source row.
                dto.setOldData(new HashMap<>(Map.of("id",current.getId(),"jsonData",current.getJsonData(),"dataJsonStr",current.getDataJsonStr())));
                if (dto.getKeyWordData()==null || dto.getKeyWordData().isEmpty()) return false;
                Map<String,Object> merged=new LinkedHashMap<>(current.getJsonData());
                for (var change:dto.getKeyWordData().entrySet()) {
                    if (!merged.containsKey(change.getKey())) throw new ServiceException("修复字段不在原问题记录中");
                    merged.put(change.getKey(),change.getValue());
                }
                dto.setUpdatedData(merged);
                return checkErrorDataRepository.updateData(dto.getReportId(),id,dto.getUpdatedData(),dto.getOperator(),()->updatePhysicalTable(dto));
            case "2":
                return dto.getId()!=null && checkErrorDataRepository.updateRemark(dto.getReportId(),dto.getId(),dto.getRemark(),dto.getOperator());
            case "3": case "4": case "5": case "6": case "7": case "8":
                return checkErrorDataRepository.changeState(dto.getReportId(),dto.getErrorDataId(),dto.getUpdateType(),dto.getReason(),dto.getOperator());
            default:
                return false;
        }
    }

    @Override
    public List<Map<String,Object>> errorDataHistory(CheckErrorDataReqDTO dto) {
        return checkErrorDataRepository.history(dto.getReportId(),dto.getId());
    }

    private boolean updatePhysicalTable(CheckErrorDataReqDTO dto) {
        DaDatasourceDO datasource = iDaDatasourceQualityService.getDaDatasourceById(dto.getDatasourceId());
        if (datasource == null) {
            return false;
        }

        if("Hive".equalsIgnoreCase(datasource.getDatasourceType()))throw new ServiceException("Hive原版本禁止原地修复，请创建新批次并复评");

        DbQueryProperty dbQueryProperty = new DbQueryProperty(
                datasource.getDatasourceType(),
                datasource.getIp(),
                datasource.getPort(),
                datasource.getDatasourceConfig()
        );

        DbQuery dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
        try {
            if(!dbQuery.valid()){
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        Map<String, Object> updatedData = dto.getKeyWordData();
        Map<String, Object> oldDataMap = dto.getOldData();
        if (updatedData == null || oldDataMap == null || oldDataMap.isEmpty()) {
            return false;
        }

        // Get the jsonData field in oldData (Map<String, Object>)
        Object jsonDataObj = oldDataMap.get("jsonData");
        if (!(jsonDataObj instanceof Map)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> whereData = (Map<String, Object>) jsonDataObj;

        // Table name (prerequisite: whitelist verification has been done)
        String fullTable = buildFullTableName(dbQueryProperty,dto.getTableName());

// // SET clause
//        StringBuilder setClause = new StringBuilder();
//        for (Map.Entry<String, Object> entry : updatedData.entrySet()) {
//            if (setClause.length() > 0) setClause.append(", ");
//            setClause.append("`").append(entry.getKey()).append("` = ")
//                    .append(toSqlLiteral(entry.getValue()));
//        }
//
// // WHERE clause (null -> IS NULL; collection -> IN(...))
//        StringBuilder whereClause = new StringBuilder();
//        for (Map.Entry<String, Object> entry : whereData.entrySet()) {
//            if (whereClause.length() > 0) whereClause.append(" AND ");
//            whereClause.append("`").append(entry.getKey()).append("` ");
//            Object v = entry.getValue();
//            if (v == null) {
//                whereClause.append("IS NULL");
//            } else if (v instanceof Collection) {
//                Collection<?> col = (Collection<?>) v;
//                if (col.isEmpty()) {
//                    whereClause.append("IN (NULL)");
//                } else {
//                    whereClause.append("IN (")
// // if toSqlLiteral is static: use lambda to avoid this:: error
//                            .append(col.stream().map(o -> toSqlLiteral(o))
//                                    .collect(Collectors.joining(", ")))
//                            .append(")");
//                }
//            } else {
//                whereClause.append("= ").append(toSqlLiteral(v));
//            }
//        }
//
//        if (setClause.length() == 0 || whereClause.length() == 0) return false;

        String oq = openQuote( dbQueryProperty.getDbType()), cq = closeQuote( dbQueryProperty.getDbType());

        String setClause = updatedData.entrySet().stream()
                .map(e -> oq + e.getKey() + cq + " = " + toSqlLiteral(e.getValue(),  dbQueryProperty.getDbType()))
                .collect(Collectors.joining(", "));

        String whereClause = whereData.entrySet().stream()
                .map(e -> buildWhereAtom(oq, cq, e.getKey(), e.getValue(),  dbQueryProperty.getDbType()))
                .collect(Collectors.joining(" AND "));

        String sql = String.format("UPDATE %s SET %s WHERE %s", fullTable, setClause, whereClause);

        try {
            int rows = dbQuery.update(sql);
            return rows > 0;
        } catch (Exception e) {
            return false;
        }

    }
    private static String toSqlLiteral(Object v) {
        if (v == null) return "NULL";

        if (v instanceof Number) {
            if (v instanceof BigDecimal) {
                return ((BigDecimal) v).toPlainString();
            }
            return v.toString();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "1" : "0";
        }
        if (v instanceof LocalDate) {
            return "'" + v.toString() + "'";
        }
        if (v instanceof LocalDateTime) {
            return "'" + v.toString().replace('T', ' ') + "'";
        }
        if (v instanceof Date) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return "'" + fmt.format((Date) v) + "'";
        }
        // Defaults to string
        return "'" + escapeSql(v.toString()) + "'";
    }
    private static String escapeSql(String s) {
        // Single quote -> two single quotes; backslash -> double backslash (if NO_BACKSLASH_ESCAPES is turned on, only single quotes can be replaced)
        return s.replace("\\", "\\\\").replace("'", "''");
    }

    /**
     * Get the full table name
     * @param dbQueryProperty data source configuration
     * @return full table name
     */
    public static String buildFullTableName(DbQueryProperty dbQueryProperty, String tableName ) {
        if (DbType.HIVE.getDb().equalsIgnoreCase(dbQueryProperty.getDbType()))
            return tech.qiantong.qdata.common.database.core.HiveTableIdentity.resolve(tableName, dbQueryProperty.getDbName()).qualified();
        String dbType = dbQueryProperty.getDbType();
        String dbName = dbQueryProperty.getDbName();
        String sid = dbQueryProperty.getSid();

        if (StringUtils.equals(DbType.SQL_SERVER.getDb(), dbType)) {
            // SQL Server splicing method: dbName.sid.tableName
            if (StringUtils.isNotBlank(dbName) && StringUtils.isNotBlank(sid)) {
                return dbName + "." + sid + "." + tableName;
            } else if (StringUtils.isNotBlank(dbName)) {
                return dbName + ".." + tableName; // Just spell the database name
            } else {
                return tableName; // Only name
            }
        } else {
            // Default MySQL and others, use backticks
            return StringUtils.isNotBlank(dbName)
                    ? "`" + dbName + "`.`" + tableName + "`"
                    : "`" + tableName + "`";
        }
    }

    private static String buildWhereAtom(String oq, String cq, String key, Object val, String dbType) {
        String col = oq + key + cq;
        if (val == null) return col + " IS NULL";

        if (val.getClass().isArray()) {
            val = Arrays.asList((Object[]) val);
        }
        if (val instanceof Collection<?>) {
            Collection<?> c = (Collection<?>) val;
            if (c.isEmpty()) return col + " IN (NULL)";
            String inVals = c.stream().map(v -> toSqlLiteral(v, dbType)).collect(Collectors.joining(", "));
            return col + " IN (" + inVals + ")";
        }
        return col + " = " + toSqlLiteral(val, dbType);
    }

    // ========== Identifier quotes ==========
    private static String openQuote(String dbType) {
        return StringUtils.equalsIgnoreCase(DbType.SQL_SERVER.getDb(), dbType) ? "[" : "`";
    }
    private static String closeQuote(String dbType) {
        return StringUtils.equalsIgnoreCase(DbType.SQL_SERVER.getDb(), dbType) ? "]" : "`";
    }

    // ========== Convert value to SQL literal ==========
    private static String toSqlLiteral(Object v, String dbType) {
        if (v == null) return "NULL";

        if (v instanceof Number) {
            return (v instanceof BigDecimal) ? ((BigDecimal) v).toPlainString() : String.valueOf(v);
        }
        if (v instanceof Boolean) {
            return (Boolean) v ? "1" : "0";
        }
        if (v instanceof Date) {
            String s = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((Date) v);
            return quoteString(s, dbType, hasNonAscii(s));
        }
        if (v instanceof LocalDate) {
            String s = ((LocalDate) v).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            return quoteString(s, dbType, hasNonAscii(s));
        }
        if (v instanceof LocalDateTime) {
            String s = ((LocalDateTime) v).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return quoteString(s, dbType, hasNonAscii(s));
        }
        if (v instanceof OffsetDateTime) {
            String s = ((OffsetDateTime) v).toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return quoteString(s, dbType, hasNonAscii(s));
        }
        if (v instanceof Timestamp) {
            String s = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((Timestamp) v);
            return quoteString(s, dbType, hasNonAscii(s));
        }

        String s = String.valueOf(v);
        return quoteString(s, dbType, hasNonAscii(s));
    }

    private static String quoteString(String s, String dbType, boolean nonAscii) {
        String esc = s.replace("'", "''");
        if (StringUtils.equalsIgnoreCase(DbType.SQL_SERVER.getDb(), dbType) && nonAscii) {
            return "N'" + esc + "'";
        }
        return "'" + esc + "'";
    }

    private static boolean hasNonAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return true;
        }
        return false;
    }



    @Override
    public String generateDataCheck(QualityRuleQueryReqDTO queryReqDTO) {
        DaDatasourceDO daDatasourceById = iDaDatasourceQualityService.getDaDatasourceById(JSONUtils.convertToLong(queryReqDTO.getDataId()));
        if (daDatasourceById == null) {
            throw new ServiceException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");
        }
        DbQueryProperty dbQueryProperty = new DbQueryProperty(
                daDatasourceById.getDatasourceType(),
                daDatasourceById.getIp(),
                daDatasourceById.getPort(),
                daDatasourceById.getDatasourceConfig()
        );
        DbQuery dbQuery;
        try {
            dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
            if (!dbQuery.valid()) {
                throw new DataQueryException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");
            }
        } catch (Exception e) {
            throw new DataQueryException("db.error.datasource.realtime.error", "Error establishing real-time datasource connection!");
        }

        CharacterValidationGenerator characterValidationGenerator = new CharacterValidationGenerator();


        QualityRuleEntity rule = new QualityRuleEntity(queryReqDTO);
        rule.setDaDatasourceById(daDatasourceById);
        String checkSql = characterValidationGenerator.generateDataCheckSql(rule, queryReqDTO.getInputValue());
        verifyPreviewSql(dbQueryProperty,queryReqDTO.getTableName(),checkSql);

        // Execute SQL and get the first row and first column (0/1)
        try (Connection conn = dbQuery.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(30);stmt.setMaxRows(1);
            try(ResultSet rs=stmt.executeQuery(checkSql)) {


            if (rs.next()) {
                Object val = rs.getObject(1);
                // Uniformly returns the string "0" / "1"
                return val == null ? "0" : String.valueOf(val);
            }
            return "0";
            }
        } catch (Exception e) {
            throw new DataQueryException("db.error.datasource.realtime.fail", "Failed to establish real-time datasource connection!");
        }
    }
}

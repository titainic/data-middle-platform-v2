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

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.database.exception.DataQueryException;
import tech.qiantong.qdata.quality.repository.CheckErrorDataRepository;
import tech.qiantong.qdata.common.httpClient.HttpTaskLogger;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.quality.controller.qa.vo.DppEvaluateLogSaveReqVO;
import tech.qiantong.qdata.quality.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.quality.dal.dataobject.quality.CheckErrorData;
import tech.qiantong.qdata.quality.dal.dataobject.quality.QualityCheckResult;
import tech.qiantong.qdata.quality.dal.dataobject.quality.QualityRuleEntity;
import tech.qiantong.qdata.quality.service.qa.IDppEvaluateLogService;
import tech.qiantong.qdata.quality.utils.quality.QualitySqlGenerateFactory;
import tech.qiantong.qdata.quality.utils.quality.QualitySqlGenerator;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class RuleExecutorTask implements Callable<QualityCheckResult> {

    private final QualityRuleEntity rule;
    private final String batch;
    private final DbQuery dbQuery;;
    private final QualitySqlGenerateFactory sqlFactory;
    private final CheckErrorDataRepository errorDataRepository;
    private final HttpTaskLogger logger;
    private final IDppEvaluateLogService iDppEvaluateLogService;
    private final java.util.Set<String> allowedHiveTables;
    private final Runnable ownershipCheck;

    public RuleExecutorTask(QualityRuleEntity rule, String batch,
                            DbQuery dbQuery,
                            QualitySqlGenerateFactory sqlFactory,
                            CheckErrorDataRepository errorDataRepository,
                            HttpTaskLogger logger,
                            IDppEvaluateLogService iDppEvaluateLogService) {
        this(rule,batch,dbQuery,sqlFactory,errorDataRepository,logger,iDppEvaluateLogService,java.util.Set.of(),()->{});
    }
    public RuleExecutorTask(QualityRuleEntity rule,String batch,DbQuery dbQuery,QualitySqlGenerateFactory sqlFactory,
                            CheckErrorDataRepository errorDataRepository,HttpTaskLogger logger,IDppEvaluateLogService iDppEvaluateLogService,
                            java.util.Set<String> allowedHiveTables,Runnable ownershipCheck) {
        this.rule = rule;
        this.batch = batch;
        this.dbQuery = dbQuery;
        this.sqlFactory = sqlFactory;
        this.errorDataRepository = errorDataRepository;
        this.logger = logger;
        this.iDppEvaluateLogService = iDppEvaluateLogService;
        this.allowedHiveTables=java.util.Set.copyOf(allowedHiveTables);this.ownershipCheck=ownershipCheck;
    }

    @Override
    public QualityCheckResult call() {
        try {
            DppEvaluateLogSaveReqVO createReqVO = new DppEvaluateLogSaveReqVO(rule);
            logger.log(MessageUtils.messageEn("quality.log.rule.exec.start", rule.getId(), rule.getRuleType()));
            // 1. Generate SQL script (strategy mode)
            logger.log(MessageUtils.messageEn("quality.log.sql.generating"));
            QualitySqlGenerator generator = sqlFactory.getGenerator(rule.getRuleType());
            String checkSql = generator.generateSql(rule);
            String errorSql = generator.generateErrorSql(rule);
            var source=rule.getDaDatasourceById();
            if(source!=null && "Hive".equalsIgnoreCase(source.getDatasourceType())) {
                var property=new DbQueryProperty(source.getDatasourceType(),source.getIp(),source.getPort(),source.getDatasourceConfig());
                var target=tech.qiantong.qdata.common.database.core.HiveTableIdentity.resolve(rule.getTableName(),property.getDbName()).canonical();
                var permitted=allowedHiveTables.isEmpty()?java.util.Set.of(target):allowedHiveTables;
                for(String sql:java.util.List.of(checkSql,errorSql)) {
                    var referenced=tech.qiantong.qdata.common.database.core.HiveReadQuery.tables(sql,property.getDbName());
                    if(!permitted.containsAll(referenced))throw new IllegalArgumentException("质量SQL引用了未声明的检查对象");
                }
            }
            ownershipCheck.run();
            logger.log(MessageUtils.messageEn("quality.log.sql.generated", checkSql, errorSql));

            // 2. Execute SQL
            try (Connection conn = getConn(rule,dbQuery).getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(300);

                logger.log(MessageUtils.messageEn("quality.log.sql.executing"));
                // Query the number of exceptions & total number
                int errorCount = 0;
                int totalCount = 0;
                try (ResultSet rs = stmt.executeQuery(checkSql)) {
                    if (rs.next()) {
//                        errorCount = rs.getInt(1);
//                        totalCount = rs.getInt(2);
                        totalCount = ((Number) rs.getObject("totalCount")).intValue();
                        errorCount = ((Number) rs.getObject("errorCount")).intValue();
                    }
                }
                logger.log(MessageUtils.messageEn("quality.log.sql.executed", errorCount, totalCount));
                // Query exception details
                logger.log(MessageUtils.messageEn("quality.log.error.query"));
                List<JSONObject> errorList = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery(errorSql)) {
                    while (rs.next()) {
                        if(errorList.size()%500==0)ownershipCheck.run();
                        JSONObject row = new JSONObject();
                        for (String col : rule.getShowErrorColumns()) {
                            row.put(col, rs.getObject(col));
                        }
                        errorList.add(row);
                    }
                }

                logger.log(MessageUtils.messageEn("quality.log.error.count", errorList.size()));

                createReqVO.setTotal((long)totalCount);
                createReqVO.setProblemTotal((long)errorCount);
                ownershipCheck.run();
                Long dppEvaluateLog = iDppEvaluateLogService.createDppEvaluateLog(createReqVO);

                // Persist problem details in the platform database
                logger.log(MessageUtils.messageEn("quality.log.storage.writing"));
                List<CheckErrorData> documents = new ArrayList<>();
                for (JSONObject obj : errorList) {
                    CheckErrorData doc = CheckErrorData.builder()
                            .reportId(String.valueOf(dppEvaluateLog))
//                            .reportId(rule.getId())
                            .dataJsonStr(obj.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue))
                            .dataJsonStrOLd(obj.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue))
                            .jsonData(obj)
                            .jsonDataOld(obj)
                            .count(totalCount)
                            .errorCount(errorCount)
                            .time(new Date())
                            .repair(0)
                            .remark("")
                            .build();
                    documents.add(doc);
                }
                ownershipCheck.run();
                errorDataRepository.saveAll(documents);
                logger.log(MessageUtils.messageEn("quality.log.storage.done"));
                // Build returns
                logger.log(MessageUtils.messageEn("quality.log.result.build"));
                return new QualityCheckResult(rule.getId(), batch, errorCount, totalCount);
            }
        } catch (Exception e) {
            logger.log(MessageUtils.messageEn("quality.log.rule.exception", rule.getId(), e.getMessage()));
            e.printStackTrace();
            return new QualityCheckResult(rule.getId(), batch, e.getMessage());
        }
    }

    private DbQuery getConn(QualityRuleEntity rule, DbQuery dbQuery) {







        return dbQuery;
    }

}

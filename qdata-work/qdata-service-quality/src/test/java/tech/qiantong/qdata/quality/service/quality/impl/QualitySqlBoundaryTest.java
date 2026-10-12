package tech.qiantong.qdata.quality.service.quality.impl;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.httpClient.HttpTaskLogger;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.quality.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskEvaluateDO;
import tech.qiantong.qdata.quality.dal.dataobject.quality.QualityRuleEntity;
import tech.qiantong.qdata.quality.repository.CheckErrorDataRepository;
import tech.qiantong.qdata.quality.service.qa.IDppEvaluateLogService;
import tech.qiantong.qdata.quality.utils.quality.QualitySqlGenerateFactory;
import tech.qiantong.qdata.quality.utils.quality.QualitySqlGenerator;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QualitySqlBoundaryTest {
    private void rejectedBeforeConnection(String sql) {
        var definition=new DppQualityTaskEvaluateDO();definition.setId(1L);definition.setTaskId(1L);
        definition.setTableName("dwd_iot_db.water_quality");definition.setEvaColumn("ph_value");definition.setRuleType("ENUM_VALIDATION");definition.setRule("{}");
        var rule=new QualityRuleEntity(definition);var source=new DaDatasourceDO();source.setDatasourceType("Hive");source.setIp("unit.invalid");source.setPort(10000L);source.setDatasourceConfig("{\"dbname\":\"qdata_test\",\"username\":\"hadoop\",\"password\":\"\"}");rule.setDaDatasourceById(source);
        var query=mock(DbQuery.class);var factory=mock(QualitySqlGenerateFactory.class);var generator=mock(QualitySqlGenerator.class);
        var errors=mock(CheckErrorDataRepository.class);var logs=mock(IDppEvaluateLogService.class);
        when(factory.getGenerator(anyString())).thenReturn(generator);when(generator.generateSql(rule)).thenReturn(sql);when(generator.generateErrorSql(rule)).thenReturn("SELECT * FROM dwd_iot_db.water_quality");
        try(var messages=mockStatic(MessageUtils.class)) {
            var result=new RuleExecutorTask(rule,"unit",query,factory,errors,mock(HttpTaskLogger.class),logs,Set.of("dwd_iot_db.water_quality"),()->{}).call();
            assertNotNull(result.getErrorMessage());verifyNoInteractions(query,errors,logs);
        }
    }
    @Test void rulesCannotReadAnUndeclaredObject() {
        rejectedBeforeConnection("SELECT COUNT(*) AS totalCount,0 AS errorCount FROM private_project.records");
    }
    @Test void rulesCannotInvokeReflectionEvenWithAnAllowedTarget() {
        rejectedBeforeConnection("SELECT reflect('unit.class','method') FROM dwd_iot_db.water_quality");
    }
}

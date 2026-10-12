package tech.qiantong.qdata.quality.service.quality.impl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import tech.qiantong.qdata.common.database.*;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.quality.controller.qa.vo.*;
import tech.qiantong.qdata.quality.dal.dataobject.qa.*;
import tech.qiantong.qdata.quality.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.quality.dal.dataobject.quality.QualityCheckResult;
import tech.qiantong.qdata.quality.service.qa.*;
import tech.qiantong.qdata.quality.service.datasource.IDaDatasourceQualityService;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class QualityLifecycleRegressionTest {
    @TempDir Path directory;
    QualityTaskExecutorServiceImpl service;
    IRedisService redis;
    IDppQualityLogService logs;
    DbQuery query;
    ThreadPoolTaskExecutor executor;
    tech.qiantong.qdata.quality.service.quality.QualityExecutionSnapshots snapshots;
    tech.qiantong.qdata.quality.service.quality.QualityExecutionSnapshots.Plan plan;
    tech.qiantong.qdata.redis.service.OwnedRedisLease lease;
    @BeforeEach void setup() {
        service=new QualityTaskExecutorServiceImpl();redis=mock(IRedisService.class);logs=mock(IDppQualityLogService.class);query=mock(DbQuery.class);executor=mock(ThreadPoolTaskExecutor.class);
        var messages=new StaticMessageSource();messages.setUseCodeAsDefaultMessage(true);
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("messageSource",messages);new SpringUtils().postProcessBeanFactory(beans);
        ReflectionTestUtils.invokeMethod(service,"setPrefixUrl",directory.toString());
        ReflectionTestUtils.setField(service,"redisService",redis);ReflectionTestUtils.setField(service,"iDppQualityLogService",logs);ReflectionTestUtils.setField(service,"qualityTaskExecutor",executor);
        when(logs.createDppQualityLog(any())).thenReturn(41L);
        var tasks=mock(IDppQualityTaskService.class);var task=new DppQualityTaskRespVO();task.setId(13L);task.setTaskName("Hive test");when(tasks.getDppQualityTaskById(13L)).thenReturn(task);ReflectionTestUtils.setField(service,"iDppQualityTaskService",tasks);
        var objects=mock(IDppQualityTaskObjService.class);var object=new DppQualityTaskObjDO();object.setId(15L);object.setTaskId(13L);object.setTableName("orders");object.setDatasourceId(9L);when(objects.getDppQualityTaskObjList("13")).thenReturn(List.of(object));ReflectionTestUtils.setField(service,"iDppQualityTaskObjService",objects);
        var sources=mock(IDaDatasourceQualityService.class);var source=new DaDatasourceDO();source.setId(9L);source.setDatasourceType("Hive");source.setIp("localhost");source.setPort(10000L);source.setDatasourceConfig("{\"username\":\"hadoop\",\"password\":\"\",\"dbname\":\"qdata_test\"}");when(sources.getDaDatasourceById(9L)).thenReturn(source);ReflectionTestUtils.setField(service,"iDaDatasourceQualityService",sources);
        var factory=mock(DataSourceFactory.class);when(factory.createDbQuery(any())).thenReturn(query);when(query.valid()).thenReturn(true);ReflectionTestUtils.setField(service,"dataSourceFactory",factory);
        var rules=mock(IDppQualityTaskEvaluateService.class);var rule=new DppQualityTaskEvaluateDO();rule.setId(16L);rule.setTaskId(13L);rule.setObjId(15L);rule.setStatus("1");rule.setTableName("orders");rule.setRule("{}");rule.setEvaColumn("amount");when(rules.getDppQualityTaskEvaluateList(anyList())).thenReturn(List.of(rule));ReflectionTestUtils.setField(service,"iDppQualityTaskEvaluateService",rules);
        snapshots=mock(tech.qiantong.qdata.quality.service.quality.QualityExecutionSnapshots.class);
        plan=tech.qiantong.qdata.quality.service.quality.QualityExecutionSnapshots.freeze(13L,"Hive test",List.of(object),List.of(rule));
        when(snapshots.capture(13L)).thenReturn(plan);ReflectionTestUtils.setField(service,"executionSnapshots",snapshots);
        lease=mock(tech.qiantong.qdata.redis.service.OwnedRedisLease.class);when(lease.isOwner()).thenReturn(true);
        when(redis.tryAcquireLock(anyString(),anyString(),anyLong())).thenReturn(true);
        when(redis.renewLock(anyString(),anyString(),anyLong())).thenReturn(true);
        var column=new DbColumn();column.setColName("amount");when(query.getTableColumns(any(tech.qiantong.qdata.common.database.constants.DbQueryProperty.class),eq("orders"))).thenReturn(List.of(column));
    }
    private void failedAndClosed() {
        verify(logs).updateDppQualityLog(argThat(l->l.getId()==41L && "1".equals(l.getSuccessFlag())));
        verify(redis).set("executeQualityTask-13","3",300);
        verify(query).close();
    }
    @Test void columnLookupFailureCannotLeaveTaskRunning() {
        when(query.getTableColumns(any(tech.qiantong.qdata.common.database.constants.DbQueryProperty.class),eq("orders"))).thenThrow(new IllegalStateException("column access denied"));
        service.executeQualityTask(plan,lease);failedAndClosed();
    }
    @Test void returnedRuleFailureIsNotReportedAsSuccess() {
        try(var construction=mockConstruction(RuleExecutorTask.class,(mock,context)->when(mock.call()).thenReturn(new QualityCheckResult("16","batch","SQL rejected")))) {
            service.executeQualityTask(plan,lease);failedAndClosed();
        }
    }
    @Test void detectedBadRowsStillCountAsSuccessfulExecution() {
        try(var construction=mockConstruction(RuleExecutorTask.class,(mock,context)->when(mock.call()).thenReturn(new QualityCheckResult("16","batch",1,2)))) {
            service.executeQualityTask(plan,lease);
            verify(logs).updateDppQualityLog(argThat(l->"0".equals(l.getSuccessFlag())));
            verify(redis).set("executeQualityTask-13","2",300);verify(query).close();
        }
    }
    @Test void runningTaskIsNotSubmittedAgain() {
        when(redis.tryAcquireLock(anyString(),anyString(),anyLong())).thenReturn(false);
        assertThrows(ServiceException.class,()->service.executeTask("13"));verify(executor,never()).submit(any(Runnable.class));
    }
    @Test void rejectedSubmissionClearsRunningState() {
        when(executor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("full"));
        assertThrows(RejectedExecutionException.class,()->service.executeTask("13"));verify(redis).set("executeQualityTask-13","3",300);
    }
    @Test void missingSnapshotPersistencePreventsAnyHiveQuery() {
        doThrow(new IllegalStateException("Snapshot storage unavailable")).when(snapshots).persist(41L,plan);
        service.executeQualityTask(plan,lease);
        verify(logs).updateDppQualityLog(argThat(log->"1".equals(log.getSuccessFlag())));
        verify(query,never()).getTableColumns(any(tech.qiantong.qdata.common.database.constants.DbQueryProperty.class),anyString());
        verify(lease).close();
    }
    @Test void lostLeaseCannotPublishSuccessOrOverwriteReplacementState() {
        doThrow(new IllegalStateException("Owner changed")).when(lease).requireOwner();when(lease.isOwner()).thenReturn(false);
        service.executeQualityTask(plan,lease);
        verify(logs).updateDppQualityLog(argThat(log->"1".equals(log.getSuccessFlag())));
        verify(redis,never()).set(eq("executeQualityTask-13"),anyString(),anyLong());verify(lease).close();
    }
    @Test void nonHiveQualityDoesNotApplyHiveIdentifierRestrictions() throws Exception {
        var sources=(IDaDatasourceQualityService)ReflectionTestUtils.getField(service,"iDaDatasourceQualityService");
        var source=new DaDatasourceDO();source.setId(9L);source.setDatasourceType("MySql");source.setIp("localhost");source.setPort(3306L);source.setDatasourceConfig(com.alibaba.fastjson2.JSON.toJSONString(Map.of("username","unit","password",tech.qiantong.qdata.common.database.utils.AesEncryptUtil.encrypt("unit-test-password"),"dbname","sales-report")));
        when(sources.getDaDatasourceById(9L)).thenReturn(source);
        try(var construction=mockConstruction(RuleExecutorTask.class,(mock,context)->when(mock.call()).thenReturn(new QualityCheckResult("16","batch",0,2)))) {
            service.executeQualityTask(plan,lease);
            verify(logs).updateDppQualityLog(argThat(log->"0".equals(log.getSuccessFlag())));
        }
    }
}

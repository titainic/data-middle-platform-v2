package tech.qiantong.qdata.module.da.service.datasource.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourceSaveReqVO;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.module.da.service.datasource.DatasourceReferenceGuard;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceProjectRelService;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceService;
import tech.qiantong.qdata.module.dpp.api.service.etl.DppEtlTaskService;
import tech.qiantong.qdata.redis.service.IRedisService;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatasourceCacheMetadataTest {
    private static DaDatasourceDO source(boolean enabled) {
        var source = new DaDatasourceDO();
        source.setId(44L);
        source.setDatasourceType("Hive");
        source.setIp("127.0.0.1");
        source.setPort(10000L);
        source.setDatasourceConfig("{\"username\":\"synthetic-user\",\"password\":\"synthetic-password\","
                + "\"keySecret\":\"synthetic-oss-secret\",\"dbname\":\"iot\"}");
        source.setValidFlag(enabled);
        return source;
    }

    private static DaDatasourceServiceImpl service(IRedisService redis, boolean shadowEnabled) {
        var service = new DaDatasourceServiceImpl();
        ReflectionTestUtils.setField(service, "redisService", redis);
        ReflectionTestUtils.setField(service, "secretlessCacheShadowEnabled", shadowEnabled);
        return service;
    }

    private static DaDatasourceSaveReqVO saveRequest(Long id, String password) {
        var request = new DaDatasourceSaveReqVO();
        request.setId(id);
        request.setDatasourceName("synthetic-connection");
        request.setDatasourceType("Hive");
        request.setIp("127.0.0.1");
        request.setPort(10000L);
        request.setDatasourceConfig("{\"username\":\"synthetic-user\",\"password\":\"" + password
                + "\",\"dbname\":\"iot\"}");
        request.setValidFlag(true);
        request.setSkipConnectionValidation(true);
        request.setProjectList(List.of());
        return request;
    }

    private static DaDatasourceServiceImpl saveService(IRedisService redis, DaDatasourceMapper mapper) {
        var service = spy(service(redis, true));
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        ReflectionTestUtils.setField(service, "daDatasourceProjectRelService", mock(IDaDatasourceProjectRelService.class));
        doReturn(0L).when(service).count(any(LambdaQueryWrapper.class));
        doReturn(List.of()).when(service).list(any(LambdaQueryWrapper.class));
        return service;
    }

    @Test void metadataWhitelistNeverSerializesStoredConfigOrUsableCredential() {
        String serialized = DatasourceCacheMetadata.serialize(source(true));
        var metadata = JSON.parseObject(serialized);
        assertEquals(Set.of("schemaVersion", "datasourceId", "datasourceType", "host", "port", "credentialRef"),
                metadata.keySet());
        assertEquals("datasource:44", metadata.getString("credentialRef"));
        assertFalse(serialized.contains("synthetic-password"));
        assertFalse(serialized.contains("synthetic-oss-secret"));
        assertFalse(serialized.contains("datasourceConfig"));
        assertFalse(serialized.contains("username"));
        assertThrows(NullPointerException.class, () -> DatasourceCacheMetadata.serialize(null));
    }

    @Test void shadowFlagDefaultsOffAndPreservesExistingWorkerHash() {
        var redis = DatasourceCacheRedisMock.create();
        var service = new DaDatasourceServiceImpl();
        ReflectionTestUtils.setField(service, "redisService", redis);
        assertEquals(false, ReflectionTestUtils.getField(service, "secretlessCacheShadowEnabled"));
        ReflectionTestUtils.invokeMethod(service, "refreshDatasourceCache", source(true));
        verify(redis).hashPut(eq("datasource"), eq("44"), anyString());
        verify(redis, never()).hashPut(eq(DatasourceCacheMetadata.REDIS_HASH), anyString(), anyString());
        // A prior canary field is removed even when the shadow feature is now off.
        verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
    }

    @Test void optInShadowWritesSecretlessMetadataAlongsideUnchangedWorkerHash() {
        var redis = DatasourceCacheRedisMock.create();
        var service = service(redis, true);
        ReflectionTestUtils.invokeMethod(service, "refreshDatasourceCache", source(true));
        var metadata = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).hashPut(eq(DatasourceCacheMetadata.REDIS_HASH), eq("44"), metadata.capture());
        assertEquals("datasource:44", JSON.parseObject(metadata.getValue()).getString("credentialRef"));
        assertFalse(metadata.getValue().contains("synthetic-password"));
        assertFalse(metadata.getValue().contains("synthetic-oss-secret"));
        verify(redis).hashPut(eq("datasource"), eq("44"), anyString());
    }

    @Test void disabledConnectionRemovesItsShadowFieldWhenCanaryIsEnabled() {
        var redis = DatasourceCacheRedisMock.create();
        var service = service(redis, true);
        ReflectionTestUtils.invokeMethod(service, "refreshDatasourceCache", source(false));
        verify(redis).hashDelete("datasource", "44");
        verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
        verify(redis, never()).hashPut(anyString(), anyString(), anyString());
    }

    @Test void disabledConnectionRemovesPreviouslyWrittenShadowEvenAfterFlagIsOff() {
        var redis = DatasourceCacheRedisMock.create();
        ReflectionTestUtils.invokeMethod(service(redis, false), "refreshDatasourceCache", source(false));
        verify(redis).hashDelete("datasource", "44");
        verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
    }

    @Test void startupWarmupUsesSameSecretlessFormatOnlyWhenOptedIn() {
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        when(mapper.selectList()).thenReturn(List.of(source(true)));
        var service = service(redis, true);
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        service.initDatasourceCache();
        var metadata = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).hashPut(eq(DatasourceCacheMetadata.REDIS_HASH), eq("44"), metadata.capture());
        assertEquals("datasource:44", JSON.parseObject(metadata.getValue()).getString("credentialRef"));
        verify(redis).hashPut(eq("datasource"), eq("44"), anyString());
    }

    @Test void startupWarmupEvictsDisabledShadowEvenWhenFlagWasTurnedOff() {
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        when(mapper.selectList()).thenReturn(List.of(source(false)));
        var service = service(redis, false);
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        service.initDatasourceCache();
        verify(redis).hashDelete("datasource", "44");
        verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
    }

    private static DaDatasourceServiceImpl removableService(IRedisService redis, DaDatasourceMapper mapper,
                                                              DatasourceReferenceGuard guard) {
        var service = service(redis, false);
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        ReflectionTestUtils.setField(service, "datasourceReferences", guard);
        return service;
    }

    @Test void publicRemovalEvictsBothFieldsAfterMetadataDeleteWithoutTouchingOldLookup() {
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        var guard = mock(DatasourceReferenceGuard.class);
        when(mapper.deleteBatchIds(List.of(44L, 45L))).thenReturn(2);
        var service = removableService(redis, mapper, guard);

        assertEquals(2, service.removeDaDatasource(List.of(44L, 45L)));
        var ordered = inOrder(guard, mapper, redis);
        ordered.verify(guard).requireRemovable(List.of(44L, 45L));
        ordered.verify(mapper).deleteBatchIds(List.of(44L, 45L));
        ordered.verify(redis).hashDelete("datasource", "44");
        ordered.verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
        ordered.verify(redis).hashDelete("datasource", "45");
        ordered.verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "45");
        verify(redis, never()).hashDelete(eq("datasource-old"), any());
    }

    @Test void publicDppRemovalEvictsOnlyAfterReferenceAndMetadataChecks() {
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        var guard = mock(DatasourceReferenceGuard.class);
        var tasks = mock(DppEtlTaskService.class);
        var relations = mock(IDaDatasourceProjectRelService.class);
        when(mapper.deleteBatchIds(List.of(44L))).thenReturn(1);
        var service = removableService(redis, mapper, guard);
        ReflectionTestUtils.setField(service, "dppEtlTaskService", tasks);
        ReflectionTestUtils.setField(service, "daDatasourceProjectRelService", relations);

        assertEquals(1, service.removeDaDatasourceDppOrDa(List.of(44L), 0L));
        var ordered = inOrder(guard, tasks, relations, mapper, redis);
        ordered.verify(guard).requireRemovable(List.of(44L));
        ordered.verify(tasks).checkTaskIdInDatasource(List.of(44L), null);
        ordered.verify(relations).remove(any(Wrapper.class));
        ordered.verify(mapper).deleteBatchIds(List.of(44L));
        ordered.verify(redis).hashDelete("datasource", "44");
        ordered.verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
    }

    @Test void failedReferenceGuardLeavesMetadataAndCacheUntouched() {
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        var guard = mock(DatasourceReferenceGuard.class);
        doThrow(new IllegalArgumentException("synthetic-reference")).when(guard).requireRemovable(List.of(44L));
        assertThrows(IllegalArgumentException.class,
                () -> removableService(redis, mapper, guard).removeDaDatasource(List.of(44L)));
        verifyNoInteractions(mapper, redis);
    }

    @Test void statusDisableEvictsBothFieldsAndFailedUpdateDoesNotEvict() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var service = spy(service(redis, false));
        doReturn(true).when(service).update(any(Wrapper.class));
        assertTrue(service.editDatasourceStatus(44L, 0L));
        verify(redis).hashDelete("datasource", "44");
        verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
        verify(redis, never()).hashPut(anyString(), anyString(), anyString());

        clearInvocations(redis);
        doReturn(false).when(service).update(any(Wrapper.class));
        assertFalse(service.editDatasourceStatus(44L, 0L));
        verifyNoInteractions(redis);
        assertThrows(IllegalArgumentException.class, () -> service.editDatasourceStatus(44L, 2L));
        verifyNoInteractions(redis);
    }

    @Test void statusEnableRefreshesWorkerAndShadowFromPersistedRow() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        when(mapper.selectById(44L)).thenReturn(source(true));
        var service = spy(service(redis, true));
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        doReturn(true).when(service).clientTest(44L);
        doReturn(true).when(service).update(any(Wrapper.class));

        assertTrue(service.editDatasourceStatus(44L, 1L));
        verify(mapper).selectById(44L);
        verify(redis).hashPut(eq("datasource"), eq("44"), anyString());
        verify(redis).hashPut(eq(DatasourceCacheMetadata.REDIS_HASH), eq("44"), anyString());
    }

    @Test void transactionalEnablePublishesOnlyAfterCommit() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        when(mapper.selectById(44L)).thenReturn(source(true));
        var service = spy(service(redis, true));
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        doReturn(true).when(service).clientTest(44L);
        doReturn(true).when(service).update(any(Wrapper.class));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(service.editDatasourceStatus(44L, 1L));
            verify(redis).hashDelete("datasource", "44");
            verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
            verify(redis, never()).hashPut(anyString(), anyString(), anyString());
            var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
            callback.afterCommit();
            verify(redis).hashPut(eq("datasource"), eq("44"), anyString());
            verify(redis).hashPut(eq(DatasourceCacheMetadata.REDIS_HASH), eq("44"), anyString());
            verify(mapper, times(2)).selectById(44L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void transactionalEnableRollbackLeavesCacheEvicted() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        when(mapper.selectById(44L)).thenReturn(source(true));
        var service = spy(service(redis, true));
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        doReturn(true).when(service).clientTest(44L);
        doReturn(true).when(service).update(any(Wrapper.class));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(service.editDatasourceStatus(44L, 1L));
            TransactionSynchronizationManager.getSynchronizations().getFirst()
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(redis).hashDelete("datasource", "44");
            verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
            verify(redis, never()).hashPut(anyString(), anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void createPublishesCredentialOnlyAfterCommit() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        when(mapper.insert(any(DaDatasourceDO.class))).thenAnswer(call -> {
            ((DaDatasourceDO) call.getArgument(0)).setId(44L);
            return 1;
        });
        when(mapper.selectById(44L)).thenReturn(source(true));
        var service = saveService(redis, mapper);
        doReturn(source(true)).when(service).getDaDatasourceById(44L);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals(44L, service.createDaDatasource(saveRequest(null, "synthetic-password")));
            verify(redis).hashDelete("datasource", "44");
            verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
            verify(redis, never()).hashPut(anyString(), anyString(), anyString());
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            verify(redis).hashPut(eq("datasource"), eq("44"), anyString());
            verify(redis).hashPut(eq(DatasourceCacheMetadata.REDIS_HASH), eq("44"), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void createRollbackNeverPublishesUncommittedCredential() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        when(mapper.insert(any(DaDatasourceDO.class))).thenAnswer(call -> {
            ((DaDatasourceDO) call.getArgument(0)).setId(44L);
            return 1;
        });
        var service = saveService(redis, mapper);
        doReturn(source(true)).when(service).getDaDatasourceById(44L);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.createDaDatasource(saveRequest(null, "synthetic-uncommitted"));
            TransactionSynchronizationManager.getSynchronizations().getFirst()
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(redis).hashDelete("datasource", "44");
            verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
            verify(redis, never()).hashPut(anyString(), anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void updatePublishesRotatedCredentialOnlyAfterCommit() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        var current = new AtomicReference<>(source(true));
        when(mapper.selectById(44L)).thenAnswer(call -> current.get());
        when(mapper.update(any(DaDatasourceDO.class), any(Wrapper.class))).thenAnswer(call -> {
            current.set(call.getArgument(0));
            return 1;
        });
        var service = saveService(redis, mapper);
        doAnswer(call -> current.get()).when(service).getDaDatasourceById(44L);
        doReturn(true).when(service).clientTest(any(DbQueryProperty.class));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals(1, service.updateDaDatasource(saveRequest(44L, "synthetic-rotated")));
            verify(redis).hashDelete("datasource", "44");
            verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
            verify(redis, never()).hashPut(anyString(), anyString(), anyString());
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            var legacy = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(redis).hashPut(eq("datasource"), eq("44"), legacy.capture());
            assertTrue(legacy.getValue().contains("synthetic-rotated"));
            assertFalse(legacy.getValue().contains("synthetic-password"));
            verify(redis).hashPut(eq(DatasourceCacheMetadata.REDIS_HASH), eq("44"), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void updateRollbackNeverPublishesRotatedCredential() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        var current = new AtomicReference<>(source(true));
        when(mapper.selectById(44L)).thenAnswer(call -> current.get());
        when(mapper.update(any(DaDatasourceDO.class), any(Wrapper.class))).thenAnswer(call -> {
            current.set(call.getArgument(0));
            return 1;
        });
        var service = saveService(redis, mapper);
        doAnswer(call -> current.get()).when(service).getDaDatasourceById(44L);
        doReturn(true).when(service).clientTest(any(DbQueryProperty.class));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals(1, service.updateDaDatasource(saveRequest(44L, "synthetic-uncommitted")));
            TransactionSynchronizationManager.getSynchronizations().getFirst()
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(redis).hashDelete("datasource", "44");
            verify(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
            verify(redis, never()).hashPut(anyString(), anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void concurrentStaleKeepCannotPublishAfterWinningRotation() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        var current = new AtomicReference<>(source(true));
        var staleRead = new CountDownLatch(1);
        var rotated = new CountDownLatch(1);
        when(mapper.selectById(44L)).thenAnswer(call -> {
            if (Thread.currentThread().getName().equals("stale-cache-editor")) staleRead.countDown();
            return current.get();
        });
        when(mapper.update(any(DaDatasourceDO.class), any(Wrapper.class))).thenAnswer(call -> {
            if (Thread.currentThread().getName().equals("stale-cache-editor")) {
                assertTrue(rotated.await(5, TimeUnit.SECONDS));
                return 0; // SQL CAS rejects the old configuration.
            }
            current.set(call.getArgument(0));
            rotated.countDown();
            return 1;
        });
        var service = saveService(redis, mapper);
        doAnswer(call -> current.get()).when(service).getDaDatasourceById(44L);
        doReturn(true).when(service).clientTest(any(DbQueryProperty.class));
        var staleFailure = new AtomicReference<Throwable>();
        var winningFailure = new AtomicReference<Throwable>();
        var winningCallback = new AtomicReference<TransactionSynchronization>();
        var stale = new Thread(() -> {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { service.updateDaDatasource(saveRequest(44L, "")); }
            catch (Throwable failure) { staleFailure.set(failure); }
            finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }, "stale-cache-editor");
        var winner = new Thread(() -> {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                service.updateDaDatasource(saveRequest(44L, "synthetic-rotated"));
                winningCallback.set(TransactionSynchronizationManager.getSynchronizations().getFirst());
            } catch (Throwable failure) { winningFailure.set(failure); }
            finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }, "rotating-cache-editor");
        stale.start();
        assertTrue(staleRead.await(5, TimeUnit.SECONDS));
        winner.start();
        stale.join(7000);
        winner.join(7000);
        assertFalse(stale.isAlive());
        assertFalse(winner.isAlive());
        assertInstanceOf(tech.qiantong.qdata.common.exception.ServiceException.class, staleFailure.get());
        assertNull(winningFailure.get());
        assertNotNull(winningCallback.get());
        verify(redis, times(1)).hashDelete("datasource", "44");
        verify(redis, never()).hashPut(anyString(), anyString(), anyString());

        winningCallback.get().afterCommit();
        var legacy = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).hashPut(eq("datasource"), eq("44"), legacy.capture());
        assertTrue(legacy.getValue().contains("synthetic-rotated"));
        assertFalse(legacy.getValue().contains("synthetic-password"));
    }

    @Test void redisFailureEscapesPublicDeleteAndTriggersTransactionalRollback() {
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        var guard = mock(DatasourceReferenceGuard.class);
        when(mapper.deleteBatchIds(List.of(44L))).thenReturn(1);
        doThrow(new IllegalStateException("synthetic-redis-outage"))
                .when(redis).hashDelete("datasource", "44");
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var factory = new ProxyFactory(removableService(redis, mapper, guard));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var publicService = (IDaDatasourceService) factory.getProxy();

        assertThrows(IllegalStateException.class, () -> publicService.removeDaDatasource(List.of(44L)));
        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
        verify(redis, never()).hashDelete(eq(DatasourceCacheMetadata.REDIS_HASH), any());
    }

    @Test void shadowEvictionFailureRollsBackPublicDisableAfterLegacyEviction() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        doThrow(new IllegalStateException("synthetic-shadow-outage"))
                .when(redis).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
        var service = spy(service(redis, false));
        doReturn(true).when(service).update(any(Wrapper.class));
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var publicService = (IDaDatasourceService) factory.getProxy();

        assertThrows(IllegalStateException.class, () -> publicService.editDatasourceStatus(44L, 0L));
        verify(redis).hashDelete("datasource", "44");
        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
    }

    private static TransactionSynchronization stagedEnable(DaDatasourceServiceImpl service) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(service.editDatasourceStatus(44L, 1L));
            return TransactionSynchronizationManager.getSynchronizations().getFirst();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void olderAfterCommitCannotOverwriteNewerCredentialAfterItsRowWasRead() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        var older = source(true);
        var newer = source(true);
        newer.setDatasourceConfig("{\"username\":\"synthetic-user\",\"password\":\"synthetic-rotated\",\"dbname\":\"iot\"}");
        var staleSnapshot = spy(older);
        var oldPayloadStarted = new CountDownLatch(1);
        var releaseOldPayload = new CountDownLatch(1);
        doAnswer(call -> {
            oldPayloadStarted.countDown();
            assertTrue(releaseOldPayload.await(5, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(staleSnapshot).simplify();
        var reads = new AtomicInteger();
        when(mapper.selectById(44L)).thenAnswer(call -> switch (reads.getAndIncrement()) {
            case 0 -> older;         // Older SQL transaction stages its fence.
            case 1 -> newer;         // Newer SQL transaction stages its fence.
            case 2 -> staleSnapshot; // Older callback read the old committed row.
            default -> newer;       // Newer callback reads the new committed row.
        });
        var service = spy(service(redis, true));
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        doReturn(true).when(service).clientTest(44L);
        doReturn(true).when(service).update(any(Wrapper.class));

        var oldCallback = stagedEnable(service);
        var newCallback = stagedEnable(service);
        var oldFailure = new AtomicReference<Throwable>();
        var oldThread = new Thread(() -> {
            try { oldCallback.afterCommit(); }
            catch (Throwable error) { oldFailure.set(error); }
        }, "old-datasource-publisher");
        oldThread.start();
        try {
            assertTrue(oldPayloadStarted.await(5, TimeUnit.SECONDS));
            newCallback.afterCommit();
        } finally {
            releaseOldPayload.countDown();
        }
        oldThread.join(7000);
        assertFalse(oldThread.isAlive());
        assertNull(oldFailure.get());
        var published = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis, times(1)).hashPut(eq("datasource"), eq("44"), published.capture());
        assertTrue(published.getValue().contains("synthetic-rotated"));
        assertFalse(published.getValue().contains("synthetic-password"));
        verify(redis, times(2)).publishDatasourceIfCurrent(eq("44"), anyString(), anyString(), anyString());
    }

    @Test void rollbackFencePreventsStartupWarmupFromRestoringOldCredential() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var redis = DatasourceCacheRedisMock.create();
        var mapper = mock(DaDatasourceMapper.class);
        when(mapper.selectById(44L)).thenReturn(source(true));
        when(mapper.selectList()).thenReturn(List.of(source(true)));
        var service = spy(service(redis, true));
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        doReturn(true).when(service).clientTest(44L);
        doReturn(true).when(service).update(any(Wrapper.class));

        stagedEnable(service).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        service.initDatasourceCache();
        verify(redis).publishDatasourceIfCurrent(eq("44"), isNull(), anyString(), anyString());
        verify(redis, never()).hashPut(eq("datasource"), eq("44"), anyString());
    }

    @Test void pendingFenceWithInjectedLegacyHashIsEvictedOnBothStartupPaths() {
        var redis = DatasourceCacheRedisMock.create();
        redis.fenceAndEvictDatasource("44", "synthetic-pending");
        redis.hashPut("datasource", "44", "synthetic-stale-worker");
        redis.hashPut(DatasourceCacheMetadata.REDIS_HASH, "44", "synthetic-stale-shadow");
        assertFalse(redis.publishDatasourceIfCurrent("44", null,
                "synthetic-current-worker", "synthetic-current-shadow"));
        verify(redis, times(2)).hashDelete("datasource", "44");
        verify(redis, times(2)).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
        redis.hashPut("datasource", "44", "synthetic-stale-worker");
        assertFalse(redis.evictDatasourceIfUnfenced("44"));
        verify(redis, times(3)).hashDelete("datasource", "44");
        verify(redis, times(3)).hashDelete(DatasourceCacheMetadata.REDIS_HASH, "44");
        verify(redis, never()).hashPut(eq("datasource"), eq("44"), eq("synthetic-current-worker"));
    }
}

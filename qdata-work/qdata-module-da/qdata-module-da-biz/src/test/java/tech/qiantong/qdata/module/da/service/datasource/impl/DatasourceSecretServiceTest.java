package tech.qiantong.qdata.module.da.service.datasource.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourceSaveReqVO;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourceRespVO;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceProjectRelService;
import tech.qiantong.qdata.redis.service.IRedisService;
import tech.qiantong.qdata.common.exception.ServiceException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatasourceSecretServiceTest {
    private static final String OLD_CONFIG = "{\"username\":\"reader\",\"password\":\"synthetic-ciphertext\",\"dbname\":\"iot\"}";

    private static DaDatasourceSaveReqVO request(Long id, String config) {
        var req = new DaDatasourceSaveReqVO();
        req.setId(id);
        req.setDatasourceName("synthetic-connection");
        req.setDatasourceType("Hive");
        req.setIp("127.0.0.1");
        req.setPort(10000L);
        req.setDatasourceConfig(config);
        req.setValidFlag(false);
        req.setProjectList(List.of());
        return req;
    }

    private static DaDatasourceDO stored() {
        var source = new DaDatasourceDO();
        source.setId(44L);
        source.setDatasourceName("synthetic-connection");
        source.setDatasourceType("Hive");
        source.setIp("127.0.0.1");
        source.setPort(10000L);
        source.setDatasourceConfig(OLD_CONFIG);
        source.setValidFlag(false);
        source.setProjectList(List.of());
        return source;
    }

    @Test void createConnectionTestUsesSubmittedCredentialWithoutReadingAnExistingRecord() {
        var service = spy(new DaDatasourceServiceImpl());
        var mapper = mock(DaDatasourceMapper.class);
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        doReturn(true).when(service).clientTest(any(DbQueryProperty.class));
        var req = request(null, OLD_CONFIG);
        assertTrue(service.testDatasourceConnection(req));
        assertEquals("synthetic-ciphertext", JSONUtil.parseObj(req.getDatasourceConfig()).getStr("password"));
        verify(mapper, never()).selectById(any());
    }

    @Test void editConnectionTestKeepsStoredCredentialAfterSanitizedDetailWasLoaded() {
        var service = spy(new DaDatasourceServiceImpl());
        var mapper = mock(DaDatasourceMapper.class);
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        when(mapper.selectById(44L)).thenReturn(stored());
        doReturn(true).when(service).clientTest(any(DbQueryProperty.class));
        var req = request(44L, "{\"username\":\"reader-2\",\"dbname\":\"iot\"}");
        assertTrue(service.testDatasourceConnection(req));
        var merged = JSONUtil.parseObj(req.getDatasourceConfig());
        assertEquals("synthetic-ciphertext", merged.getStr("password"));
        assertEquals("reader-2", merged.getStr("username"));
        verify(mapper).selectById(44L);
    }

    @Test void editPersistsMergedCredentialInsteadOfTheBlankFrontendField() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var service = spy(new DaDatasourceServiceImpl());
        var mapper = mock(DaDatasourceMapper.class);
        var relations = mock(IDaDatasourceProjectRelService.class);
        var redis = DatasourceCacheRedisMock.create();
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        ReflectionTestUtils.setField(service, "daDatasourceProjectRelService", relations);
        ReflectionTestUtils.setField(service, "redisService", redis);
        when(mapper.selectById(44L)).thenReturn(stored());
        doReturn(0L).when(service).count(any(LambdaQueryWrapper.class));
        doReturn(List.of()).when(service).list(any(LambdaQueryWrapper.class));
        doReturn(stored()).when(service).getDaDatasourceById(44L);
        when(mapper.update(any(DaDatasourceDO.class), any(Wrapper.class))).thenReturn(1);
        var req = request(44L, "{\"username\":\"reader\",\"password\":\"\",\"dbname\":\"iot\"}");
        assertEquals(1, service.updateDaDatasource(req));
        assertEquals("synthetic-ciphertext", JSONUtil.parseObj(req.getDatasourceConfig()).getStr("password"));
        var persisted = org.mockito.ArgumentCaptor.forClass(DaDatasourceDO.class);
        var condition = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(persisted.capture(), condition.capture());
        assertEquals("synthetic-ciphertext", JSONUtil.parseObj(persisted.getValue().getDatasourceConfig()).getStr("password"));
        assertTrue(condition.getValue().getSqlSegment().contains("HEX(datasource_config) = HEX"));
        assertDoesNotThrow(() -> CCJSqlParserUtil.parse("UPDATE da_datasource SET datasource_config = ? WHERE " +
                condition.getValue().getSqlSegment().replaceAll("#\\{[^}]+}", "?")));
    }

    @Test void concurrentRotationWinsAndStaleKeepEditFailsClosed() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var service = spy(new DaDatasourceServiceImpl());
        var mapper = mock(DaDatasourceMapper.class);
        var relations = mock(IDaDatasourceProjectRelService.class);
        var redis = DatasourceCacheRedisMock.create();
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        ReflectionTestUtils.setField(service, "daDatasourceProjectRelService", relations);
        ReflectionTestUtils.setField(service, "redisService", redis);
        doReturn(0L).when(service).count(any(LambdaQueryWrapper.class));
        doReturn(List.of()).when(service).list(any(LambdaQueryWrapper.class));
        doReturn(stored()).when(service).getDaDatasourceById(44L);
        var staleRead = new CountDownLatch(1);
        var rotated = new CountDownLatch(1);
        when(mapper.selectById(44L)).thenAnswer(call -> {
            if (Thread.currentThread().getName().equals("stale-editor")) staleRead.countDown();
            return stored();
        });
        when(mapper.update(any(DaDatasourceDO.class), any(Wrapper.class))).thenAnswer(call -> {
            if (Thread.currentThread().getName().equals("stale-editor")) {
                assertTrue(rotated.await(5, TimeUnit.SECONDS));
                return 0; // SQL CAS observes that the ciphertext changed.
            }
            rotated.countDown();
            return 1;
        });
        var staleFailure = new AtomicReference<Throwable>();
        var rotationFailure = new AtomicReference<Throwable>();
        var stale = new Thread(() -> {
            try { service.updateDaDatasource(request(44L, "{\"username\":\"reader\",\"dbname\":\"iot\"}")); }
            catch (Throwable error) { staleFailure.set(error); }
        }, "stale-editor");
        var rotation = new Thread(() -> {
            try { service.updateDaDatasource(request(44L, "{\"username\":\"reader\",\"password\":\"synthetic-rotated\",\"dbname\":\"iot\"}")); }
            catch (Throwable error) { rotationFailure.set(error); }
        }, "rotating-editor");
        stale.start();
        assertTrue(staleRead.await(5, TimeUnit.SECONDS));
        rotation.start();
        stale.join(7000);
        rotation.join(7000);
        assertFalse(stale.isAlive());
        assertFalse(rotation.isAlive());
        assertNull(rotationFailure.get());
        assertInstanceOf(ServiceException.class, staleFailure.get());
        verify(mapper, times(2)).update(any(DaDatasourceDO.class), any(Wrapper.class));
        verify(redis, times(1)).hashDelete("datasource", "44");
    }

    @Test void legacyNullConfigurationUsesNullCompareAndSwap() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var service = spy(new DaDatasourceServiceImpl());
        var mapper = mock(DaDatasourceMapper.class);
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        ReflectionTestUtils.setField(service, "daDatasourceProjectRelService", mock(IDaDatasourceProjectRelService.class));
        ReflectionTestUtils.setField(service, "redisService", DatasourceCacheRedisMock.create());
        var old = stored();
        old.setDatasourceConfig(null);
        when(mapper.selectById(44L)).thenReturn(old);
        doReturn(0L).when(service).count(any(LambdaQueryWrapper.class));
        doReturn(List.of()).when(service).list(any(LambdaQueryWrapper.class));
        doReturn(stored()).when(service).getDaDatasourceById(44L);
        when(mapper.update(any(DaDatasourceDO.class), any(Wrapper.class))).thenReturn(1);
        assertEquals(1, service.updateDaDatasource(request(44L,
                "{\"username\":\"synthetic-reader\",\"dbname\":\"iot\"}")));
        var condition = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(DaDatasourceDO.class), condition.capture());
        assertTrue(condition.getValue().getSqlSegment().toUpperCase().contains("DATASOURCE_CONFIG IS NULL"),
                condition.getValue().getSqlSegment());
        assertDoesNotThrow(() -> CCJSqlParserUtil.parse("UPDATE da_datasource SET datasource_config = ? WHERE " +
                condition.getValue().getSqlSegment().replaceAll("#\\{[^}]+}", "?")));
    }

    @Test void legacyImportCannotBypassScopedCredentialMerge() {
        var service = new DaDatasourceServiceImpl();
        var mapper = mock(DaDatasourceMapper.class);
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        assertThrows(ServiceException.class,
                () -> service.importDaDatasource(List.of(new DaDatasourceRespVO()), true, "synthetic"));
        verifyNoInteractions(mapper);
    }

    @Test void disabledDatasourceCanExplicitlyClearCredentialsWithoutCachingAnInvalidConnection() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "synthetic"), DaDatasourceDO.class);
        var service = spy(new DaDatasourceServiceImpl());
        var mapper = mock(DaDatasourceMapper.class);
        var redis = DatasourceCacheRedisMock.create();
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        ReflectionTestUtils.setField(service, "daDatasourceProjectRelService", mock(IDaDatasourceProjectRelService.class));
        ReflectionTestUtils.setField(service, "redisService", redis);
        when(mapper.selectById(44L)).thenReturn(stored());
        doReturn(0L).when(service).count(any(LambdaQueryWrapper.class));
        doReturn(List.of()).when(service).list(any(LambdaQueryWrapper.class));
        var saved = stored();
        saved.setDatasourceConfig("{\"username\":\"reader\",\"dbname\":\"iot\"}");
        doReturn(saved).when(service).getDaDatasourceById(44L);
        when(mapper.update(any(DaDatasourceDO.class), any(Wrapper.class))).thenReturn(1);
        var req = request(44L, "{\"username\":\"reader\",\"dbname\":\"iot\"}");
        req.setClearSecrets(List.of("password", "keySecret"));
        assertEquals(1, service.updateDaDatasource(req));
        var updated = org.mockito.ArgumentCaptor.forClass(DaDatasourceDO.class);
        verify(mapper).update(updated.capture(), any(Wrapper.class));
        assertFalse(JSONUtil.parseObj(updated.getValue().getDatasourceConfig()).containsKey("password"));
        verify(redis).hashDelete("datasource", "44");
        verify(redis, never()).hashPut(eq("datasource"), eq("44"), any());
    }
}

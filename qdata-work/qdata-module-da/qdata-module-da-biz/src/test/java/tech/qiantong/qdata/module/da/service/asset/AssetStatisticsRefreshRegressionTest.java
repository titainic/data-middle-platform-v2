package tech.qiantong.qdata.module.da.service.asset;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetRespVO;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetSaveReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.service.asset.impl.DaAssetServiceImpl;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline only: metadata, query and persistence boundaries are mocked; no connections are opened. */
class AssetStatisticsRefreshRegressionTest {
    final DaAssetServiceImpl service = spy(new DaAssetServiceImpl());
    final IDaDatasourceService sources = mock(IDaDatasourceService.class);
    final DataSourceFactory factory = mock(DataSourceFactory.class);
    final DbQuery query = mock(DbQuery.class);
    AssetStatisticsRefreshRegressionTest() {
        ReflectionTestUtils.setField(service, "iDaDatasourceService", sources);
        ReflectionTestUtils.setField(service, "dataSourceFactory", factory);
    }
    DaAssetRespVO asset(String type, Long sourceId) {
        var asset = new DaAssetRespVO(); asset.setId(36L); asset.setType(type); asset.setDatasourceId(sourceId);
        asset.setDatabaseName("ods_iot_db"); asset.setTableName("water_quality");
        doReturn(asset).when(service).getDaAssetById(36L);
        return asset;
    }
    void connection(String type) {
        var source = new DaDatasourceDO(); source.setId(3L); source.setDatasourceType(type); source.setIp("127.0.0.1");
        source.setPort(10000L); source.setDatasourceConfig("{\"dbname\":\"default\",\"username\":\"offline-fixture\",\"password\":\"offline-fixture\"}");
        when(sources.getDaDatasourceById(3L)).thenReturn(source);
        when(factory.createDbQuery(any(DbQueryProperty.class))).thenReturn(query);
    }
    @Test void apiAndFileAssetsAreRejectedBeforeDatasourceOrQueryLookup() {
        for (String type : List.of("2", "6")) {
            asset(type, -1L); assertThrows(ServiceException.class, () -> service.startDaAssetDatasourceTask(36L));
        }
        verifyNoInteractions(sources, factory, query); verify(service, never()).updateDaAsset(any());
    }
    @Test void missingAssetAndMissingOrInvalidConnectionCannotBecomeNullPointerErrors() {
        doReturn(null).when(service).getDaAssetById(36L);
        assertThrows(ServiceException.class, () -> service.startDaAssetDatasourceTask(36L));
        for (Long id : new Long[]{null, -1L, 0L}) {
            asset("1", id); assertThrows(ServiceException.class, () -> service.startDaAssetDatasourceTask(36L));
        }
        verifyNoInteractions(sources, factory);
        asset("1", 3L); assertThrows(ServiceException.class, () -> service.startDaAssetDatasourceTask(36L));
        verify(sources).getDaDatasourceById(3L); verifyNoInteractions(factory);
    }
    @Test void failedValidationClosesQueryWithoutPersisting() {
        asset("1", 3L); connection("Hive"); when(query.valid()).thenReturn(false);
        assertThrows(RuntimeException.class, () -> service.startDaAssetDatasourceTask(36L));
        verify(query).close(); verify(service, never()).updateDaAsset(any());
    }
    @Test void validationExceptionClosesQueryWithoutPersisting() {
        asset("1", 3L); connection("Hive"); when(query.valid()).thenThrow(new IllegalStateException("offline failure"));
        assertThrows(IllegalStateException.class, () -> service.startDaAssetDatasourceTask(36L));
        verify(query).close(); verify(service, never()).updateDaAsset(any());
    }
    @Test void countFailureDoesNotPersistAPartialSnapshot() {
        asset("1", 3L); connection("Hive"); when(query.valid()).thenReturn(true);
        when(query.getTableColumns(any(DbQueryProperty.class), anyString())).thenReturn(List.of());
        when(query.countNew(anyString(), anyMap())).thenThrow(new IllegalStateException("offline count failure"));
        assertThrows(IllegalStateException.class, () -> service.startDaAssetDatasourceTask(36L));
        verify(query).close(); verify(service, never()).updateDaAsset(any());
    }
    @Test void hiveSnapshotUsesRegisteredDatabaseInsteadOfConnectionDefault() {
        asset("1", 3L); connection("Hive"); when(query.valid()).thenReturn(true);
        when(query.getTableColumns(any(DbQueryProperty.class), eq("ods_iot_db.water_quality"))).thenReturn(List.of(new DbColumn()));
        when(query.countNew(eq("ods_iot_db.water_quality"), anyMap())).thenReturn(3);
        doReturn(1).when(service).updateDaAsset(any(DaAssetSaveReqVO.class));
        assertEquals(200, service.startDaAssetDatasourceTask(36L).get("code"));
        verify(service).updateDaAsset(argThat(saved -> saved.getDataCount() == 3L && saved.getFieldCount() == 1L
                && saved.getDatabaseName().equals("ods_iot_db") && saved.getId().equals(36L)));
        verify(query).close(); verify(query, never()).countNew(eq("default.water_quality"), anyMap());
    }
    @Test void nonHiveNamesAndRealEmptyCountsRemainCompatible() {
        asset("1", 3L); connection("MySql"); when(query.valid()).thenReturn(true);
        when(query.getTableColumns(any(DbQueryProperty.class), eq("water_quality"))).thenReturn(List.of());
        when(query.countNew(eq("water_quality"), anyMap())).thenReturn(0);
        doReturn(1).when(service).updateDaAsset(any(DaAssetSaveReqVO.class));
        service.startDaAssetDatasourceTask(36L);
        verify(service).updateDaAsset(argThat(saved -> saved.getDataCount() == 0L && saved.getFieldCount() == 0L));
        verify(query).close();
    }
    @Test void previewReturnsFreshTotalWithoutOverwritingTheStoredSnapshot() {
        var stored = asset("1", 3L); stored.setDataCount(0L); connection("Hive");
        var mapper = mock(tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper.class);
        var columns = mock(tech.qiantong.qdata.module.da.dal.mapper.assetColumn.DaAssetColumnMapper.class);
        var cache = mock(tech.qiantong.qdata.common.core.redis.RedisCache.class);
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        ReflectionTestUtils.setField(service, "daAssetColumnMapper", columns);
        ReflectionTestUtils.setField(service, "redisCache", cache);
        var source = sources.getDaDatasourceById(3L);
        when(mapper.selectById(3L)).thenReturn(source);
        var column = new tech.qiantong.qdata.module.da.dal.dataobject.assetColumn.DaAssetColumnDO();
        column.setColumnName("id"); column.setPkFlag("1"); column.setNullableFlag("0");
        when(columns.findByAssetId(36L)).thenReturn(List.of(column));
        doReturn(List.of(DbColumn.builder().colName("id").build())).when(cache).getCacheList(anyString());
        when(query.valid()).thenReturn(true); when(query.generateCheckTableExistsSQL(any(), anyString())).thenReturn(1);
        var rows = List.<java.util.Map<String,Object>>of(java.util.Map.of("id", 1), java.util.Map.of("id", 2), java.util.Map.of("id", 3));
        when(query.queryDbColumnByList(anyList(), eq("ods_iot_db.water_quality"), any(), isNull(), isNull(), eq(0L), eq(10L))).thenReturn(rows);
        when(query.countNew(eq("ods_iot_db.water_quality"), any(DbQueryProperty.class), isNull())).thenReturn(3);
        var request = new cn.hutool.json.JSONObject(); request.set("id", "36"); request.set("pageNum", 1); request.set("pageSize", 10);
        var response = service.getColumnData(request);
        assertEquals(3, response.get("total")); assertEquals(rows, response.get("tableData"));
        assertEquals(0L, stored.getDataCount()); verify(service, never()).updateDaAsset(any()); verify(query).close();
    }

}

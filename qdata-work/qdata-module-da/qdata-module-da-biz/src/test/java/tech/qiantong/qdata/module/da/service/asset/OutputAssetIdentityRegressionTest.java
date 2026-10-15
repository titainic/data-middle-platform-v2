package tech.qiantong.qdata.module.da.service.asset;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.da.controller.admin.asset.DaAssetController;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetPageReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.asset.DaAssetDO;
import tech.qiantong.qdata.module.da.dal.mapper.asset.DaAssetMapper;
import tech.qiantong.qdata.module.da.service.asset.impl.DaAssetServiceImpl;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutputAssetIdentityRegressionTest {
    final DaAssetServiceImpl service = new DaAssetServiceImpl();
    final DaAssetMapper mapper = mock(DaAssetMapper.class);

    OutputAssetIdentityRegressionTest() {
        var configuration = new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "output-asset"), DaAssetDO.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "entityClass", DaAssetDO.class);
    }

    private DaAssetPageReqVO request() {
        var request = new DaAssetPageReqVO(); request.setDatasourceId("1");
        request.setDatabaseName("ods_iot_db"); request.setTableName("air_quality");
        request.setAccessibleAssetIds(List.of(2L, 3L)); return request;
    }

    @Test void lookupBindsExactConnectionDatabaseTableAndAccessibleAssets() {
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<?> query = call.getArgument(0);
            var lambda = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) query;
            String sql = lambda.getSqlSegment();
            assertFalse(sql.toUpperCase().contains("LIKE"));
            for (String field : List.of("datasource_id", "database_name", "table_name", "del_flag", "id")) assertTrue(sql.toLowerCase().contains(field), sql);
            assertTrue(lambda.getParamNameValuePairs().values().containsAll(List.of("1", "ods_iot_db", "air_quality", "0", 2L, 3L)));
            return List.of();
        });
        service.getTablesByDataSourceId(request());
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test void noAccessibleAssetsCannotBecomeAnUnrestrictedQuery() {
        var request = request(); request.setAccessibleAssetIds(List.of());
        assertTrue(service.getTablesByDataSourceId(request).isEmpty()); verifyNoInteractions(mapper);
    }

    @Test void endpointAddsAnExactStringIdentityAndPreservesExistingFields() {
        var controller = new DaAssetController(); var assets = mock(IDaAssetService.class);
        var access = mock(AssetProjectReadAccess.class);
        ReflectionTestUtils.setField(controller, "daAssetService", assets);
        ReflectionTestUtils.setField(controller, "readAccess", access);
        var request = request(); request.setProjectId(7L);
        when(access.ids("da:asset:list")).thenReturn(List.of(9007199254740993L));
        var asset = new DaAssetDO(); asset.setId(9007199254740993L); asset.setTableName("air_quality");
        when(assets.getTablesByDataSourceId(request)).thenReturn(List.of(asset));
        var response = controller.getTablesByDataSourceId(request);
        var row = (Map<?, ?>) ((List<?>) response.get("data")).get(0);
        assertEquals("9007199254740993", row.get("assetId"));
        assertEquals(9007199254740993L, row.get("id")); assertEquals("air_quality", row.get("tableName"));
        assertEquals(List.of(9007199254740993L), request.getAccessibleAssetIds());
        verify(access).requireProject(7L, "da:asset:list");
    }

    @Test void endpointCannotResolveAnAssetInAnUnauthorizedProject() {
        var controller = new DaAssetController(); var assets = mock(IDaAssetService.class);
        var access = mock(AssetProjectReadAccess.class);
        ReflectionTestUtils.setField(controller, "daAssetService", assets);
        ReflectionTestUtils.setField(controller, "readAccess", access);
        var request = request(); request.setProjectId(7L);
        doThrow(new AccessDeniedException("foreign project")).when(access).requireProject(7L, "da:asset:list");
        assertThrows(AccessDeniedException.class, () -> controller.getTablesByDataSourceId(request));
        verifyNoInteractions(assets);
    }
}

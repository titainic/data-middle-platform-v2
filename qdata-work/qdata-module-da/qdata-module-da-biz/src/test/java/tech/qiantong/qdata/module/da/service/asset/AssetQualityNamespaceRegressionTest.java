package tech.qiantong.qdata.module.da.service.asset;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.datasource.DaDatasourceController;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourceTableVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetColumn.DaAssetColumnDO;
import tech.qiantong.qdata.module.da.service.datasource.DatasourceProjectAccess;
import tech.qiantong.qdata.module.da.service.datasource.HiveNamespaceBrowser;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise the existing endpoint and authorization/service contract; no JDBC or HTTP calls. */
class AssetQualityNamespaceRegressionTest {
    @Test void qualifiedHiveAndLegacyNamesReachAccessColumnsAndRulesUnchanged() {
        var sources = mock(IDaDatasourceService.class); var assets = mock(IDaAssetService.class);
        var project = mock(DatasourceProjectAccess.class); var access = mock(DataResourceAccess.class);
        var controller = new DaDatasourceController(sources, assets, mock(HiveNamespaceBrowser.class));
        ReflectionTestUtils.setField(controller, "projectAccess", project);
        ReflectionTestUtils.setField(controller, "resourceAccess", access);
        for (String table : List.of("ods_iot_db.water_quality", "dwd_iot_db.water_quality", "legacy_mysql")) {
            var request = new DaDatasourceTableVO(); request.setId(3L); request.setTableName(table); request.setWithRule(1);
            var column = new DaAssetColumnDO(); column.setColumnName("device_id");
            when(sources.columnsAsAssetColumnList(3L, table)).thenReturn(List.of(column));
            when(assets.listRelRule(3L, table, "1")).thenReturn(List.of());
            var result = controller.columnsAsAssetColumnList(request);
            assertEquals(200, result.getCode()); assertEquals("device_id", result.getData().get(0).getColumnName());
            verify(project).tableScope(3L, table, "query"); verify(access).requireTable(3L, table);
            verify(sources).columnsAsAssetColumnList(3L, table); verify(assets).listRelRule(3L, table, "1");
        }
        verify(sources, never()).columnsAsAssetColumnList(3L, "default.water_quality");
    }
}

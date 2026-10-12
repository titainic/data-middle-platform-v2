package tech.qiantong.qdata.module.dp.service.model;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.dp.controller.admin.model.DpModelMaterializedController;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpMaterializedMethodReqVO;
import tech.qiantong.qdata.module.dp.service.model.impl.DpModelMaterializedServiceImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LegacyHiveMaterializationGuardTest {
    @Test
    void controllerRejectsLegacyHiveBeforeAnyPhysicalOperation() {
        var controller = new DpModelMaterializedController();
        var access = mock(ModelResourceAccess.class);
        var sources = mock(IDaDatasourceApiService.class);
        var runs = mock(IDpModelMaterializedService.class);
        var source = new DaDatasourceRespDTO();
        source.setId(9L);
        source.setDatasourceType("Hive");
        when(sources.getDatasourceById(9L)).thenReturn(source);
        ReflectionTestUtils.setField(controller, "resources", access);
        ReflectionTestUtils.setField(controller, "sources", sources);
        ReflectionTestUtils.setField(controller, "dpModelMaterializedService", runs);
        var request = new DpMaterializedMethodReqVO();
        request.setModelId(List.of(13L));
        request.setDatasourceId(9L);
        request.setReleaseMode("1");

        assertThrows(AccessDeniedException.class, () -> controller.createMaterializedTable(request));
        verify(access).targetDatasource(13L, 9L);
        verifyNoInteractions(runs);
    }

    @Test
    void directServiceCallCannotRebuildLegacyHiveTable() {
        var request = new DpMaterializedMethodReqVO();
        request.setModelId(List.of(13L));
        request.setDatasourceType("hIvE");
        request.setReleaseMode("1");

        assertThrows(IllegalArgumentException.class,
                () -> new DpModelMaterializedServiceImpl().createMaterializedTable(request));
    }
}

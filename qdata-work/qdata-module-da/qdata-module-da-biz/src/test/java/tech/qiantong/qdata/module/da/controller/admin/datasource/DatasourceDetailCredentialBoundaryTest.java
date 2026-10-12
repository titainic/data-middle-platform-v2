package tech.qiantong.qdata.module.da.controller.admin.datasource;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourcePageReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.service.asset.IDaAssetService;
import tech.qiantong.qdata.module.da.service.datasource.DatasourceProjectAccess;
import tech.qiantong.qdata.module.da.service.datasource.HiveNamespaceBrowser;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceService;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatasourceDetailCredentialBoundaryTest {
    @Test void editCapabilityNeverMakesRawConfigurationVisible() {
        var service = mock(IDaDatasourceService.class);
        var access = mock(DatasourceProjectAccess.class);
        var resource = mock(DataResourceAccess.class);
        var controller = new DaDatasourceController(service, mock(IDaAssetService.class), mock(HiveNamespaceBrowser.class));
        ReflectionTestUtils.setField(controller, "projectAccess", access);
        ReflectionTestUtils.setField(controller, "resourceAccess", resource);
        var source = new DaDatasourceDO();
        source.setId(44L);
        source.setDatasourceConfig("{\"username\":\"reader\",\"password\":\"synthetic-ciphertext\",\"keySecret\":\"synthetic-secret\",\"dbname\":\"iot\"}");
        when(service.getDaDatasourceById(44L)).thenReturn(source);
        when(access.capabilities(44L)).thenReturn(Map.of("edit", true, "query", true));
        var result = controller.getInfo(44L).getData();
        assertTrue(result.getDatasourceConfig().contains("reader"));
        assertFalse(result.getDatasourceConfig().contains("password"));
        assertFalse(result.getDatasourceConfig().contains("keySecret"));
        assertFalse(result.getDatasourceConfig().contains("synthetic-ciphertext"));
        verify(access).read(44L, "query");
        verify(resource).requireDatasourceConfiguration(44L);
    }

    @Test void crossProjectDenialStillHappensBeforeLoadingTheConnection() {
        var service = mock(IDaDatasourceService.class);
        var access = mock(DatasourceProjectAccess.class);
        var resource = mock(DataResourceAccess.class);
        var controller = new DaDatasourceController(service, mock(IDaAssetService.class), mock(HiveNamespaceBrowser.class));
        ReflectionTestUtils.setField(controller, "projectAccess", access);
        ReflectionTestUtils.setField(controller, "resourceAccess", resource);
        doThrow(new AccessDeniedException("denied")).when(access).read(44L, "query");
        assertThrows(AccessDeniedException.class, () -> controller.getInfo(44L));
        verify(service, never()).getDaDatasourceById(44L);
    }

    @Test void listAndExportRejectCredentialEqualityProbesBeforeQueryingAnyProject() {
        var service = mock(IDaDatasourceService.class);
        var controller = new DaDatasourceController(service, mock(IDaAssetService.class), mock(HiveNamespaceBrowser.class));
        var guess = new DaDatasourcePageReqVO();
        guess.setDatasourceConfig("{\"password\":\"synthetic-guess\"}");
        assertThrows(IllegalArgumentException.class, () -> controller.list(guess));
        assertThrows(IllegalArgumentException.class, () -> controller.dppList(guess));
        assertThrows(IllegalArgumentException.class, () -> controller.dppNoKafkaList(guess));
        assertThrows(IllegalArgumentException.class, () -> controller.getDaDatasourceList(guess));
        assertThrows(IllegalArgumentException.class, () -> controller.export(new MockHttpServletResponse(), guess));
        verifyNoInteractions(service);
    }
}

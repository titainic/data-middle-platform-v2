package tech.qiantong.qdata.module.mc.service.metadata.dialect;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.*;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McDbDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskScopeDO;
import tech.qiantong.qdata.module.mc.service.task.impl.McTaskServiceImpl;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HiveDatasourceSwitchRegressionTest {
    private DaDatasourceRespDTO current() {
        var d=new DaDatasourceRespDTO();d.setId(9L);d.setIp("new-hive-host");d.setPort(10000L);d.setDatasourceType("Hive");
        d.setDatasourceConfig("{\"dbname\":\"qdata_test\",\"username\":\"hadoop\",\"password\":\"\"}");return d;
    }
    private McDbRespVO stored() {
        var d=new McDbRespVO();d.setId(113L);d.setDatasourceId(9L);d.setDbName("qdata_test");d.setDbType("Hive");
        d.setIp("old-kyuubi-host");d.setPort(10009);d.setDatasourceConfig("{}");return d;
    }
    @Test void connectionChangesPreserveDatabaseIdentity() throws Exception {
        var req=new McDbSaveReqVO();req.setDbName("qdata_test");var stored=stored();
        var service=new McTaskServiceImpl();
        Method match=service.getClass().getDeclaredMethod("findMatchedDb",McDbSaveReqVO.class,DaDatasourceRespDTO.class,List.class);match.setAccessible(true);
        assertSame(stored,match.invoke(service,req,current(),List.of(stored)));
        var other=current();other.setId(10L);assertNull(match.invoke(service,req,other,List.of(stored)));
    }
    @Test void endpointSwitchDoesNotMarkExistingDatabaseDeleted() throws Exception {
        var scope=new McTaskScopeDO();scope.setDbName("qdata_test");
        var service=new McTaskServiceImpl();Method deleted=service.getClass().getDeclaredMethod("findDbsOnlyInResp",List.class,DaDatasourceRespDTO.class,List.class);deleted.setAccessible(true);
        assertTrue(((List<?>)deleted.invoke(service,List.of(scope),current(),List.of(stored()))).isEmpty());
    }
    @Test void metadataUsesCurrentEndpointAndDoesNotFallBackToRetiredEndpoint() throws Exception {
        var api=mock(IDaDatasourceApiService.class);when(api.getDatasourceById(9L)).thenReturn(current());
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("datasources",api);new SpringUtils().postProcessBeanFactory(beans);
        var old=new McDbDO();old.setDatasourceId(9L);old.setIp("old-kyuubi-host");old.setPort(10009);old.setDatasourceConfig("{}");
        Method build=HiveDialect.class.getDeclaredMethod("buildConnectionInfo",McDbDO.class);build.setAccessible(true);
        Object connection=build.invoke(new HiveDialect(),old);Method url=connection.getClass().getDeclaredMethod("getUrl");url.setAccessible(true);
        assertEquals("jdbc:hive2://new-hive-host:10000/qdata_test",url.invoke(connection));
        when(api.getDatasourceById(9L)).thenReturn(null);
        assertInstanceOf(IllegalArgumentException.class,assertThrows(InvocationTargetException.class,()->build.invoke(new HiveDialect(),old)).getCause());
    }
}

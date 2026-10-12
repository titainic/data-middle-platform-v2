package tech.qiantong.qdata.module.dpp.service.etl.task;
import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.utils.TaskConverter;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.exception.ServiceException;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CurrentDatasourceRegressionTest {
    private JSONObject old() {return JSON.parseObject("{\"datasourceId\":9,\"datasourceType\":\"Hive\",\"ip\":\"old-kyuubi-host\",\"port\":10009,\"datasourceConfig\":\"{}\"}");}
    private DppDatasourceResolver resolver() {
        var source=new DaDatasourceRespDTO();source.setId(9L);source.setDatasourceType("Hive");source.setIp("10.131.135.14");source.setPort(10000L);
        source.setDatasourceConfig("{\"dbname\":\"qdata_test\",\"username\":\"hadoop\",\"password\":\"\"}");
        var api=mock(IDaDatasourceApiService.class);when(api.getDatasourceById(9L)).thenReturn(source);return new DppDatasourceResolver(api,mock(DppHiveWritePolicy.class));
    }
    private void inject(Object runner,DppDatasourceResolver resolver) throws Exception {
        var f=runner.getClass().getDeclaredField("datasourceResolver");f.setAccessible(true);f.set(runner,resolver);
    }
    @Test void jdbcRunnerUsesCurrentDatasourceInsteadOfSavedEndpoint() throws Exception {
        var runner=new DataDevelopmentJdbcTaskRunner();inject(runner,resolver());var params=new JSONObject();params.put("datasources",old());
        Method m=runner.getClass().getDeclaredMethod("buildNodeDbQueryProperty",JSONObject.class);m.setAccessible(true);
        DbQueryProperty p=(DbQueryProperty)m.invoke(runner,params);
        assertTrue(p.trainToJdbcUrl().contains("10.131.135.14:10000/qdata_test"));
        assertEquals("old-kyuubi-host",params.getJSONObject("datasources").getString("ip"));
    }
    @Test void sparkPayloadResolvesBothReaderAndWriterAtSubmission() throws Exception {
        var runner=new DppEtlTaskSparkRunner();inject(runner,resolver());
        var params=new JSONObject();params.put("readerDatasource",old());params.put("writerDatasource",old());
        var node=new DppEtlNodeRespVO();node.setCode("123");node.setName("test");node.setComponentType("91");node.setParameters(params.toJSONString());
        var task=new DppEtlTaskDO();task.setId(29L);
        try(var converter=mockStatic(TaskConverter.class)) {
            converter.when(()->TaskConverter.buildEtlTaskParams(anyString(),anyMap(),anyMap(),anyList())).thenAnswer(inv->{
                var parsed=JSON.parseArray((String)inv.getArgument(0)).getJSONObject(0).getJSONObject("taskParams");
                for(String key:List.of("readerDatasource","writerDatasource"))assertEquals("10.131.135.14",parsed.getJSONObject(key).getString("ip"));
                return Map.of("resolved",true);
            });
            Method m=runner.getClass().getDeclaredMethod("buildEtlMainArgs",DppEtlTaskDO.class,List.class);m.setAccessible(true);
            assertEquals(Map.of("resolved",true),m.invoke(runner,task,List.of(node)));
        }
    }
    @Test void deletedDatasourceDoesNotFallBackToOldCredentialsOrEndpoint() {
        var api=mock(IDaDatasourceApiService.class);
        assertThrows(ServiceException.class,()->new DppDatasourceResolver(api,mock(DppHiveWritePolicy.class)).current(old()));
    }
}

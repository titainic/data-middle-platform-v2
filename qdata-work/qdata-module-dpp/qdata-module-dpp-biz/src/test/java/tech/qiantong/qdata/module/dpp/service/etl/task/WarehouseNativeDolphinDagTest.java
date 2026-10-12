package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.api.ds.api.etl.DsNodeGenCodeRespDTO;
import tech.qiantong.qdata.api.ds.api.etl.DsTaskSaveReqDTO;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlNodeService;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlTaskService;
import tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService;
import tech.qiantong.qdata.redis.service.IRedisService;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarehouseNativeDolphinDagTest {
    private static final String BATCH = "b5291988-2bfc-41ee-abf8-55532ef66497";

    @Test void createsDistinctHttpNodesWithTheFrozenModelDependenciesBeforeAnyHiveWork() throws Exception {
        ObjectProvider<IDppEtlTaskService> tasks = mock(ObjectProvider.class);
        ObjectProvider<DataDevelopmentJdbcTaskRunner> runners = mock(ObjectProvider.class);
        ObjectProvider<WarehouseBatchExecution> warehouse = mock(ObjectProvider.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IDsEtlTaskService ds = mock(IDsEtlTaskService.class);
        IDsEtlNodeService codes = mock(IDsEtlNodeService.class);
        IRedisService redis = mock(IRedisService.class);
        WarehouseNativeDolphinService service = new WarehouseNativeDolphinService(tasks, runners, warehouse, jdbc,
                mock(TransactionTemplate.class), ds, codes, redis);
        Field url = service.getClass().getDeclaredField("healthUrl");
        url.setAccessible(true); url.set(service, "http://10.222.1.90:8080/actuator/health");
        IDppEtlTaskService taskService = mock(IDppEtlTaskService.class);
        when(tasks.getObject()).thenReturn(taskService);
        DppEtlTaskDO task = new DppEtlTaskDO(); task.setWarehouseBatchId(BATCH);
        when(taskService.getById(37L)).thenReturn(task);
        when(jdbc.queryForMap(startsWith("SELECT task_id,status FROM dp_warehouse_batch"), eq(BATCH)))
                .thenReturn(Map.of("task_id", 37L, "status", "WAITING_DS"));
        when(jdbc.queryForList(startsWith("SELECT ordinal,node_code,definition_json,dependencies_json FROM dp_warehouse_batch_step"), eq(BATCH)))
                .thenReturn(List.of(
                        Map.of("ordinal", 0, "node_code", "local-0", "definition_json", "{\"layer\":\"ODS\",\"name\":\"water\"}", "dependencies_json", "[]"),
                        Map.of("ordinal", 1, "node_code", "local-1", "definition_json", "{\"layer\":\"DWD\",\"name\":\"clean\"}", "dependencies_json", "[]"),
                        Map.of("ordinal", 2, "node_code", "local-2", "definition_json", "{\"layer\":\"ADS\",\"name\":\"report\"}", "dependencies_json", "[0,1]")));
        when(codes.genCode(185094373543744L)).thenReturn(code(100L), code(101L), code(102L));
        assertThrows(IllegalStateException.class, () -> service.start(37L, BATCH, "185094373543744"));
        var captured = org.mockito.ArgumentCaptor.forClass(DsTaskSaveReqDTO.class);
        verify(ds).createTask(captured.capture(), eq(185094373543744L));
        var definition = JSON.parseArray(captured.getValue().getTaskDefinitionJson());
        var relations = JSON.parseArray(captured.getValue().getTaskRelationJson());
        assertEquals(3, definition.size()); assertEquals(4, relations.size());
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            var node = definition.getJSONObject(ordinal);
            assertEquals("HTTP", node.getString("taskType"));
            var params = node.getJSONObject("taskParams");
            assertEquals("POST", params.getString("httpMethod"));
            assertTrue(params.getString("url").endsWith("/" + BATCH + "/" + ordinal));
            String token = JSON.parseObject(params.getString("httpBody")).getString("token");
            assertNotNull(token);
            assertTrue(token.matches("[0-9a-f]{64}"));
            assertNull(params.get("sql"));
        }
        assertNotEquals(definition.getJSONObject(0).getJSONObject("taskParams").getString("httpBody"),
                definition.getJSONObject(1).getJSONObject("taskParams").getString("httpBody"));
        assertTrue(relations.stream().anyMatch(value -> value.toString().contains("\"preTaskCode\":100") && value.toString().contains("\"postTaskCode\":102")));
        assertTrue(relations.stream().anyMatch(value -> value.toString().contains("\"preTaskCode\":100") && value.toString().contains("\"postTaskCode\":101")));
        assertTrue(relations.stream().anyMatch(value -> value.toString().contains("\"preTaskCode\":101") && value.toString().contains("\"postTaskCode\":102")));
        verifyNoInteractions(redis, runners, warehouse);
    }

    private static DsNodeGenCodeRespDTO code(long value) {
        var result = DsNodeGenCodeRespDTO.builder().data(List.of(value)).build();
        result.setSuccess(true); return result;
    }

    @Test void tokenForAnotherNodeCannotClaimAStepOrReachHive() throws Exception {
        ObjectProvider<IDppEtlTaskService> tasks = mock(ObjectProvider.class);
        ObjectProvider<DataDevelopmentJdbcTaskRunner> runners = mock(ObjectProvider.class);
        ObjectProvider<WarehouseBatchExecution> warehouse = mock(ObjectProvider.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IRedisService redis = mock(IRedisService.class);
        var service = new WarehouseNativeDolphinService(tasks, runners, warehouse, jdbc, mock(TransactionTemplate.class),
                mock(IDsEtlTaskService.class), mock(IDsEtlNodeService.class), redis);
        String firstNodeToken = "a".repeat(64);
        String secondNodeToken = "b".repeat(64);
        String firstNodeHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(firstNodeToken.getBytes(StandardCharsets.UTF_8)));
        when(jdbc.queryForList(startsWith("SELECT * FROM dp_warehouse_ds_native_run"), eq(BATCH)))
                .thenReturn(List.of(Map.of("state", "RUNNING")));
        when(jdbc.queryForList(startsWith("SELECT ds_node_code,token_sha256 FROM dp_warehouse_ds_native_node"), eq(BATCH), eq(0)))
                .thenReturn(List.of(Map.of("ds_node_code", "100", "token_sha256", firstNodeHash)));
        assertThrows(SecurityException.class, () -> service.executeNode(BATCH, 0, secondNodeToken));
        verifyNoInteractions(redis, runners, warehouse, tasks);
    }
}

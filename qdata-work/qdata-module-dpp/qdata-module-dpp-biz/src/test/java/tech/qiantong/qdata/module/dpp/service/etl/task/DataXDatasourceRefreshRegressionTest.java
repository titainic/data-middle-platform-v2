package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.database.utils.AesEncryptUtil;
import tech.qiantong.qdata.common.enums.TaskComponentTypeEnum;
import tech.qiantong.qdata.common.enums.TaskExecutionStatus;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.datax.DataXExecutor;
import tech.qiantong.qdata.datax.DataXResult;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskInstanceDO;
import tech.qiantong.qdata.module.dpp.service.etl.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real runner, datasource resolver and JSON builder; no database, scheduler or DataX process. */
class DataXDatasourceRefreshRegressionTest {
    private static final List<String> COLUMNS = List.of("id", "name", "amt", "created");
    private final DppEtlTaskDataIntegrationRunner runner = new DppEtlTaskDataIntegrationRunner();
    private final IDaDatasourceApiService sources = mock(IDaDatasourceApiService.class);
    private final DppHiveWritePolicy policy = mock(DppHiveWritePolicy.class);
    private final IDppEtlNodeService nodes = mock(IDppEtlNodeService.class);
    private final IDppEtlNodeInstanceService nodeInstances = mock(IDppEtlNodeInstanceService.class);
    private final IDppEtlTaskInstanceService instances = mock(IDppEtlTaskInstanceService.class);
    private final IDppEtlTaskInstanceLogService logs = mock(IDppEtlTaskInstanceLogService.class);
    private final DataXExecutor executor = mock(DataXExecutor.class);
    private final DppEtlTaskDO task = new DppEtlTaskDO();
    private final DppEtlTaskInstanceDO instance = new DppEtlTaskInstanceDO();
    private DppEtlNodeRespVO reader;
    private DppEtlNodeRespVO writer;
    private String submittedJson;
    private final StringBuilder taskLog = new StringBuilder();

    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(runner, "iDppEtlNodeService", nodes);
        ReflectionTestUtils.setField(runner, "dppEtlNodeInstanceService", nodeInstances);
        ReflectionTestUtils.setField(runner, "dppEtlTaskInstanceService", instances);
        ReflectionTestUtils.setField(runner, "iDppEtlTaskInstanceLogService", logs);
        ReflectionTestUtils.setField(runner, "dataXExecutor", executor);
        ReflectionTestUtils.setField(runner, "datasourceResolver", new DppDatasourceResolver(sources, policy));
        task.setId(31L); task.setCode("offline-datax"); task.setName("BIN-46 offline"); task.setType("1");
        // Mirrors task10: draftJson absent, normal table-reader querySql is an empty string.
        instance.setId(101L); instance.setTaskId(31L); instance.setStatus("1");
        reader = node(1L, TaskComponentTypeEnum.DB_READER.getCode(), new JSONObject()
                .fluentPut("readerDatasource", savedSource("datasourceId", 11L, "src_db"))
                .fluentPut("table_name", "t_acc_src").fluentPut("querySql", "")
                .fluentPut("readModeType", "1"));
        writer = node(2L, TaskComponentTypeEnum.DB_WRITER.getCode(), new JSONObject()
                .fluentPut("writerDatasource", savedSource("id", 12L, "dst_db"))
                .fluentPut("target_table_name", "t_bin46_offline").fluentPut("writeModeType", 2)
                .fluentPut("columns", COLUMNS).fluentPut("target_columns", COLUMNS)
                .fluentPut("preSql", "").fluentPut("postSql", ""));
        when(nodes.listNodeByTaskId(31L)).thenReturn(List.of(reader, writer));
        when(nodeInstances.createLocalDataXNodeInstance(any())).thenReturn(true);
        when(instances.updateById(any(DppEtlTaskInstanceDO.class))).thenReturn(true);
        when(sources.getDatasourceById(11L)).thenReturn(source(11L, "current-source", 3307L, "source-user", "fixture-source"));
        when(sources.getDatasourceById(12L)).thenReturn(source(12L, "current-target", 3308L, "target-user", "fixture-target"));
        when(executor.run(anyString(), any())).thenAnswer(call -> {
            submittedJson = call.getArgument(0);
            return new DataXResult(0, null, "offline executor result");
        });
    }

    @Test void redactedReaderAndWriterUseServerCredentialsAndCurrentEndpoints() throws Exception {
        String savedReader = reader.getParameters(), savedWriter = writer.getParameters();
        run();
        JSONObject input = parameter("reader"), output = parameter("writer");
        assertEquals("source-user", input.getString("username"));
        assertEquals("fixture-source", input.getString("password"));
        assertEquals("target-user", output.getString("username"));
        assertEquals("fixture-target", output.getString("password"));
        assertTrue(input.getJSONArray("connection").getJSONObject(0).getJSONArray("jdbcUrl")
                .getString(0).contains("current-source:3307/src_db"));
        assertTrue(output.getJSONArray("connection").getJSONObject(0).getString("jdbcUrl")
                .contains("current-target:3308/dst_db"));
        assertEquals(COLUMNS, input.getJSONArray("column").toJavaList(String.class));
        assertEquals(COLUMNS, output.getJSONArray("column").toJavaList(String.class));
        assertEquals(List.of("t_acc_src"), input.getJSONArray("connection").getJSONObject(0)
                .getJSONArray("table").toJavaList(String.class));
        assertFalse(output.containsKey("preSql")); assertFalse(output.containsKey("postSql"));
        assertEquals(savedReader, reader.getParameters()); assertEquals(savedWriter, writer.getParameters());
        assertEquals("7", instance.getStatus());
        verify(sources).getDatasourceById(11L); verify(sources).getDatasourceById(12L);
        assertLogsMask("fixture-source", "fixture-target");
    }

    @Test void everyExecutionReloadsCredentialsInsteadOfReusingThePreviousJob() throws Exception {
        run();
        when(sources.getDatasourceById(11L)).thenReturn(source(11L, "rotated-source", 3309L, "new-user", "fixture-rotated"));
        run();
        assertEquals("fixture-rotated", parameter("reader").getString("password"));
        assertEquals("new-user", parameter("reader").getString("username"));
        assertTrue(submittedJson.contains("rotated-source:3309"));
        verify(sources, times(2)).getDatasourceById(11L);
        verify(sources, times(2)).getDatasourceById(12L);
        assertLogsMask("fixture-source", "fixture-target", "fixture-rotated");
    }

    @Test void encryptedServerPasswordsStillReachTheBuilderAsUsableCredentials() throws Exception {
        String previous = System.getProperty("qdata.aes.key");
        try {
            System.setProperty("qdata.aes.key", "0123456789abcdef"); // Offline fixture key only.
            String encrypted = AesEncryptUtil.encrypt("fixture-encrypted");
            when(sources.getDatasourceById(11L)).thenReturn(source(11L, "current-source", 3307L, "source-user", encrypted));
            run();
            assertEquals("fixture-encrypted", parameter("reader").getString("password"));
            assertLogsMask("fixture-encrypted", encrypted, "fixture-target");
        } finally {
            if (previous == null) System.clearProperty("qdata.aes.key");
            else System.setProperty("qdata.aes.key", previous);
        }
    }

    @Test void anExplicitEmptyServerPasswordRemainsSupported() throws Exception {
        when(sources.getDatasourceById(11L)).thenReturn(source(11L, "current-source", 3307L, "source-user", ""));
        run();
        assertEquals("", parameter("reader").getString("password"));
    }

    @Test void missingReaderFailsBeforeTheExecutorAndDoesNotUseSavedCredentials() {
        JSONObject parameters = JSON.parseObject(reader.getParameters());
        parameters.getJSONObject("readerDatasource").put("datasourceConfig",
                "{\"username\":\"stale\",\"password\":\"fixture-stale\"}");
        reader.setParameters(parameters.toJSONString());
        when(sources.getDatasourceById(11L)).thenReturn(null);
        assertMissingSourceFailure();
        assertFalse(taskLog.toString().contains("fixture-stale"));
    }

    @Test void missingWriterFailsBeforeTheExecutor() {
        when(sources.getDatasourceById(12L)).thenReturn(null);
        assertMissingSourceFailure();
    }

    @Test void writerPolicyFailureStopsExecutionAndRecordsFailure() {
        doAnswer(call -> {
            JSONObject parameters = call.getArgument(0);
            if (parameters.containsKey("writerDatasource")) throw new IllegalArgumentException("fixture write policy rejection");
            return null;
        }).when(policy).integration(any());
        var failure = assertThrows(IllegalArgumentException.class, this::run);
        assertEquals("fixture write policy rejection", failure.getMessage());
        assertEquals("6", instance.getStatus()); verifyNoInteractions(executor);
    }

    @Test void fileReadersStillResolveOnlyTheDatabaseWriter() throws Exception {
        for (var component : List.of(TaskComponentTypeEnum.CSV_READER, TaskComponentTypeEnum.EXCEL_READER)) {
            reader.setComponentType(component.getCode());
            reader.setParameters(new JSONObject().fluentPut("csvFile", "/offline/fixture.csv")
                    .fluentPut("readerDatasource", new JSONObject().fluentPut("datasourceConfig", "{}"))
                    .fluentPut("startData", 1).fluentPut("startColumn", 1).toJSONString());
            run();
            assertEquals("/offline/fixture.csv", parameter("reader").getString("csvFile"));
            assertFalse(parameter("reader").containsKey("password"));
            assertEquals("fixture-target", parameter("writer").getString("password"));
        }
        verify(sources, never()).getDatasourceById(11L);
        verify(sources, times(2)).getDatasourceById(12L);
    }

    private void assertMissingSourceFailure() {
        ServiceException failure = assertThrows(ServiceException.class, this::run);
        assertEquals("The referenced datasource no longer exists.", failure.getMessage());
        assertEquals("6", instance.getStatus()); assertNotNull(instance.getEndTime());
        verify(instances).updateById(instance);
        verify(nodeInstances, times(2)).updateNodeInstance(argThat(n -> n.getState() == TaskExecutionStatus.FAILURE));
        verifyNoInteractions(executor);
    }

    private void assertLogsMask(String... secrets) {
        assertTrue(taskLog.toString().contains("******"));
        for (String secret : secrets) {
            assertFalse(taskLog.toString().contains(secret));
            verify(logs, never()).saveOrUpdateRealtime(argThat(l -> l.getLogContent().contains(secret)));
        }
    }

    private void run() { runner.startDppEtlTaskDataIntegration(task, instance, taskLog); }

    private JSONObject parameter(String side) {
        return JSON.parseObject(submittedJson).getJSONObject("job").getJSONArray("content")
                .getJSONObject(0).getJSONObject(side).getJSONObject("parameter");
    }

    private JSONObject savedSource(String idKey, long id, String database) {
        return new JSONObject().fluentPut(idKey, id).fluentPut("datasourceType", "MySql")
                .fluentPut("ip", "stale-endpoint").fluentPut("port", 1).fluentPut("dbname", database)
                .fluentPut("datasourceConfig", "{\"username\":\"stale-user\"}");
    }

    private DaDatasourceRespDTO source(long id, String host, long port, String username, String password) {
        var source = new DaDatasourceRespDTO(); source.setId(id); source.setDatasourceName("offline-" + id);
        source.setDatasourceType("MySql"); source.setIp(host); source.setPort(port);
        source.setDatasourceConfig(new JSONObject().fluentPut("username", username).fluentPut("password", password).toJSONString());
        return source;
    }

    private DppEtlNodeRespVO node(long id, String component, JSONObject parameters) {
        var node = new DppEtlNodeRespVO(); node.setId(id); node.setCode("offline-" + id); node.setName("offline-" + id);
        node.setType("DATAX"); node.setComponentType(component); node.setParameters(parameters.toJSONString());
        return node;
    }
}

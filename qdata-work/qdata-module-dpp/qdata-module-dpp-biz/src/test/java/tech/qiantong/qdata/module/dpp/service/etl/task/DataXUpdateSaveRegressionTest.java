package tech.qiantong.qdata.module.dpp.service.etl.task;

import cn.hutool.core.codec.Base64;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.api.ds.api.etl.DsTaskSaveReqDTO;
import tech.qiantong.qdata.api.ds.api.etl.DsTaskSaveRespDTO;
import tech.qiantong.qdata.api.ds.api.etl.ds.ProcessDefinition;
import tech.qiantong.qdata.api.ds.api.etl.ds.ProcessTaskRelation;
import tech.qiantong.qdata.api.ds.api.etl.ds.TaskDefinition;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlTaskService;
import tech.qiantong.qdata.common.constant.ScheduleConstants;
import tech.qiantong.qdata.common.enums.TaskComponentTypeEnum;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.DppEtlTaskController;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.*;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.*;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlTaskMapper;
import tech.qiantong.qdata.module.dpp.service.etl.*;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEtlTaskServiceImpl;
import tech.qiantong.qdata.module.dpp.service.scheduler.DppTaskQuartzService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real existing-task controller/service/converter/components/resolver; persistence and scheduler are offline doubles. */
class DataXUpdateSaveRegressionTest {
    private static final List<String> COLUMNS = List.of("id", "name", "amt", "created");
    private final DppEtlTaskController controller = new DppEtlTaskController();
    private final DppEtlTaskServiceImpl service = new DppEtlTaskServiceImpl();
    private final DevelopmentResourceAccess taskAccess = mock(DevelopmentResourceAccess.class);
    private final DataResourceAccess sourceAccess = mock(DataResourceAccess.class);
    private final IDaDatasourceApiService sources = mock(IDaDatasourceApiService.class);
    private final DppHiveWritePolicy policy = mock(DppHiveWritePolicy.class);
    private final DppEtlTaskMapper tasks = mock(DppEtlTaskMapper.class);
    private final IDppEtlNodeService nodes = mock(IDppEtlNodeService.class);
    private final IDppEtlNodeLogService nodeLogs = mock(IDppEtlNodeLogService.class);
    private final IDppEtlTaskLogService taskLogs = mock(IDppEtlTaskLogService.class);
    private final IDppEtlTaskExtService extensions = mock(IDppEtlTaskExtService.class);
    private final IDppEtlSchedulerService schedules = mock(IDppEtlSchedulerService.class);
    private final IDppEtlTaskNodeRelService relations = mock(IDppEtlTaskNodeRelService.class);
    private final IDppEtlTaskNodeRelLogService relationLogs = mock(IDppEtlTaskNodeRelLogService.class);
    private final IDsEtlTaskService dolphin = mock(IDsEtlTaskService.class);
    private final DppEtlTaskDO existing = new DppEtlTaskDO();
    private final DppEtlNewNodeSaveReqVO request = new DppEtlNewNodeSaveReqVO();
    private final List<DppEtlNodeDO> savedNodes = new ArrayList<>();
    private final List<DppEtlNodeLogSaveReqVO> savedLogs = new ArrayList<>();
    private JSONObject submittedArgs;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "dppEtlTaskService", service);
        ReflectionTestUtils.setField(controller, "resourceAccess", taskAccess);
        ReflectionTestUtils.setField(service, "dppEtlTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "iDppEtlNodeService", nodes);
        ReflectionTestUtils.setField(service, "iDppEtlNodeLogService", nodeLogs);
        ReflectionTestUtils.setField(service, "iDppEtlTaskLogService", taskLogs);
        ReflectionTestUtils.setField(service, "dppEtlTaskExtService", extensions);
        ReflectionTestUtils.setField(service, "iDppEtlSchedulerService", schedules);
        ReflectionTestUtils.setField(service, "iDppEtlTaskNodeRelService", relations);
        ReflectionTestUtils.setField(service, "iDppEtlTaskNodeRelLogService", relationLogs);
        ReflectionTestUtils.setField(service, "dsEtlTaskService", dolphin);
        ReflectionTestUtils.setField(service, "dataResourceAccess", sourceAccess);
        ReflectionTestUtils.setField(service, "datasourceResolver", new DppDatasourceResolver(sources, policy));

        existing.setId(10L); existing.setCode("offline-datax"); existing.setName("DataX existing task");
        existing.setProjectId(1L); existing.setProjectCode("123"); existing.setType("1");
        existing.setVersion(1L); existing.setStatus("0"); existing.setScheduler(ScheduleConstants.QUARTZ);
        when(tasks.selectById(any())).thenReturn(existing);
        when(tasks.updateById(any(DppEtlTaskDO.class))).thenReturn(1);
        var ext = new DppEtlTaskExtDO(); ext.setEtlTaskCode(existing.getCode());
        ext.setEtlNodeCode("9003"); ext.setEtlNodeName("existing integration");
        ext.setEtlNodeId(3L); ext.setEtlRelationId(4L);
        when(extensions.getByTaskId(10L)).thenReturn(ext);
        var schedule = new DppEtlSchedulerDO(); schedule.setId(9L); schedule.setStatus("0");
        when(schedules.getDppEtlSchedulerById(any(DppEtlSchedulerPageReqVO.class))).thenReturn(schedule);
        when(nodeLogs.getByNodeCodeAndVersion(anyString(), anyInt())).thenAnswer(call -> {
            var old = new DppEtlNodeLogDO(); old.setCode(call.getArgument(0));
            old.setParameters("{\"target_table_name\":\"t_bin44_old\"}");
            return old;
        });
        when(nodeLogs.getMaxVersionByNodeCode(anyString())).thenReturn(1);
        when(taskLogs.queryMaxVersionByCode(existing.getCode())).thenReturn(1);
        when(relationLogs.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        when(nodes.update(any(DppEtlNodeDO.class), any())).thenAnswer(call -> {
            savedNodes.add(call.getArgument(0)); return true;
        });
        when(nodeLogs.createDppEtlNodeLogBatch(anyList())).thenAnswer(call -> {
            savedLogs.addAll(call.getArgument(0)); return List.of();
        });
        when(sources.getDatasourceById(2L)).thenReturn(source(2L, "current-reader", "source_db", "source-user", "fixture-source"));
        when(sources.getDatasourceById(3L)).thenReturn(source(3L, "current-writer", "datax_acc_dst", "target-user", "fixture-target"));
        when(dolphin.updateTask(any(), anyString(), anyString())).thenAnswer(call -> {
            DsTaskSaveReqDTO value = call.getArgument(0);
            String encoded = JSON.parseArray(value.getTaskDefinitionJson()).getJSONObject(0)
                    .getJSONObject("taskParams").getString("mainArgs");
            submittedArgs = JSON.parseObject(Base64.decodeStr(encoded));
            var definition = new ProcessDefinition(); definition.setVersion(2);
            var node = new TaskDefinition(); node.setVersion(2);
            var relation = new ProcessTaskRelation(); relation.setId(4L);
            definition.setTaskDefinitionList(List.of(node)); definition.setTaskRelationList(List.of(relation));
            var response = new DsTaskSaveRespDTO(); response.setSuccess(true); response.setData(definition);
            return response;
        });

        request.setId("10"); request.setCode(existing.getCode()); request.setName(existing.getName());
        request.setProjectId(1L); request.setProjectCode(123L); request.setType("1");
        request.setReleaseState("0"); request.setSchedulerState("0"); request.setScheduler(ScheduleConstants.QUARTZ);
        request.setLocations(List.of(Map.of("x", 0, "y", 0)));
        request.setTaskRelationJson("[{\"preTaskCode\":\"0\",\"postTaskCode\":\"reader\",\"preTaskVersion\":1,\"postTaskVersion\":1},"
                + "{\"preTaskCode\":\"reader\",\"postTaskCode\":\"writer\",\"preTaskVersion\":1,\"postTaskVersion\":1}]");
        JSONObject reader = new JSONObject().fluentPut("readerDatasource", savedSource("datasourceId", 2L))
                .fluentPut("columns", COLUMNS).fluentPut("table_name", "t_acc_src")
                .fluentPut("querySql", "").fluentPut("readModeType", "1");
        JSONObject writer = new JSONObject().fluentPut("writerDatasource", savedSource("id", 3L))
                .fluentPut("columns", COLUMNS).fluentPut("target_columns", COLUMNS)
                .fluentPut("target_table_name", "t_bin49_new").fluentPut("writeModeType", 2)
                .fluentPut("preSql", "").fluentPut("postSql", "");
        request.setTaskDefinitionList(JSON.toJSONString(List.of(
                node(21L, "reader", TaskComponentTypeEnum.DB_READER.getCode(), reader),
                node(22L, "writer", TaskComponentTypeEnum.DB_WRITER.getCode(), writer))));
    }

    @Test void redactedExistingTaskUpdateSavesTheNewFourColumnTarget() {
        String original = request.getTaskDefinitionList();
        var response = controller.updateEtlTask(request);
        assertEquals(200, response.getCode());
        assertEquals(original, request.getTaskDefinitionList());
        assertEquals(2, savedNodes.size()); assertEquals(2, savedLogs.size());
        JSONObject output = JSON.parseObject(savedNodes.stream().filter(n -> "writer".equals(n.getCode())).findFirst().orElseThrow().getParameters());
        assertEquals("t_bin49_new", output.getString("target_table_name"));
        assertEquals(COLUMNS, output.getJSONArray("columns").toJavaList(String.class));
        assertEquals(COLUMNS, output.getJSONArray("target_columns").toJavaList(String.class));
        assertEquals(2, output.getIntValue("writeModeType"));
        assertEquals("", output.getString("preSql")); assertEquals("", output.getString("postSql"));
        assertTrue(savedNodes.stream().allMatch(n -> n.getVersion() == 2L));
        verify(relations).createDppEtlTaskNodeRelBatch(argThat(rows -> rows.size() == 2
                && rows.stream().allMatch(r -> r.getPostNodeVersion() == 2)));
        verify(taskAccess).request(request, "edit");
        verify(sourceAccess).requireDatasource(2L); verify(sourceAccess).requireDatasource(3L);
        verify(sources).getDatasourceById(2L); verify(sources).getDatasourceById(3L);
        assertEquals("0", existing.getStatus()); assertEquals("0", request.getSchedulerState());
        verifyNoInteractions(dolphin);
        assertNoServerSecrets(JSON.toJSONString(response));
        assertNoServerSecrets(JSON.toJSONString(savedNodes)); assertNoServerSecrets(JSON.toJSONString(savedLogs));
    }

    @Test void schedulerParametersUseCurrentServerCredentialsAndPreserveMapping() {
        useDolphin(); controller.updateEtlTask(request);
        JSONObject input = submittedArgs.getJSONObject("reader").getJSONObject("parameter");
        JSONObject output = submittedArgs.getJSONObject("writer").getJSONObject("parameter");
        assertEquals("fixture-source", input.getString("password"));
        assertEquals("fixture-target", output.getString("password"));
        assertEquals("source-user", input.getString("username"));
        assertTrue(input.getJSONObject("connection").getString("jdbcUrl").contains("current-reader:3306/source_db"));
        assertEquals("t_bin49_new", output.getJSONObject("connection").getString("table"));
        assertEquals(COLUMNS, output.getJSONArray("target_column").toJavaList(String.class));
        assertFalse(output.containsKey("preSql")); assertFalse(output.containsKey("postSql"));
        assertNoServerSecrets(JSON.toJSONString(savedNodes)); assertNoServerSecrets(JSON.toJSONString(savedLogs));
    }

    @Test void referenceOnlyCanvasSupportsLocalAndSchedulerUpdates() {
        useReferenceOnlySources();
        String original = request.getTaskDefinitionList();
        assertEquals(200, controller.updateEtlTask(request).getCode());
        useDolphin();
        assertEquals(200, controller.updateEtlTask(request).getCode());
        assertEquals(original, request.getTaskDefinitionList());
        assertEquals("source-user", submittedArgs.getJSONObject("reader").getJSONObject("parameter").getString("username"));
        assertEquals("target-user", submittedArgs.getJSONObject("writer").getJSONObject("parameter").getString("username"));
        assertFalse(JSON.toJSONString(savedNodes).contains("datasourceConfig"));
        assertFalse(JSON.toJSONString(savedLogs).contains("datasourceConfig"));
        assertNoServerSecrets(JSON.toJSONString(savedNodes));
    }

    @Test void firstCanvasSavePersistsReferenceOnlyNodesAndTheirRelations() {
        useReferenceOnlySources();
        var quartz = mock(DppTaskQuartzService.class);
        ReflectionTestUtils.setField(service, "dppTaskQuartzService", quartz);
        when(quartz.create(any(), anyString())).thenReturn(90L);
        when(nodes.createDppEtlNodeBatch(anyList())).thenAnswer(call -> {
            List<DppEtlNodeSaveReqVO> values = call.getArgument(0);
            for (var value : values) savedNodes.add(cn.hutool.core.bean.BeanUtil.copyProperties(value, DppEtlNodeDO.class));
            return savedNodes;
        });
        String original = request.getTaskDefinitionList();
        assertEquals(200, controller.createEtlTaskFrontPostposition(request).getCode());
        assertEquals(original, request.getTaskDefinitionList());
        assertEquals(2, savedNodes.size()); assertEquals(2, savedLogs.size());
        for (var node : savedNodes) {
            var parameters = JSON.parseObject(node.getParameters());
            String key = "reader".equals(node.getCode()) ? "readerDatasource" : "writerDatasource";
            assertEquals(1, parameters.getJSONObject(key).size());
            assertNotNull(parameters.getJSONObject(key).getLong("datasourceId"));
        }
        verify(relations).createDppEtlTaskNodeRelBatch(argThat(rows -> rows.size() == 2));
        verify(quartz).create(any(), eq("dppQuartzJob.dataIntegration(%sL)"));
        assertNoServerSecrets(JSON.toJSONString(savedNodes));
        assertFalse(JSON.toJSONString(savedLogs).contains("datasourceConfig"));
    }

    @Test void repeatedUpdatesReloadCredentialsInsteadOfTrustingClientValues() {
        useDolphin(); controller.updateEtlTask(request);
        when(sources.getDatasourceById(2L)).thenReturn(source(2L, "rotated-reader", "source_db", "rotated-user", "fixture-rotated"));
        var definitions = JSON.parseArray(request.getTaskDefinitionList());
        definitions.getJSONObject(0).getJSONObject("taskParams").getJSONObject("readerDatasource")
                .put("datasourceConfig", "{\"username\":\"client-user\",\"password\":\"fixture-client\"}");
        request.setTaskDefinitionList(definitions.toJSONString());
        controller.updateEtlTask(request);
        JSONObject input = submittedArgs.getJSONObject("reader").getJSONObject("parameter");
        assertEquals("rotated-user", input.getString("username")); assertEquals("fixture-rotated", input.getString("password"));
        assertTrue(input.getJSONObject("connection").getString("jdbcUrl").contains("rotated-reader"));
        verify(sources, times(2)).getDatasourceById(2L);
        assertNoServerSecrets(request.getTaskDefinitionList()); assertNoServerSecrets(JSON.toJSONString(savedNodes));
    }

    @Test void missingReaderCannotFallBackToClientCredentials() {
        when(sources.getDatasourceById(2L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> controller.updateEtlTask(request)); assertNoWrites();
    }

    @Test void missingWriterCannotSavePartOfTheTask() {
        when(sources.getDatasourceById(3L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> controller.updateEtlTask(request)); assertNoWrites();
    }

    @Test void unauthorizedReaderStopsBeforeAnyCredentialsAreLoaded() {
        doThrow(new AccessDeniedException("fixture denied")).when(sourceAccess).requireDatasource(2L);
        assertThrows(AccessDeniedException.class, () -> controller.updateEtlTask(request));
        verifyNoInteractions(sources); assertNoWrites();
    }

    @Test void unauthorizedWriterStopsBeforeAnyCredentialsAreLoaded() {
        doThrow(new AccessDeniedException("fixture denied")).when(sourceAccess).requireDatasource(3L);
        assertThrows(AccessDeniedException.class, () -> controller.updateEtlTask(request));
        verifyNoInteractions(sources); assertNoWrites();
    }

    @Test void clientCredentialsWithoutAConnectionIdCannotBypassAuthorization() {
        var definitions = JSON.parseArray(request.getTaskDefinitionList());
        var source = definitions.getJSONObject(0).getJSONObject("taskParams").getJSONObject("readerDatasource");
        source.remove("datasourceId");
        source.put("datasourceConfig", "{\"username\":\"client-user\",\"password\":\"fixture-client\"}");
        request.setTaskDefinitionList(definitions.toJSONString());
        assertThrows(IllegalArgumentException.class, () -> controller.updateEtlTask(request));
        verifyNoInteractions(sources); assertNoWrites();
    }

    @Test void writerPolicyRejectionStopsBeforePersistence() {
        doAnswer(call -> {
            JSONObject params = call.getArgument(0);
            if (params.containsKey("writerDatasource")) throw new IllegalArgumentException("fixture write policy rejection");
            return null;
        }).when(policy).integration(any());
        var failure = assertThrows(IllegalArgumentException.class, () -> controller.updateEtlTask(request));
        assertEquals("fixture write policy rejection", failure.getMessage()); assertNoWrites();
    }

    @Test void taskEditPermissionStillRunsBeforeTheUpdateService() {
        doThrow(new AccessDeniedException("fixture task denied")).when(taskAccess).request(request, "edit");
        assertThrows(AccessDeniedException.class, () -> controller.updateEtlTask(request));
        verifyNoInteractions(tasks, sources, sourceAccess); assertNoWrites();
    }

    @Test void onlineTaskStillRejectsUpdatesBeforeResolvingCredentials() {
        existing.setStatus("1");
        assertThrows(ServiceException.class, () -> controller.updateEtlTask(request));
        verifyNoInteractions(sources, sourceAccess); assertNoWrites();
    }

    @Test void fileReaderPlaceholderDoesNotRequireDatabaseCredentials() {
        var definitions = JSON.parseArray(request.getTaskDefinitionList());
        var reader = definitions.getJSONObject(0);
        reader.put("componentType", TaskComponentTypeEnum.CSV_READER.getCode());
        reader.put("taskParams", new JSONObject().fluentPut("csvFile", "/offline/fixture.csv")
                .fluentPut("readerDatasource", new JSONObject().fluentPut("datasourceId", "").fluentPut("datasourceConfig", "{}")));
        request.setTaskDefinitionList(definitions.toJSONString());
        assertEquals(200, controller.updateEtlTask(request).getCode());
        verify(sources, never()).getDatasourceById(2L); verify(sources).getDatasourceById(3L);
        assertNoServerSecrets(JSON.toJSONString(savedNodes));
    }

    private void useDolphin() { existing.setScheduler(ScheduleConstants.DOLPHINSCHEDULER); request.setScheduler(ScheduleConstants.DOLPHINSCHEDULER); }

    private void useReferenceOnlySources() {
        var definitions = JSON.parseArray(request.getTaskDefinitionList());
        for (int i = 0; i < definitions.size(); i++) {
            var parameters = definitions.getJSONObject(i).getJSONObject("taskParams");
            String key = i == 0 ? "readerDatasource" : "writerDatasource";
            var saved = parameters.getJSONObject(key);
            Long id = saved.getLong("datasourceId");
            if (id == null) id = saved.getLong("id");
            parameters.put(key, new JSONObject().fluentPut("datasourceId", id));
        }
        request.setTaskDefinitionList(definitions.toJSONString());
    }

    private void assertNoWrites() {
        verify(tasks, never()).updateById(any(DppEtlTaskDO.class));
        verifyNoInteractions(nodes, nodeLogs, taskLogs, schedules, extensions, relations, relationLogs, dolphin);
    }

    private void assertNoServerSecrets(String value) {
        for (String secret : List.of("fixture-source", "fixture-target", "fixture-rotated")) assertFalse(value.contains(secret));
    }

    private JSONObject savedSource(String idKey, long id) {
        return new JSONObject().fluentPut(idKey, id).fluentPut("datasourceType", "MySql")
                .fluentPut("ip", "stale-host").fluentPut("port", 1).fluentPut("datasourceConfig", "{}");
    }

    private JSONObject node(long id, String code, String component, JSONObject params) {
        return new JSONObject().fluentPut("id", id).fluentPut("code", code).fluentPut("version", 1)
                .fluentPut("name", code).fluentPut("taskType", "DATAX").fluentPut("componentType", component)
                .fluentPut("taskParams", params);
    }

    private DaDatasourceRespDTO source(long id, String host, String db, String user, String password) {
        var source = new DaDatasourceRespDTO(); source.setId(id); source.setDatasourceType("MySql");
        source.setIp(host); source.setPort(3306L); source.setDatasourceName("fixture-" + id);
        source.setDatasourceConfig(new JSONObject().fluentPut("dbname", db).fluentPut("username", user)
                .fluentPut("password", password).toJSONString());
        return source;
    }
}

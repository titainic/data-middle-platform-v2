package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.api.ds.api.etl.ds.TaskInstance;
import tech.qiantong.qdata.common.enums.TaskComponentTypeEnum;
import tech.qiantong.qdata.common.enums.TaskExecutionStatus;
import tech.qiantong.qdata.datax.DataXExecutor;
import tech.qiantong.qdata.datax.DataXJsonBuilder;
import tech.qiantong.qdata.datax.DataXResult;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.*;
import tech.qiantong.qdata.module.dpp.service.etl.*;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises orchestration without a database, scheduler, or DataX process. */
class LocalDataXExecutionRegressionTest {
    private final DppEtlTaskDataIntegrationRunner runner = new DppEtlTaskDataIntegrationRunner();
    private final IDppEtlNodeService nodes = mock(IDppEtlNodeService.class);
    private final IDppEtlNodeInstanceService nodeInstances = mock(IDppEtlNodeInstanceService.class);
    private final IDppEtlTaskInstanceService instances = mock(IDppEtlTaskInstanceService.class);
    private final IDppEtlTaskInstanceLogService logs = mock(IDppEtlTaskInstanceLogService.class);
    private final DataXExecutor executor = mock(DataXExecutor.class);
    private final DppEtlTaskDO task = new DppEtlTaskDO();
    private final DppEtlTaskInstanceDO instance = new DppEtlTaskInstanceDO();
    private DppEtlNodeRespVO reader;
    private DppEtlNodeRespVO writer;
    private MockedStatic<DataXJsonBuilder> jsonBuilder;

    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(runner, "iDppEtlNodeService", nodes);
        ReflectionTestUtils.setField(runner, "dppEtlNodeInstanceService", nodeInstances);
        ReflectionTestUtils.setField(runner, "dppEtlTaskInstanceService", instances);
        ReflectionTestUtils.setField(runner, "iDppEtlTaskInstanceLogService", logs);
        ReflectionTestUtils.setField(runner, "dataXExecutor", executor);
        ReflectionTestUtils.setField(runner, "datasourceResolver", new DppDatasourceResolver(
                mock(IDaDatasourceApiService.class), mock(DppHiveWritePolicy.class)));
        task.setId(31L); task.setType("1"); task.setCode("offline-task"); task.setName("offline regression");
        instance.setId(101L); instance.setTaskId(task.getId()); instance.setStatus("1");
        reader = node(1L, TaskComponentTypeEnum.DB_READER.getCode());
        writer = node(2L, TaskComponentTypeEnum.DB_WRITER.getCode());
        when(nodes.listNodeByTaskId(31L)).thenReturn(List.of(reader, writer));
        // Model the node_type NOT NULL and remark varchar(512) database constraints.
        when(nodeInstances.createLocalDataXNodeInstance(any())).thenAnswer(call -> {
            DppEtlNodeInstanceDO node = call.getArgument(0);
            if (node.getNodeType() == null) throw new IllegalStateException("node_type is required");
            return true;
        });
        when(instances.updateById(any(DppEtlTaskInstanceDO.class))).thenAnswer(call -> {
            DppEtlTaskInstanceDO saved = call.getArgument(0);
            if (saved.getRemark() != null && saved.getRemark().length() > 512) {
                throw new IllegalStateException("remark exceeds varchar(512)");
            }
            return true;
        });
        when(executor.run(anyString(), any())).thenReturn(new DataXResult(0, null, "3 records synchronized"));
        jsonBuilder = mockStatic(DataXJsonBuilder.class);
        jsonBuilder.when(() -> DataXJsonBuilder.buildJson(anyMap(), anyMap(), anyList())).thenReturn("{}");
    }

    @AfterEach void close() { jsonBuilder.close(); }

    @Test void savedNodeTypeWorksWithoutTaskTypeInParametersAndFinishesTheJob() throws Exception {
        reader.setParameters("{\"taskType\":\"LEGACY\"}");
        runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder());
        var saved = ArgumentCaptor.forClass(DppEtlNodeInstanceDO.class);
        verify(nodeInstances, times(2)).createLocalDataXNodeInstance(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(n -> "DATAX".equals(n.getNodeType())));
        assertTrue(saved.getAllValues().stream().allMatch(n -> "1".equals(n.getTaskType())));
        assertEquals("7", instance.getStatus()); assertNotNull(instance.getEndTime());
        verify(executor).run(eq("{}"), any());
        var updates = ArgumentCaptor.forClass(TaskInstance.class);
        verify(nodeInstances, times(2)).updateNodeInstance(updates.capture());
        assertTrue(updates.getAllValues().stream().allMatch(n -> n.getState() == TaskExecutionStatus.SUCCESS));
    }

    @Test void legacyParameterTypeIsRetainedWhenSavedTypeIsBlank() {
        reader.setType(" "); reader.setParameters("{\"taskType\":\"LEGACY_DATAX\"}");
        runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder());
        var saved = ArgumentCaptor.forClass(DppEtlNodeInstanceDO.class);
        verify(nodeInstances, times(2)).createLocalDataXNodeInstance(saved.capture());
        assertEquals("LEGACY_DATAX", saved.getAllValues().getFirst().getNodeType());
    }

    @Test void oldNodesWithoutEitherTypeUseDataX() {
        reader.setType(null); writer.setType("");
        runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder());
        assertEquals("7", instance.getStatus());
        verify(nodeInstances, times(2)).createLocalDataXNodeInstance(argThat(n -> "DATAX".equals(n.getNodeType())));
    }

    @Test void longFailurePersistsFailureStateAndRetainsFullReasonInLogs() throws Exception {
        String message = "执行失败".repeat(200) + " untruncated-tail";
        var failure = new IllegalStateException(message);
        when(executor.run(anyString(), any())).thenThrow(failure);
        var log = new StringBuilder();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> runner.startDppEtlTaskDataIntegration(task, instance, log)));
        assertEquals("6", instance.getStatus()); assertNotNull(instance.getEndTime());
        assertEquals(message.substring(0, 512), instance.getRemark());
        verify(instances).updateById(instance);
        assertTrue(log.toString().contains(message));
        verify(logs, atLeastOnce()).saveOrUpdateRealtime(argThat(l -> l.getLogContent().contains(message)));
        verify(nodeInstances, times(2)).updateNodeInstance(argThat(n -> n.getState() == TaskExecutionStatus.FAILURE));
    }

    @Test void nullFailureMessageDoesNotPreventFailurePersistence() throws Exception {
        when(executor.run(anyString(), any())).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder()));
        assertEquals("6", instance.getStatus()); assertNull(instance.getRemark());
        verify(instances).updateById(instance);
    }

    @Test void remarkAtTheColumnBoundaryIsKeptIntact() throws Exception {
        String message = "x".repeat(512);
        when(executor.run(anyString(), any())).thenThrow(new IllegalStateException(message));
        assertThrows(IllegalStateException.class, () -> runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder()));
        assertEquals(message, instance.getRemark());
    }

    @Test void nodeCreationFailureStopsBeforeExecutingAndReachesTheScheduler() {
        doReturn(false).when(nodeInstances).createLocalDataXNodeInstance(any());
        assertThrows(RuntimeException.class, () -> runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder()));
        assertEquals("6", instance.getStatus()); assertNotNull(instance.getEndTime());
        verifyNoInteractions(executor);
    }

    @Test void unsuccessfulExitIsRecordedAsFailureAndThrown() throws Exception {
        when(executor.run(anyString(), any())).thenReturn(new DataXResult(1, null, "expected failure"));
        assertThrows(RuntimeException.class, () -> runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder()));
        assertEquals("6", instance.getStatus());
        verify(instances).updateById(instance);
    }

    @Test void statusPersistenceFailurePreservesTheOriginalErrorAndFinalLog() throws Exception {
        var failure = new IllegalStateException("original execution failure");
        when(executor.run(anyString(), any())).thenThrow(failure);
        when(instances.updateById(any(DppEtlTaskInstanceDO.class))).thenReturn(false);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder())));
        assertEquals(1, failure.getSuppressed().length);
        verify(logs, atLeastOnce()).saveOrUpdateRealtime(argThat(l -> l.getLogContent().contains("execution finished")));
    }

    @Test void logOutageCannotHideTheExecutionFailure() throws Exception {
        var failure = new IllegalStateException("expected execution failure");
        when(executor.run(anyString(), any())).thenThrow(failure);
        doThrow(new IllegalStateException("log store unavailable")).when(logs).saveOrUpdateRealtime(any());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder())));
        assertEquals("6", instance.getStatus());
        verify(instances).updateById(instance);
    }

    @Test void checkedExecutionFailureIsPropagatedWithItsCause() throws Exception {
        var failure = new IOException("DataX process could not start");
        when(executor.run(anyString(), any())).thenThrow(failure);
        var thrown = assertThrows(IllegalStateException.class,
                () -> runner.startDppEtlTaskDataIntegration(task, instance, new StringBuilder()));
        assertSame(failure, thrown.getCause()); assertEquals("6", instance.getStatus());
    }

    private DppEtlNodeRespVO node(long id, String componentType) {
        var node = new DppEtlNodeRespVO();
        node.setId(id); node.setCode("node-" + id); node.setName("node-" + id);
        node.setType("DATAX"); node.setParameters("{}"); node.setComponentType(componentType);
        return node;
    }
}

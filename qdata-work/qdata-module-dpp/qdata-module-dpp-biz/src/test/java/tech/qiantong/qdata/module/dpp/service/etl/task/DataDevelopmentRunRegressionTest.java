package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.api.ds.api.etl.ds.TaskInstance;
import tech.qiantong.qdata.common.enums.TaskExecutionStatus;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskInstanceDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlNodeInstanceDO;
import tech.qiantong.qdata.module.dpp.service.etl.*;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataDevelopmentRunRegressionTest {
    private final List<String> executed = new ArrayList<>();
    private final List<String> persistedStates = new ArrayList<>();
    private final List<TaskExecutionStatus> nodeStates = new ArrayList<>();
    private final Map<Long,DppEtlNodeInstanceDO> storedNodes = new HashMap<>();
    private final IRedisService redis = mock(IRedisService.class);
    private final DppPublishedExecutionPlan nodes = mock(DppPublishedExecutionPlan.class);
    private final IDppEtlTaskInstanceService instances = mock(IDppEtlTaskInstanceService.class);
    private final IDppEtlNodeInstanceService nodeInstances = mock(IDppEtlNodeInstanceService.class);
    private final DppEtlTaskDO task = new DppEtlTaskDO();
    private final DppEtlTaskInstanceDO instance = new DppEtlTaskInstanceDO();
    private String failNode;
    private final Map<String,RuntimeException> branchErrors = new HashMap<>();
    private boolean loseLease;
    private final DataDevelopmentJdbcTaskRunner runner = new DataDevelopmentJdbcTaskRunner() {
        @Override JdbcExecuteResult executeDataDevelopmentNodeSql(DppEtlNodeRespVO node, StringBuilder log) {
            executed.add(node.getName());
            if (loseLease) when(redis.renewLock(anyString(), anyString(), anyLong())).thenReturn(false);
            if (branchErrors.containsKey(node.getName())) throw branchErrors.get(node.getName());
            if (node.getName().equals(failNode)) throw new IllegalStateException("expected node failure");
            return new JdbcExecuteResult(0, 1);
        }
    };

    @BeforeEach void setup() throws Exception {
        inject("redisService", redis);
        inject("publishedExecutionPlan", nodes);
        inject("warehouseBatches", mock(tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.class));
        inject("dppEtlTaskInstanceService", instances);
        inject("dppEtlNodeInstanceService", nodeInstances);
        inject("dppEtlNodeInstanceLogService", mock(IDppEtlNodeInstanceLogService.class));
        task.setVersion(1L); task.setId(31L); task.setCode("workflow"); task.setName("read-only regression"); task.setProjectCode("project");
        instance.setId(101L); instance.setStatus("1");
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redis.renewLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(redis.releaseLock(anyString(), anyString())).thenReturn(true);
        when(nodeInstances.createNodeInstance(any())).thenAnswer(call -> {
            TaskInstance node = call.getArgument(0);
            storedNodes.put(node.getId(), DppEtlNodeInstanceDO.builder().id(node.getId()).status("1").build());
            return true;
        });
        when(nodeInstances.getById(any())).thenAnswer(call -> storedNodes.get(call.getArgument(0)));
        when(nodeInstances.getPersistedStatus(anyLong())).thenAnswer(call -> {
            var stored = storedNodes.get(call.getArgument(0)); return stored == null ? null : stored.getStatus();
        });
        when(nodeInstances.updateNodeInstance(any())).thenAnswer(call -> {
            TaskInstance node = call.getArgument(0);
            nodeStates.add(node.getState());
            storedNodes.get(node.getId()).setStatus(String.valueOf(node.getState().getCode()));
            return true;
        });
        when(instances.updateById(any(DppEtlTaskInstanceDO.class))).thenAnswer(call -> {
            persistedStates.add(((DppEtlTaskInstanceDO)call.getArgument(0)).getStatus()); return true;
        });
        when(nodes.load("workflow", "project", 1L)).thenReturn(List.of(node("first"), node("second")));
    }

    @AfterEach void close() { runner.close(); }

    @Test void twoNodesPersistSuccessOnlyAfterBothFinish() {
        runner.run(task, instance, new StringBuilder());
        assertEquals(List.of("first", "second"), executed);
        assertEquals(List.of("7"), persistedStates);
        verify(redis, never()).delete(anyString());
        verify(redis, times(1)).releaseLock(eq("dpp:data-development:run:31"), anyString());
    }

    @Test void firstFailureBlocksFollowingNodeAndCannotBecomeSuccess() {
        failNode = "first";
        StringBuilder log = new StringBuilder();
        assertThrows(IllegalStateException.class, () -> runner.run(task, instance, log));
        assertEquals(List.of("first"), executed);
        assertEquals(List.of("6"), persistedStates);
        assertEquals(List.of(TaskExecutionStatus.FAILURE, TaskExecutionStatus.STOP), nodeStates);
        assertTrue(log.toString().contains("BLOCKED"));
        verify(redis, times(1)).releaseLock(anyString(), anyString());
    }

    @Test void emptyTaskFailsAndReleasesOwnedLease() {
        when(nodes.load("workflow", "project", 1L)).thenReturn(List.of());
        assertThrows(RuntimeException.class, () -> runner.run(task, instance, new StringBuilder()));
        assertEquals(List.of("6"), persistedStates);
        assertTrue(executed.isEmpty());
        verify(redis).releaseLock(anyString(), anyString());
    }

    @Test void competingSubmissionDoesNotDeleteOrReleaseAnotherOwnersLease() {
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);
        assertThrows(RuntimeException.class, () -> runner.run(task, instance, new StringBuilder()));
        assertTrue(executed.isEmpty());
        verify(redis, never()).delete(anyString());
        verify(redis, never()).releaseLock(anyString(), anyString());
    }

    @Test void nodeRecordFailureStopsBeforeAnySql() {
        doReturn(false).when(nodeInstances).createNodeInstance(any());
        assertThrows(IllegalStateException.class, () -> runner.run(task, instance, new StringBuilder()));
        assertTrue(executed.isEmpty());
        assertEquals(List.of("6"), persistedStates);
        verify(redis).releaseLock(anyString(), anyString());
    }

    @Test void lostLeasePreventsSuccessAndFurtherNodes() {
        loseLease = true;
        assertThrows(IllegalStateException.class, () -> runner.run(task, instance, new StringBuilder()));
        assertEquals(List.of("first"), executed);
        assertEquals(List.of("6"), persistedStates);
    }

    @Test void nodeStatusFailureIsNotReportedAsSuccess() {
        doReturn(false).when(nodeInstances).updateNodeInstance(any());
        assertThrows(IllegalStateException.class, () -> runner.run(task, instance, new StringBuilder()));
        assertEquals(List.of("first"), executed);
        assertEquals(List.of("6"), persistedStates);
    }

    @Test void anAcknowledgementWithoutAPersistedNodeNeverExecutesSql() {
        doReturn(true).when(nodeInstances).createNodeInstance(any());
        assertThrows(IllegalStateException.class, () -> runner.run(task, instance, new StringBuilder()));
        assertTrue(executed.isEmpty());
        assertEquals(List.of("6"), persistedStates);
    }

    @Test void anAcknowledgementWithoutASavedResultNeverCompletesTheTask() {
        doReturn(true).when(nodeInstances).updateNodeInstance(any());
        assertThrows(IllegalStateException.class, () -> runner.run(task, instance, new StringBuilder()));
        assertEquals(List.of("first"), executed);
        assertEquals(List.of("6"), persistedStates);
    }
    @Test void failureStatusOutageStillNotifiesWarehouseAndPreservesOriginalFailure() throws Exception {
        var warehouse=mock(tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.class);
        inject("warehouseBatches",warehouse);when(warehouse.batchForTask(31L)).thenReturn("retained-batch");
        failNode="first";when(instances.updateById(any(DppEtlTaskInstanceDO.class))).thenReturn(false);
        var error=assertThrows(IllegalStateException.class,()->runner.run(task,instance,new StringBuilder()));
        assertEquals("expected node failure",error.getMessage());assertTrue(error.getSuppressed().length>0);
        verify(warehouse).fail("retained-batch",31,101,"expected node failure");
        assertEquals(List.of("first"),executed);verify(redis).releaseLock(anyString(),anyString());
    }

    @Test void durablyRecordedManagedFailureContinuesIndependentBranchButNeverCompletesBatch() throws Exception {
        var warehouse=managed();
        branchErrors.put("first",new tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.StepFailed("retained output rejected",new IllegalArgumentException("invalid key")));
        assertThrows(IllegalStateException.class,()->runner.run(task,instance,new StringBuilder()));
        assertEquals(List.of("first","second"),executed);
        assertEquals(List.of(TaskExecutionStatus.FAILURE,TaskExecutionStatus.SUCCESS),nodeStates);
        assertEquals(List.of("6"),persistedStates);
        verify(warehouse,never()).complete(anyString(),anyLong(),anyLong(),anyString());
        verify(warehouse).fail("retained-batch",31,101,"retained output rejected");
    }
    @Test void managedDependencyBlockPersistsStopAndStillVisitsIndependentThirdNode() throws Exception {
        managed();when(nodes.load("workflow","project",1L)).thenReturn(List.of(node("first"),node("blocked"),node("third")));
        branchErrors.put("first",new tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.StepFailed("failed branch",null));
        branchErrors.put("blocked",new tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.DependencyBlocked("upstream failed"));
        StringBuilder log=new StringBuilder();assertThrows(IllegalStateException.class,()->runner.run(task,instance,log));
        assertEquals(List.of("first","blocked","third"),executed);
        assertEquals(List.of(TaskExecutionStatus.FAILURE,TaskExecutionStatus.STOP,TaskExecutionStatus.SUCCESS),nodeStates);
        assertTrue(log.toString().contains("BLOCKED：blocked"));assertEquals(List.of("6"),persistedStates);
    }
    @Test void managedFailureCannotContinueWhenItsNodeStatusCannotBeSaved() throws Exception {
        managed();branchErrors.put("first",new tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.StepFailed("recorded in warehouse",null));
        doReturn(false).when(nodeInstances).updateNodeInstance(any());
        assertThrows(IllegalStateException.class,()->runner.run(task,instance,new StringBuilder()));
        assertEquals(List.of("first"),executed);assertEquals(List.of("6"),persistedStates);
    }
    @Test void managedFailureCannotContinueAfterLeaseLoss() throws Exception {
        managed();loseLease=true;branchErrors.put("first",new tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.StepFailed("recorded in warehouse",null));
        assertThrows(IllegalStateException.class,()->runner.run(task,instance,new StringBuilder()));
        assertEquals(List.of("first"),executed);assertEquals(List.of("6"),persistedStates);
    }
    @Test void ordinaryTaskDoesNotAcceptManagedContinuationSignals() {
        branchErrors.put("first",new tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.StepFailed("unexpected managed signal",null));
        assertThrows(IllegalStateException.class,()->runner.run(task,instance,new StringBuilder()));
        assertEquals(List.of("first"),executed);assertEquals(List.of("6"),persistedStates);
    }
    private tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution managed() throws Exception {
        var warehouse=mock(tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.class);
        inject("warehouseBatches",warehouse);when(warehouse.batchForTask(31L)).thenReturn("retained-batch");return warehouse;
    }

    private DppEtlNodeRespVO node(String name) {
        DppEtlNodeRespVO node = new DppEtlNodeRespVO();
        node.setName(name); node.setCode(name); node.setVersion(1L);
        node.setParameters("{\"componentType\":\"51\",\"taskType\":\"SQL\"}");
        return node;
    }

    private void inject(String name, Object value) throws Exception {
        var field = DataDevelopmentJdbcTaskRunner.class.getDeclaredField(name);
        field.setAccessible(true); field.set(runner, value);
    }
}

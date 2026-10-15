package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.enums.TaskComponentTypeEnum;
import tech.qiantong.qdata.common.enums.WorkflowExecutionStatus;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.*;
import tech.qiantong.qdata.module.dpp.service.etl.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SparkPreflightRegressionTest {
    final DppEtlTaskSparkRunner runner = new DppEtlTaskSparkRunner();
    final IDppEtlNodeService nodes = mock(IDppEtlNodeService.class);
    final DppDatasourceResolver resolver = mock(DppDatasourceResolver.class);
    final SparkPreflightFailureRecorder recorder = mock(SparkPreflightFailureRecorder.class);
    final DppEtlTaskDO task = new DppEtlTaskDO();
    final IllegalArgumentException failure = new IllegalArgumentException("ODS模型资产身份不匹配");

    SparkPreflightRegressionTest() {
        task.setId(13L); task.setType("1"); task.setCode("1234");
        ReflectionTestUtils.setField(runner, "iDppEtlNodeService", nodes);
        ReflectionTestUtils.setField(runner, "datasourceResolver", resolver);
        ReflectionTestUtils.setField(runner, "preflightFailures", recorder);
        var reader = new DppEtlNodeRespVO(); reader.setComponentType(TaskComponentTypeEnum.CSV_READER.getCode());
        reader.setParameters("{}");
        var writer = new DppEtlNodeRespVO(); writer.setComponentType(TaskComponentTypeEnum.DB_WRITER.getCode());
        writer.setParameters("{}");
        when(nodes.listNodeByTaskId(13L)).thenReturn(List.of(reader, writer));
        when(resolver.refreshNodeParameters(anyMap())).thenThrow(failure);
    }

    @Test void interactivePreflightDoesNotLaunchOrCreateAnInstance() {
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> runner.validateTask(task)));
        verifyNoInteractions(recorder);
    }

    @Test void scheduledPreflightFailureIsRecordedWithoutStartingADriver() {
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> runner.startSparkEtlTask(task, new StringBuilder())));
        verify(recorder).record(task, failure);
    }

    @Test void instancePersistenceFailureDoesNotHideTheOriginalReason() {
        var persistence = new IllegalStateException("storage unavailable");
        doThrow(persistence).when(recorder).record(task, failure);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> runner.startSparkEtlTask(task, new StringBuilder())));
        assertArrayEquals(new Throwable[]{persistence}, failure.getSuppressed());
    }

    @Test void missingNodesAlsoLeaveAFailedAttempt() {
        when(nodes.listNodeByTaskId(13L)).thenReturn(List.of());
        assertThrows(RuntimeException.class, () -> runner.startSparkEtlTask(task, new StringBuilder()));
        verify(recorder).record(eq(task), any(RuntimeException.class));
    }

    @Test void recorderPersistsFinalFailureAndItsLogWithTheSameIdentity() {
        var instances = mock(IDppEtlTaskInstanceService.class);
        var logs = mock(IDppEtlTaskInstanceLogService.class);
        when(instances.save(any())).thenReturn(true);
        when(logs.saveOrUpdateRealtime(any())).thenReturn(true);
        task.setProjectId(7L); task.setProjectCode("9007199254740993"); task.setName("air");
        new SparkPreflightFailureRecorder(instances, logs).record(task, failure);
        var instance = org.mockito.ArgumentCaptor.forClass(DppEtlTaskInstanceDO.class);
        var log = org.mockito.ArgumentCaptor.forClass(DppEtlTaskInstanceLogDO.class);
        verify(instances).save(instance.capture()); verify(logs).saveOrUpdateRealtime(log.capture());
        assertEquals(String.valueOf(WorkflowExecutionStatus.FAILURE.getCode()), instance.getValue().getStatus());
        assertEquals(13L, instance.getValue().getTaskId()); assertEquals(7L, instance.getValue().getProjectId());
        assertEquals("9007199254740993", instance.getValue().getProjectCode());
        assertNotNull(instance.getValue().getEndTime());
        assertEquals(instance.getValue().getId(), log.getValue().getTaskInstanceId());
        assertTrue(log.getValue().getLogContent().contains(failure.getMessage()));
    }

    @Test void rejectedInstanceWriteIsReportedAndDoesNotWriteAnOrphanLog() {
        var instances = mock(IDppEtlTaskInstanceService.class);
        var logs = mock(IDppEtlTaskInstanceLogService.class);
        assertThrows(IllegalStateException.class, () -> new SparkPreflightFailureRecorder(instances, logs).record(task, failure));
        verifyNoInteractions(logs);
    }

    @Test void rejectedLogWriteCannotClaimARecordedFailure() {
        var instances = mock(IDppEtlTaskInstanceService.class);
        var logs = mock(IDppEtlTaskInstanceLogService.class);
        when(instances.save(any())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> new SparkPreflightFailureRecorder(instances, logs).record(task, failure));
    }
}

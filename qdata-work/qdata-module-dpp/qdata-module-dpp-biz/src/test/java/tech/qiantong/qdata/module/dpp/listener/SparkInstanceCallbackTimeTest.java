package tech.qiantong.qdata.module.dpp.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.api.ds.api.etl.ds.ProcessInstance;
import tech.qiantong.qdata.api.ds.api.etl.ds.TaskInstance;
import tech.qiantong.qdata.common.enums.*;
import tech.qiantong.qdata.module.att.api.project.IAttProjectApi;
import tech.qiantong.qdata.module.dpp.api.etl.dto.DppEtlTaskRespDTO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.*;
import tech.qiantong.qdata.module.dpp.service.etl.*;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEtlTaskInstanceServiceImpl;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEtlNodeInstanceServiceImpl;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SparkInstanceCallbackTimeTest {
    private static final Date START = Date.from(Instant.parse("2026-10-15T12:30:57Z"));
    private static final Date END = Date.from(Instant.parse("2026-10-15T12:31:27Z"));

    private Message message(Object payload) throws Exception {
        // Same standalone Jackson producer as RabbitmqUtils; do not use the
        // Spring web mapper to hide the actual message format.
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryTag(41L);
        return new Message(new ObjectMapper().writeValueAsBytes(payload), properties);
    }

    private Map wireMap(Message message) {
        return (Map) new Jackson2JsonMessageConverter(new ObjectMapper()
                .setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))).fromMessage(message);
    }

    private ProcessInstance process() {
        return ProcessInstance.builder().id(187020347675488L).processDefinitionCode("12")
                .projectCode("1").name("time-boundary").startTime(START).endTime(END)
                .scheduleTime(START).commandStartTime(START).state(WorkflowExecutionStatus.SUCCESS)
                .commandType(CommandType.START_PROCESS).failureStrategy(FailureStrategy.CONTINUE)
                .isSubProcess(Flag.NO).runTimes(1).build();
    }

    private TaskInstance node() {
        return TaskInstance.builder().id(187020347675489L).processInstanceId(187020347675488L)
                .projectCode("1").taskCode("42").taskDefinitionVersion(1).taskType("SPARK")
                .submitTime(START).startTime(START).endTime(END).state(TaskExecutionStatus.SUCCESS)
                .taskInstancePriority(Priority.MEDIUM).build();
    }

    @Test void processInsertAndUpdatePreserveTheInstantThroughThePersistenceObject() throws Exception {
        var service = spy(new DppEtlTaskInstanceServiceImpl());
        var tasks = mock(IDppEtlTaskService.class);
        var projects = mock(IAttProjectApi.class);
        var task = new DppEtlTaskRespDTO();
        task.setId(12L);
        task.setType("1");
        when(tasks.getTaskByTaskCode("12")).thenReturn(task);
        when(projects.getProjectIdByProjectCode("1")).thenReturn(1L);
        ReflectionTestUtils.setField(service, "dppEtlTaskService", tasks);
        ReflectionTestUtils.setField(service, "attProjectApi", projects);
        doReturn(true).when(service).save(any(DppEtlTaskInstanceDO.class));
        doReturn(true).when(service).saveOrUpdate(any(DppEtlTaskInstanceDO.class));
        var old = new DppEtlTaskInstanceDO();
        old.setId(process().getId());
        doReturn(old).when(service).getById(process().getId());
        var listener = new ProcessListener();
        ReflectionTestUtils.setField(listener, "dppEtlTaskInstanceService", service);
        var channel = mock(Channel.class);

        var msg = message(Map.of("type", 1, "instance", process()));
        var raw = wireMap(msg);
        assertEquals("2026-10-15 20:30:57", ((Map<?, ?>) raw.get("instance")).get("startTime"));
        listener.processInstanceUpdate(raw, channel, msg);
        var saved = ArgumentCaptor.forClass(DppEtlTaskInstanceDO.class);
        verify(service).save(saved.capture());
        assertEquals(START, saved.getValue().getStartTime());
        assertEquals(END, saved.getValue().getEndTime());
        assertEquals(START, saved.getValue().getScheduleTime());
        assertEquals("7", saved.getValue().getStatus());
        // MySQL connection uses Asia/Shanghai. Its wall-clock DATETIME must be
        // the same day 20:30, not next-day 04:30. This assertion is offline.
        assertEquals("2026-10-15T20:30:57", saved.getValue().getStartTime().toInstant()
                .atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().toString());

        msg = message(Map.of("type", 2, "instance", process()));
        listener.processInstanceUpdate(wireMap(msg), channel, msg);
        verify(service).saveOrUpdate(saved.capture());
        assertEquals(START, saved.getValue().getStartTime());
        assertEquals(END, saved.getValue().getEndTime());
        verify(channel, times(2)).basicAck(41L, false);
    }

    @Test void nodeInsertAndUpdateKeepSubmitStartAndEndTimes() throws Exception {
        var service = spy(new DppEtlNodeInstanceServiceImpl());
        var logs = mock(IDppEtlNodeLogService.class);
        var projects = mock(IAttProjectApi.class);
        var definition = new DppEtlNodeLogDO();
        definition.setId(42L);
        when(logs.getByNodeCodeAndVersion("42", 1)).thenReturn(definition);
        when(projects.getProjectIdByProjectCode("1")).thenReturn(1L);
        ReflectionTestUtils.setField(service, "dppEtlNodeLogService", logs);
        ReflectionTestUtils.setField(service, "attProjectApi", projects);
        doReturn(true).when(service).save(any(DppEtlNodeInstanceDO.class));
        doReturn(true).when(service).saveOrUpdate(any(DppEtlNodeInstanceDO.class));
        var old = new DppEtlNodeInstanceDO();
        old.setId(node().getId());
        doReturn(old).when(service).getById(node().getId());
        var listener = new TaskListener();
        ReflectionTestUtils.setField(listener, "dppEtlNodeInstanceService", service);
        var channel = mock(Channel.class);
        var msg = message(node());
        listener.taskInstanceInsert(wireMap(msg), channel, msg);
        var saved = ArgumentCaptor.forClass(DppEtlNodeInstanceDO.class);
        verify(service).save(saved.capture());
        assertEquals(START, saved.getValue().getSubmitTime());
        assertEquals(START, saved.getValue().getStartTime());
        assertEquals("7", saved.getValue().getStatus());
        listener.taskInstanceUpdate(wireMap(msg), channel, msg);
        verify(service).saveOrUpdate(saved.capture());
        assertEquals(START, saved.getValue().getStartTime());
        assertEquals(END, saved.getValue().getEndTime());
        verify(channel, times(2)).basicAck(41L, false);
    }

    @Test void runningMessagesKeepNullEndTimeAndFailedMessagesKeepTheirState() throws Exception {
        var service = mock(IDppEtlTaskInstanceService.class);
        when(service.createTaskInstance(any())).thenReturn(true);
        when(service.updateTaskInstance(any())).thenReturn(true);
        var listener = new ProcessListener();
        ReflectionTestUtils.setField(listener, "dppEtlTaskInstanceService", service);
        var process = process();
        process.setEndTime(null);
        process.setState(WorkflowExecutionStatus.RUNNING_EXECUTION);
        var msg = message(Map.of("type", 1, "instance", process));
        listener.processInstanceUpdate(wireMap(msg), mock(Channel.class), msg);
        var captured = ArgumentCaptor.forClass(ProcessInstance.class);
        verify(service).createTaskInstance(captured.capture());
        assertNull(captured.getValue().getEndTime());
        assertEquals(START, captured.getValue().getStartTime());
        process.setEndTime(END);
        process.setState(WorkflowExecutionStatus.FAILURE);
        msg = message(Map.of("type", 2, "instance", process));
        listener.processInstanceUpdate(wireMap(msg), mock(Channel.class), msg);
        verify(service).updateTaskInstance(captured.capture());
        assertEquals(WorkflowExecutionStatus.FAILURE, captured.getValue().getState());
        assertEquals(END, captured.getValue().getEndTime());
    }

    @Test void numericEpochMessagesRemainCompatibleAndKeepMilliseconds() throws Exception {
        var service = mock(IDppEtlNodeInstanceService.class);
        when(service.updateNodeInstance(any())).thenReturn(true);
        var listener = new TaskListener();
        ReflectionTestUtils.setField(listener, "dppEtlNodeInstanceService", service);
        var msg = message(Map.of("id", 1, "startTime", START.getTime() + 123, "endTime", END.getTime() + 456));
        listener.taskInstanceUpdate(wireMap(msg), mock(Channel.class), msg);
        var captured = ArgumentCaptor.forClass(TaskInstance.class);
        verify(service).updateNodeInstance(captured.capture());
        assertEquals(START.getTime() + 123, captured.getValue().getStartTime().getTime());
        assertEquals(END.getTime() + 456, captured.getValue().getEndTime().getTime());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-15T15:59:59Z", "2026-10-15T16:00:00Z", "2026-12-31T16:00:00Z"})
    void midnightAndYearBoundariesKeepTheOriginalInstant(String instant) throws Exception {
        var service = mock(IDppEtlTaskInstanceService.class);
        when(service.updateTaskInstance(any())).thenReturn(true);
        var listener = new ProcessListener();
        ReflectionTestUtils.setField(listener, "dppEtlTaskInstanceService", service);
        var process = process();
        var start = Date.from(Instant.parse(instant));
        process.setStartTime(start);
        process.setEndTime(new Date(start.getTime() + 30000));
        var msg = message(Map.of("type", 2, "instance", process));
        listener.processInstanceUpdate(wireMap(msg), mock(Channel.class), msg);
        var captured = ArgumentCaptor.forClass(ProcessInstance.class);
        verify(service).updateTaskInstance(captured.capture());
        assertEquals(start, captured.getValue().getStartTime());
        assertEquals(start.getTime() + 30000, captured.getValue().getEndTime().getTime());
    }

    @Test void malformedTimeFailsBeforePersistenceOrAcknowledgement() throws Exception {
        var service = mock(IDppEtlNodeInstanceService.class);
        var listener = new TaskListener();
        ReflectionTestUtils.setField(listener, "dppEtlNodeInstanceService", service);
        var channel = mock(Channel.class);
        var msg = message(Map.of("id", 1, "startTime", "invalid-time"));
        assertThrows(IllegalArgumentException.class, () -> listener.taskInstanceInsert(wireMap(msg), channel, msg));
        verifyNoInteractions(service, channel);
    }

    @Test void additionalProducerFieldsRemainCompatible() throws Exception {
        var service = mock(IDppEtlNodeInstanceService.class);
        when(service.updateNodeInstance(any())).thenReturn(true);
        var listener = new TaskListener();
        ReflectionTestUtils.setField(listener, "dppEtlNodeInstanceService", service);
        var msg = message(node());
        var map = wireMap(msg);
        map.put("producerVersion", "future-compatible");
        listener.taskInstanceUpdate(map, mock(Channel.class), msg);
        var captured = ArgumentCaptor.forClass(TaskInstance.class);
        verify(service).updateNodeInstance(captured.capture());
        assertEquals(START, captured.getValue().getStartTime());
    }
}

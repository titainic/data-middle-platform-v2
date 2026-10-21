package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.api.ds.api.base.DsStatusRespDTO;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlTaskService;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlSchedulerService;
import tech.qiantong.qdata.common.constant.ScheduleConstants;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlSchedulerPageReqVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.*;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlTaskMapper;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlSchedulerService;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEtlTaskServiceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实上下线服务逻辑；持久化与外部调度均为替身，不连接共享环境。 */
class TaskReleaseRoutingRegressionTest {
    private final DppEtlTaskServiceImpl service = new DppEtlTaskServiceImpl();
    private final DppEtlTaskMapper tasks = mock(DppEtlTaskMapper.class);
    private final IDppEtlSchedulerService schedules = mock(IDppEtlSchedulerService.class);
    private final IDsEtlTaskService dolphin = mock(IDsEtlTaskService.class);
    private final IDsEtlSchedulerService dolphinSchedules = mock(IDsEtlSchedulerService.class);
    private final DppEtlTaskDO task = new DppEtlTaskDO();
    private final DppEtlSchedulerDO schedule = new DppEtlSchedulerDO();
    private final DppEtlNewNodeSaveReqVO request = new DppEtlNewNodeSaveReqVO();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "dppEtlTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "iDppEtlSchedulerService", schedules);
        ReflectionTestUtils.setField(service, "dsEtlTaskService", dolphin);
        ReflectionTestUtils.setField(service, "iDsEtlSchedulerService", dolphinSchedules);
        task.setId(10L); task.setCode("1010"); task.setType("3"); task.setProjectCode("5005");
        task.setScheduler(ScheduleConstants.QUARTZ); task.setStatus("1");
        schedule.setStatus("0"); schedule.setDsId(0L);
        request.setId("10"); request.setReleaseState("0");
        when(tasks.selectById(any())).thenReturn(task);
        when(schedules.getDppEtlSchedulerById(any(DppEtlSchedulerPageReqVO.class))).thenReturn(schedule);
        when(tasks.updateById(any(DppEtlTaskDO.class))).thenReturn(1);
    }

    @ParameterizedTest @CsvSource({"1,0,0", "-3,0,-2", "0,1,1", "-2,1,-3"})
    void quartzReleaseUsesLocalPathAndNeverCallsDolphin(String before, String target, String expected) {
        task.setStatus(before); request.setReleaseState(target);
        service.updateReleaseTask(request);
        verify(tasks).updateById(argThat((DppEtlTaskDO value) -> expected.equals(value.getStatus())));
        verifyNoInteractions(dolphin, dolphinSchedules);
    }

    @Test void quartzCannotOfflineTaskWhileScheduleIsOnline() {
        schedule.setStatus("1");
        assertThrows(ServiceException.class, () -> service.updateReleaseTask(request));
        verify(tasks, never()).updateById(any(DppEtlTaskDO.class));
        verifyNoInteractions(dolphin, dolphinSchedules);
    }

    @ParameterizedTest @CsvSource({"1,0", "-3,-2"})
    void dolphinOfflineReturnsBeforeOnlineAndKeepsOfflineStatus(String before, String expected) {
        task.setScheduler(ScheduleConstants.DOLPHINSCHEDULER); task.setStatus(before); schedule.setDsId(19L);
        var response = new DsStatusRespDTO(); response.setSuccess(true); response.setData(true);
        when(dolphin.releaseTask("OFFLINE", "5005", "1010")).thenReturn(response);
        when(dolphinSchedules.offlineScheduler("5005", 19L)).thenReturn(response);
        service.updateReleaseTask(request);
        verify(dolphin).releaseTask("OFFLINE", "5005", "1010");
        verify(dolphinSchedules).offlineScheduler("5005", 19L);
        verifyNoMoreInteractions(dolphin, dolphinSchedules);
        verify(tasks).updateById(argThat((DppEtlTaskDO value) -> expected.equals(value.getStatus())));
    }

    @Test void failedDolphinOfflineDoesNotWriteLocalSuccessOrAttemptOnline() {
        task.setScheduler(ScheduleConstants.DOLPHINSCHEDULER); schedule.setDsId(19L);
        assertThrows(ServiceException.class, () -> service.updateReleaseTask(request));
        verify(tasks, never()).updateById(any(DppEtlTaskDO.class));
        verify(dolphin).releaseTask("OFFLINE", "5005", "1010");
        verifyNoMoreInteractions(dolphin);
        verifyNoInteractions(dolphinSchedules);
    }
}

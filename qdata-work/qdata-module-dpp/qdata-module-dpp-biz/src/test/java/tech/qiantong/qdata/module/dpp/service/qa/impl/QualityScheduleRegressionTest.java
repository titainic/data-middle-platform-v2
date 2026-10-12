package tech.qiantong.qdata.module.dpp.service.qa.impl;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.quartz.scheduler.ISchedulerAdapter;
import tech.qiantong.qdata.quartz.domain.*;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskSaveReqVO;
import java.lang.reflect.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class QualityScheduleRegressionTest {
    private Long upsert(ISchedulerAdapter adapter, Long id) throws Exception {
        DppQualityTaskServiceImpl service = new DppQualityTaskServiceImpl();
        Field field = service.getClass().getDeclaredField("schedulerAdapter");field.setAccessible(true);field.set(service, adapter);
        DppQualityTaskSaveReqVO task = new DppQualityTaskSaveReqVO();
        task.setId(1L);task.setTaskName("Hive test");task.setCycle("0 0/30 * * * ?");
        Method method = service.getClass().getDeclaredMethod("quartzUpsertSchedule", DppQualityTaskSaveReqVO.class, Long.class);method.setAccessible(true);
        return (Long) method.invoke(service, task, id);
    }
    @Test void existingScheduleKeepsIdentityAndDoesNotCreateDuplicate() throws Exception {
        ISchedulerAdapter adapter = mock(ISchedulerAdapter.class);
        when(adapter.selectScheduleById(any())).thenReturn(ScheduleRespDTO.builder().jobId(9L).status("1").build());
        when(adapter.updateSchedule(any())).thenReturn(9L);
        assertEquals(9L,upsert(adapter,9L));
        verify(adapter).updateSchedule(argThat(c -> c.getId()==9L && "Hive test".equals(c.getJobName()) && "1".equals(c.getStatus())));
        verify(adapter,never()).delete(any());verify(adapter,never()).createSchedule(any());
    }
    @Test void missingScheduleIsCreatedOnce() throws Exception {
        ISchedulerAdapter adapter = mock(ISchedulerAdapter.class);
        when(adapter.createSchedule(any())).thenReturn(10L);
        assertEquals(10L,upsert(adapter,9L));
        verify(adapter,times(1)).createSchedule(any());verify(adapter,never()).delete(any());
    }
}

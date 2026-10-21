package tech.qiantong.qdata.module.dpp.service.scheduler;

import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.quartz.scheduler.ISchedulerAdapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DppTaskQuartzServiceTest {
    private final ISchedulerAdapter adapter = mock(ISchedulerAdapter.class);
    private final DppTaskQuartzService service = new DppTaskQuartzService();
    private DppEtlTaskDO task() {
        ReflectionTestUtils.setField(service, "schedulerAdapter", adapter);
        var task = new DppEtlTaskDO(); task.setId(37L); task.setName("fix_task");
        task.setCronExpression("0 * * * * ?"); task.setExecutionType("PARALLEL");
        return task;
    }

    @Test void registrationIsPausedUntilTheUserEnablesPeriodicScheduling() throws Exception {
        when(adapter.createSchedule(any())).thenReturn(42L);
        assertEquals(42L, service.create(task(), "dppQuartzJob.dataDevelopment(%sL)"));
        verify(adapter).createSchedule(argThat(command -> "1".equals(command.getStatus())
                && "dppQuartzJob.dataDevelopment(37L)".equals(command.getInvokeTarget())));
    }

    @Test void schedulerFailureCannotBecomeSuccessfulQuartzIdZero() throws Exception {
        when(adapter.createSchedule(any())).thenThrow(new SchedulerException("offline fixture"));
        assertThrows(ServiceException.class, () -> service.create(task(), "dppQuartzJob.dataDevelopment(%sL)"));
        verify(adapter, never()).selectScheduleById(any());
    }

    @Test void invalidSchedulerIdFailsTheSave() throws Exception {
        when(adapter.createSchedule(any())).thenReturn(0L);
        assertThrows(ServiceException.class, () -> service.create(task(), "dppQuartzJob.dataDevelopment(%sL)"));
    }
}

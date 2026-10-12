package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlTaskService;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService;
import tech.qiantong.qdata.quartz.domain.ScheduleCommand;
import tech.qiantong.qdata.quartz.scheduler.ISchedulerAdapter;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WarehouseDolphinDispatchTest {
    private final ObjectProvider<IDppEtlTaskService> provider=mock(ObjectProvider.class);
    private final IDppEtlTaskService tasks=mock(IDppEtlTaskService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final IDsEtlTaskService dolphin=mock(IDsEtlTaskService.class);
    private final ISchedulerAdapter quartz=mock(ISchedulerAdapter.class);
    private final WarehouseTaskGatewayImpl gateway=new WarehouseTaskGatewayImpl(provider,mock(DppPublishedExecutionPlan.class),jdbc,dolphin,quartz,mock(WarehouseNativeDolphinService.class));
    private final String batch="5911a599-cd3b-49f0-9815-29bf5ab64d23";

    private void waiting() {
        when(jdbc.queryForList(startsWith("SELECT batch_id,task_id,project_code,instance_id FROM dp_warehouse_ds_dispatch")))
                .thenReturn(List.of(Map.of("batch_id",batch,"task_id",37L,"project_code","185094373543744","instance_id",1519L)));
    }

    @Test void onlyASuccessfulDolphinInstanceCanTriggerTheGuardedRunner() throws Exception {
        waiting();when(dolphin.instanceState("185094373543744",1519L)).thenReturn("SUCCESS");
        when(jdbc.update(startsWith("UPDATE dp_warehouse_ds_dispatch SET state='TRIGGERING'"),eq(batch))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE dp_warehouse_batch SET status='RUNNING'"),eq(batch))).thenReturn(1);
        when(provider.getObject()).thenReturn(tasks);
        var task=new DppEtlTaskDO();task.setQuartzId(71L);when(tasks.getById(37L)).thenReturn(task);
        gateway.reconcileDolphinBatches();
        verify(quartz,times(1)).trigger(argThat((ScheduleCommand command)->Long.valueOf(71).equals(command.getId())));
        verify(jdbc).update(startsWith("UPDATE dp_warehouse_ds_dispatch SET state='FIRED'"),eq(batch));
    }

    @Test void aFailedDolphinInstanceNeverSubmitsHiveWork() throws Exception {
        waiting();when(dolphin.instanceState("185094373543744",1519L)).thenReturn("FAILURE");
        when(jdbc.update(startsWith("UPDATE dp_warehouse_ds_dispatch SET state='FAILED'"),eq("FAILURE"),eq(batch))).thenReturn(1);
        gateway.reconcileDolphinBatches();
        verifyNoInteractions(quartz);
        verify(provider,never()).getObject();
    }
}

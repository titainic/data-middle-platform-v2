package tech.qiantong.qdata.module.dpp.service.etl;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.constant.ScheduleConstants;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.*;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlTaskMapper;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEtlTaskServiceImpl;
import tech.qiantong.qdata.module.dpp.utils.TaskConverter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuartzDefinitionLogRegressionTest {
    private void inject(Object target, String name, Object value) throws Exception {
        var field=DppEtlTaskServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target,value);
    }

    @Test void updatingQuartzDefinitionCreatesValidNewLog() throws Exception {
        var service=spy(new DppEtlTaskServiceImpl());
        var mapper=mock(DppEtlTaskMapper.class);
        var logs=mock(IDppEtlTaskLogService.class);
        var nodes=mock(IDppEtlNodeService.class);
        var rels=mock(IDppEtlTaskNodeRelService.class);
        inject(service,"dppEtlTaskMapper",mapper);
        inject(service,"iDppEtlTaskLogService",logs);
        inject(service,"iDppEtlNodeService",nodes);
        inject(service,"iDppEtlNodeLogService",mock(IDppEtlNodeLogService.class));
        inject(service,"iDppEtlTaskNodeRelService",rels);
        inject(service,"iDppEtlTaskNodeRelLogService",mock(IDppEtlTaskNodeRelLogService.class));
        var task=new DppEtlTaskDO();
        task.setId(31L);task.setCode("env31");task.setStatus("0");task.setScheduler(ScheduleConstants.QUARTZ);
        when(mapper.selectById("31")).thenReturn(task);
        when(rels.removeOldDppEtlTaskNodeRel(anyString())).thenReturn(List.of());
        when(nodes.createDppEtlNodeBatch(anyList())).thenReturn(List.of());
        doReturn(Map.of()).when(service).releaseTaskCrontab(any());
        doReturn(1).when(service).updateDppEtlTask(any());
        doReturn(null).when(service).getDppEtlTaskLogByRequest(any());
        when(logs.createDppEtlTaskLog(any())).thenAnswer(invocation->{
            DppEtlTaskLogSaveReqVO log=invocation.getArgument(0);
            assertNull(log.getId(),"A new version log must not reuse the task primary key");
            assertEquals(0L,log.getDsId(),"Quartz logs must satisfy the non-null ds_id column");
            return 80L;
        });
        var request=new DppEtlNewNodeSaveReqVO();
        request.setId("31");request.setCode("env31");request.setVersion(2);
        request.setTaskDefinitionList("[]");request.setTaskRelationJson("[]");
        try(var converter=mockStatic(TaskConverter.class,invocation->{
            if(List.class.isAssignableFrom(invocation.getMethod().getReturnType())) return List.of();
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        })) {
            service.updateProcessDefinition(request);
        }
        verify(logs).createDppEtlTaskLog(any());
    }
}

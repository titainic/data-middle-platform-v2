package tech.qiantong.qdata.module.dpp.service.etl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.qiantong.qdata.api.ds.api.etl.ds.TaskInstance;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskInstanceDO;
import tech.qiantong.qdata.module.dpp.service.etl.task.DataDevelopmentJdbcTaskRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DataDevelopmentNodeLogRegressionTest {
 @Test void nodeLogUsesNodeCodeAndVersionEvenWhenWorkflowIdentityDiffers() throws Exception {
  var runner=new DataDevelopmentJdbcTaskRunner();var service=mock(IDppEtlNodeInstanceService.class);
  when(service.createNodeInstance(any())).thenReturn(true);
  when(service.getPersistedStatus(anyLong())).thenReturn("1");
  var field=DataDevelopmentJdbcTaskRunner.class.getDeclaredField("dppEtlNodeInstanceService");field.setAccessible(true);field.set(runner,service);
  var task=new DppEtlTaskDO();task.setId(31L);task.setCode("workflow-code");task.setName("environment query");task.setProjectCode("project-code");
  var node=new DppEtlNodeRespVO();node.setCode("sql-node-code");node.setVersion(3L);node.setParameters("{\"taskType\":\"SQL\"}");
  var instance=new DppEtlTaskInstanceDO();instance.setId(100L);instance.setTaskVersion(7);
  var method=DataDevelopmentJdbcTaskRunner.class.getDeclaredMethod("createNodeInstance",DppEtlTaskDO.class,DppEtlNodeRespVO.class,DppEtlTaskInstanceDO.class);method.setAccessible(true);method.invoke(runner,task,node,instance);
  var capture=ArgumentCaptor.forClass(TaskInstance.class);verify(service).createNodeInstance(capture.capture());
  assertEquals("sql-node-code",capture.getValue().getTaskCode());assertEquals(3,capture.getValue().getTaskDefinitionVersion());assertEquals(100L,capture.getValue().getProcessInstanceId());
 }
}

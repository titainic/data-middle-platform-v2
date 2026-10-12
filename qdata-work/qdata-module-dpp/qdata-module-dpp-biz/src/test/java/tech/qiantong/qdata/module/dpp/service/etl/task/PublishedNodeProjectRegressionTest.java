package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.api.ds.api.etl.ds.TaskDefinition;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import tech.qiantong.qdata.module.dpp.utils.TaskConverter;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PublishedNodeProjectRegressionTest {
    @Test void publishedNodeUsesItsWorkflowProjectEvenWhenNodePayloadOmitsOrSpoofsIt() {
        var request=new DppEtlNewNodeSaveReqVO();request.setType("3");request.setProjectId(1L);request.setProjectCode(123L);
        request.setTaskDefinitionList("[{\"code\":\"101\",\"taskParams\":{\"sql\":\"SELECT 1\",\"componentType\":\"51\"}}]");
        var node=new TaskDefinition();node.setCode("101");node.setVersion(1);node.setTaskType("SQL");
        assertEquals("123",TaskConverter.convertToDppEtlNodeSaveReqVOList(List.of(node),request).get(0).getProjectCode());
        assertEquals("51",TaskConverter.convertToDppEtlNodeSaveReqVOList(List.of(node),request).get(0).getComponentType());
        node.setProjectCode("wrong-project");assertEquals("123",TaskConverter.convertToDppEtlNodeSaveReqVOList(List.of(node),request).get(0).getProjectCode());
    }
}

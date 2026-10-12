package tech.qiantong.qdata.module.dpp.utils;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.*;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlNodeDO;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TaskRelationRegressionTest {
    private DppEtlNodeDO node(long id,String code,long version) {
        DppEtlNodeDO n=new DppEtlNodeDO();n.setId(id);n.setCode(code);n.setVersion(version);return n;
    }
    private DppEtlNewNodeSaveReqVO request() {
        DppEtlNewNodeSaveReqVO r=new DppEtlNewNodeSaveReqVO();
        r.setTaskRelationJson("[{\"preTaskCode\":0,\"preTaskVersion\":0,\"postTaskCode\":101,\"postTaskVersion\":1},{\"preTaskCode\":101,\"preTaskVersion\":1,\"postTaskCode\":102,\"postTaskVersion\":1}]");return r;
    }
    @Test void editedNodeVersionIsUsedByIncomingAndOutgoingRelations() {
        var result=TaskConverter.convertToDppEtlTaskNodeRelSaveReqVOList(List.of(node(6,"101",2),node(7,"102",1)),request(),new DppEtlTaskSaveReqVO());
        assertEquals(-1L,result.get(0).getPreNodeId());
        assertEquals(6L,result.get(0).getPostNodeId());assertEquals(2,result.get(0).getPostNodeVersion());
        assertEquals(6L,result.get(1).getPreNodeId());assertEquals(2,result.get(1).getPreNodeVersion());
        assertEquals(7L,result.get(1).getPostNodeId());assertEquals(1,result.get(1).getPostNodeVersion());
    }
    @Test void missingNodeIsRejectedInsteadOfPersistingBrokenRelation() {
        assertThrows(IllegalArgumentException.class,()->TaskConverter.convertToDppEtlTaskNodeRelSaveReqVOList(List.of(node(6,"101",2)),request(),new DppEtlTaskSaveReqVO()));
    }
}

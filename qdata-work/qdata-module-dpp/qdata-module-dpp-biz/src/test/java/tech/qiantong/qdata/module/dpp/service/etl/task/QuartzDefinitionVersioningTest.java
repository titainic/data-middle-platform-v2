package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import static org.junit.jupiter.api.Assertions.*;

class QuartzDefinitionVersioningTest {
    @Test void firstSaveCreatesVersionOneEvenWhenDraftNodesHaveNoVersion() {
        var r = request(); r.setVersion(0);
        QuartzDefinitionVersioning.prepare(r, 0L, code -> 0);
        assertEquals(1, r.getVersion());
        assertTrue(JSON.parseArray(r.getTaskDefinitionList()).stream()
                .allMatch(node -> ((com.alibaba.fastjson2.JSONObject) node).getIntValue("version") == 1));
    }
    DppEtlNewNodeSaveReqVO request() {
        var r=new DppEtlNewNodeSaveReqVO();r.setVersion(3);
        r.setTaskDefinitionList("[{\"code\":\"100\",\"version\":1},{\"code\":\"200\",\"version\":2}]");
        r.setTaskRelationJson("[{\"preTaskCode\":0,\"postTaskCode\":\"100\"},{\"preTaskCode\":\"100\",\"postTaskCode\":\"200\"}]");return r;
    }
    @Test void advancesTaskAndEachNodeWhileRebindingEveryDependency() {
        var r=request();QuartzDefinitionVersioning.prepare(r,3L,code->code.equals("100")?7:4);
        assertEquals(4,r.getVersion());var nodes=JSON.parseArray(r.getTaskDefinitionList());var edges=JSON.parseArray(r.getTaskRelationJson());
        assertEquals(8,nodes.getJSONObject(0).getIntValue("version"));assertEquals(5,nodes.getJSONObject(1).getIntValue("version"));
        assertEquals(0,edges.getJSONObject(0).getIntValue("preTaskVersion"));assertEquals(8,edges.getJSONObject(1).getIntValue("preTaskVersion"));assertEquals(5,edges.getJSONObject(1).getIntValue("postTaskVersion"));
    }
    @Test void staleEditorCannotOverwriteAPublishedVersion() {
        var r=request();String before=r.getTaskDefinitionList();assertThrows(IllegalArgumentException.class,()->QuartzDefinitionVersioning.prepare(r,4L,code->1));assertEquals(before,r.getTaskDefinitionList());
    }
    @Test void danglingDependencyIsRejectedBeforeSaving() {
        var r=request();r.setTaskRelationJson("[{\"preTaskCode\":\"missing\",\"postTaskCode\":\"100\"}]");assertThrows(IllegalArgumentException.class,()->QuartzDefinitionVersioning.prepare(r,3L,code->1));
    }
    @Test void duplicateNodeCodesCannotShareAMutableVersion() {
        var r=request();r.setTaskDefinitionList("[{\"code\":\"100\"},{\"code\":\"100\"}]");assertThrows(IllegalArgumentException.class,()->QuartzDefinitionVersioning.prepare(r,3L,code->1));
    }
}

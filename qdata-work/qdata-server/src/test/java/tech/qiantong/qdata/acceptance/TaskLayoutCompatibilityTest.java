package tech.qiantong.qdata.acceptance;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskUpdateQueryRespVO;
import static org.junit.jupiter.api.Assertions.*;
class TaskLayoutCompatibilityTest {
    private DppEtlTaskUpdateQueryRespVO read(String layout){var task=new DppEtlTaskDO();task.setLocations(layout);return new DppEtlTaskUpdateQueryRespVO(task);}
    @Test void currentJsonLayoutRetainsLargeIdentifiersAndCoordinates(){var point=read("[{\"taskCode\":1789358660026000,\"x\":80,\"y\":100}]").getLocations().get(0);assertEquals(1789358660026000L,((Number)point.get("taskCode")).longValue());assertEquals(80,point.get("x"));}
    @Test void historicalNumericMapLayoutIsReadableWithoutMutatingTheSnapshot(){var task=new DppEtlTaskDO();String raw="[{taskCode=1789358660026000, y=100, x=-80}, {y=260, x=340, taskCode=1789358660026001}]";task.setLocations(raw);var layout=new DppEtlTaskUpdateQueryRespVO(task).getLocations();assertEquals(2,layout.size());assertEquals(1789358660026001L,((Number)layout.get(1).get("taskCode")).longValue());assertEquals(-80,layout.get(0).get("x"));assertEquals(raw,task.getLocations());}
    @Test void malformedOrUnknownHistoricalFieldsAreRejected(){assertThrows(RuntimeException.class,()->read("[{taskCode=1, x=2, other=3}]"));assertThrows(RuntimeException.class,()->read("[{taskCode=1, x=broken, y=3}]"));}
}

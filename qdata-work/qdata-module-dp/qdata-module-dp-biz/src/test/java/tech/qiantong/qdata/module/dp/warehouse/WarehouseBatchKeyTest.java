package tech.qiantong.qdata.module.dp.warehouse;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseBatchKeyTest {
    WarehouseBatchRegistry.Request request() {
        var r=new WarehouseBatchRegistry.Request();r.setModelIds(List.of(20L,10L));r.setWindowStart(LocalDate.of(2024,1,1));r.setWindowEnd(LocalDate.of(2024,1,18));return r;
    }
    WarehouseModelCompiler.Step step(long model,long dataset,int revision,String physical) {
        var d=new WarehouseDefinition();d.setDatasetId(dataset);return new WarehouseModelCompiler.Step(model,revision,"source",physical,d,List.of());
    }
    WarehouseBatchRegistry.InputProof input(long dataset,String hash) {return new WarehouseBatchRegistry.InputProof(dataset,dataset,List.of(),List.of(),5000,5000,hash);}
    @Test void collectionOrderPhysicalAttemptAndDisplayNamesDoNotChangeTheRequestKey() {
        var first=request();var second=request();second.setModelIds(List.of(10L,20L));second.setName("different display name");
        String a=WarehouseBatchRegistry.canonicalKey(1,first,List.of(step(1,5,2,"attempt-a"),step(2,6,3,"attempt-a")),List.of(input(5,"a"),input(6,"b")));
        String b=WarehouseBatchRegistry.canonicalKey(1,second,List.of(step(2,6,3,"attempt-b"),step(1,5,2,"attempt-b")),List.of(input(6,"b"),input(5,"a")));
        assertEquals(a,b);assertEquals(64,a.length());
    }
    @Test void changedInputTemplateVersionOrBusinessWindowCreatesANewKey() {
        var r=request();var plan=List.of(step(1,5,2,"x"));String first=WarehouseBatchRegistry.canonicalKey(1,r,plan,List.of(input(5,"a")));
        assertNotEquals(first,WarehouseBatchRegistry.canonicalKey(1,r,plan,List.of(input(5,"b"))));
        assertNotEquals(first,WarehouseBatchRegistry.canonicalKey(1,r,List.of(step(1,5,3,"x")),List.of(input(5,"a"))));
        r.setWindowEnd(LocalDate.of(2024,2,1));assertNotEquals(first,WarehouseBatchRegistry.canonicalKey(1,r,plan,List.of(input(5,"a"))));
    }
    @Test void schedulerAndComputeRouteAreFrozenInTheBatchIdentity() {
        var r=request();var plan=List.of(step(1,5,2,"x"));var input=List.of(input(5,"a"));
        String quartzTez=WarehouseBatchRegistry.canonicalKey(1,r,plan,input);
        r.setScheduler("DOLPHINSCHEDULER");
        String dolphinTez=WarehouseBatchRegistry.canonicalKey(1,r,plan,input);
        assertNotEquals(quartzTez,dolphinTez);
        r.setComputeEngine("KYUUBI_SPARK");r.setComputeDatasourceId(25L);
        String dolphinSpark=WarehouseBatchRegistry.canonicalKey(1,r,plan,input);
        assertNotEquals(dolphinTez,dolphinSpark);
        r.setComputeDatasourceId(26L);
        assertNotEquals(dolphinSpark,WarehouseBatchRegistry.canonicalKey(1,r,plan,input));
    }
}

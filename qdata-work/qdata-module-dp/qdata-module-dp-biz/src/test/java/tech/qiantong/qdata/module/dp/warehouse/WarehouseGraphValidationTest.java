package tech.qiantong.qdata.module.dp.warehouse;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.dpp.api.warehouse.WarehouseTaskGateway;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseGraphValidationTest {
    static final String BATCH="6cda62d8-f00a-46d1-b111-61b2e50a32c5";
    List<WarehouseDefinition> models(){
        var result=new ArrayList<WarehouseDefinition>();String[] layers={"ODS","DWD","DWS"};
        for(int i=0;i<3;i++){var d=new WarehouseDefinition();d.setId((long)i+1);d.setRevision(4);d.setDatasetId((long)i+1);d.setSpaceId(1L);d.setDatasourceId(9L);d.setLayer(layers[i]);d.setDatabaseName(layers[i].toLowerCase(Locale.ROOT)+"_iot_db");d.setTableName("water_quality");d.setName("water_quality");var c=new WarehouseDefinition.Column();c.setName("sensor_id");c.setType("STRING");c.setSource("sensor_id");d.setColumns(new ArrayList<>(List.of(c)));if(i>0){d.setUpstreamIds(new ArrayList<>(List.of((long)i)));d.setSelectSql("SELECT sensor_id FROM "+layers[i-1].toLowerCase(Locale.ROOT)+"_iot_db.water_quality");}result.add(d);}return result;
    }
    WarehouseBatchRegistry.Request request(){var r=new WarehouseBatchRegistry.Request();r.setName("graph validation");r.setSpaceId(1L);r.setModelIds(List.of(3L));r.setWindowStart(LocalDate.of(2026,8,31));r.setWindowEnd(LocalDate.of(2026,9,1));return r;}
    void rejectedBeforeAnyOutput(List<WarehouseDefinition> models,String text)throws Exception{
        var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);var spaces=mock(WarehouseSpaceService.class);var modelService=mock(WarehouseService.class);var tasks=mock(WarehouseTaskGateway.class);var factory=mock(DataSourceFactory.class);
        when(spaces.getInternal(1L)).thenReturn(new WarehouseSpaceService.Space());when(modelService.list()).thenReturn(models);
        var registry=new WarehouseBatchRegistry(jdbc,tx,spaces,modelService,tasks,factory);
        var error=assertThrows(IllegalArgumentException.class,()->registry.create(request()));assertTrue(error.getMessage().contains(text),error.getMessage());verifyNoInteractions(jdbc,tx,tasks,factory);
    }
    @Test void upstreamCycleReportsOrderedFullyQualifiedPath()throws Exception{var models=models();models.get(0).setUpstreamIds(List.of(3L));rejectedBeforeAnyOutput(models,"dws_iot_db.water_quality[3] -> dwd_iot_db.water_quality[2] -> ods_iot_db.water_quality[1] -> dws_iot_db.water_quality[3]");}
    @Test void dimensionCycleReportsItsPath()throws Exception{var models=models();var relation=new WarehouseDefinition.Relation();relation.setModelId(3L);models.get(2).setDimensions(List.of(relation));rejectedBeforeAnyOutput(models,"dws_iot_db.water_quality[3] -> dws_iot_db.water_quality[3]");}
    @Test void missingDimensionReportsOriginAndMissingIdentity()throws Exception{var models=models();var relation=new WarehouseDefinition.Relation();relation.setModelId(999L);models.get(2).setDimensions(List.of(relation));rejectedBeforeAnyOutput(models,"dws_iot_db.water_quality[3] -> missing[999]");}
    @Test void oldPhysicalVersionCannotBeHiddenBehindCurrentUpstreamIds()throws Exception{var models=models();models.get(1).setSelectSql("SELECT sensor_id FROM ods_iot_db.water_quality__old_run");rejectedBeforeAnyOutput(models,"模板实际来源与登记上游不一致");}
    @Test void eachBatchUsesFreshPhysicalSourcesWithoutDependingOnUserRunIdPredicates(){var models=models();var plan=WarehouseModelCompiler.compile(models,List.of(3L),BATCH);String suffix=BATCH.replace("-","");assertEquals(Set.of("ods_iot_db.water_quality__"+suffix),WarehouseSql.readTables(plan.get(1).definition().getSelectSql(),"dwd_iot_db"));assertEquals(Set.of("dwd_iot_db.water_quality__"+suffix),WarehouseSql.readTables(plan.get(2).definition().getSelectSql(),"dws_iot_db"));assertFalse(plan.get(1).definition().getSelectSql().contains("run_id"));}
    @Test void anotherRunGetsDifferentTargetsAndFrozenSql(){var models=models();var old=WarehouseModelCompiler.compile(models,List.of(3L),BATCH);models.get(1).setRevision(5);models.get(1).setSelectSql("SELECT upper(sensor_id) AS sensor_id FROM ods_iot_db.water_quality");var next=WarehouseModelCompiler.compile(models,List.of(3L),"b6850291-e06c-4776-a633-4705e8ecb8aa");assertEquals(4,old.get(1).templateRevision());assertEquals(5,next.get(1).templateRevision());assertNotEquals(old.get(1).targetTable(),next.get(1).targetTable());assertFalse(old.get(1).definition().getSelectSql().toUpperCase(Locale.ROOT).contains("UPPER("));assertTrue(next.get(1).definition().getSelectSql().toUpperCase(Locale.ROOT).contains("UPPER("));}
    @Test void failedInputReceiptCannotReachHiveOrRegistration()throws Exception{var models=models();var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);var spaces=mock(WarehouseSpaceService.class);var ms=mock(WarehouseService.class);var tasks=mock(WarehouseTaskGateway.class);var factory=mock(DataSourceFactory.class);var space=new WarehouseSpaceService.Space();space.setId(1L);space.setProjectId(1L);space.setDatasourceId(9L);when(spaces.getInternal(1L)).thenReturn(space);when(ms.list()).thenReturn(models);when(ms.get(1L)).thenReturn(models.get(0));when(tasks.receipt(777L)).thenThrow(new IllegalArgumentException("输入任务尚未全部成功"));var r=request();var input=new WarehouseBatchRegistry.Input();input.setModelId(1L);input.setInstanceId(777L);r.setInputs(List.of(input));var registry=new WarehouseBatchRegistry(jdbc,tx,spaces,ms,tasks,factory);assertThrows(IllegalArgumentException.class,()->registry.create(r));verify(tasks).receipt(777L);verifyNoInteractions(jdbc,tx,factory);}
}

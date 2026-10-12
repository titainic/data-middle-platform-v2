package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseModelCompilerTest {
    static final String BATCH="5f26a70a-a518-4ea1-8120-fb196b13dbf4";
    List<WarehouseDefinition> templates() throws Exception {
        try(var input=getClass().getResourceAsStream("/warehouse/iot-five-database-templates.json")) {
            assertNotNull(input);return JSON.parseArray(new String(input.readAllBytes(),StandardCharsets.UTF_8),WarehouseDefinition.class);
        }
    }
    @Test void realTwentyModelContractUsesOnlyTheNewVersionSources() throws Exception {
        var input=templates();String before=JSON.toJSONString(input);
        var plan=WarehouseModelCompiler.compile(input,List.of(54L,55L,56L,57L),BATCH);
        assertEquals(20,plan.size());assertEquals(532,plan.stream().mapToInt(s->s.definition().getColumns().size()).sum());
        assertEquals(before,JSON.toJSONString(input));
        Map<Long,String> targets=plan.stream().collect(Collectors.toMap(WarehouseModelCompiler.Step::templateModelId,WarehouseModelCompiler.Step::targetTable));
        Set<Long> ready=new HashSet<>();
        for(var step:plan) {
            assertTrue(ready.containsAll(step.dependencies()));ready.add(step.templateModelId());
            assertNull(step.definition().getId());assertNotNull(step.definition().getDatasetId());assertNotEquals(step.sourceTable(),step.targetTable());
            if(!step.definition().getLayer().equals("ODS"))assertEquals(step.definition().getUpstreamIds().stream().map(targets::get).collect(Collectors.toSet()),WarehouseSql.readTables(step.definition().getSelectSql(),step.definition().getDatabaseName()));
        }
        input.get(0).getColumns().get(0).setName("changed_after_submit");
        assertEquals("device_id",plan.get(0).definition().getColumns().get(0).getName());
    }
    @Test void cteAliasesAndTableNamesInsideStringLiteralsStayUnchanged() {
        String sql="WITH water_quality AS (SELECT device_id FROM ods_iot_db.water_quality) SELECT 'ods_iot_db.water_quality' AS origin,device_id FROM water_quality";
        String rewritten=WarehouseModelCompiler.rewriteTables(sql,Map.of("ods_iot_db.water_quality","ods_iot_db.water_quality__new"));
        assertTrue(rewritten.contains("'ods_iot_db.water_quality'"));assertEquals(Set.of("ods_iot_db.water_quality__new"),WarehouseSql.readTables(rewritten,"dwd_iot_db"));
    }
    @Test void physicalVersionsStayDistinctAcrossAttemptsAndLongNames() {
        String a="a".repeat(127)+"x",b="a".repeat(127)+"y";
        String suffix=BATCH.replace("-","");
        assertTrue(WarehouseModelCompiler.versionTable(a,suffix).length()<=128);
        assertNotEquals(WarehouseModelCompiler.versionTable(a,suffix),WarehouseModelCompiler.versionTable(b,suffix));
        assertNotEquals(WarehouseModelCompiler.versionTable("water_quality",suffix),WarehouseModelCompiler.versionTable("water_quality",UUID.randomUUID().toString().replace("-","")));
    }
    @Test void crossSpaceAndMissingDimensionPlansFailBeforeRegistration() throws Exception {
        var input=templates();input.stream().filter(m->m.getId()==49L).findFirst().orElseThrow().setSpaceId(2L);
        assertThrows(IllegalArgumentException.class,()->WarehouseModelCompiler.compile(input,List.of(54L),BATCH));
        var missing=templates().stream().filter(m->m.getId()!=49L).toList();assertThrows(IllegalArgumentException.class,()->WarehouseModelCompiler.compile(missing,List.of(54L),BATCH));
    }
    @Test void realStringDatePartitionsSupportWindowedLatestObservationProcessing() throws Exception {
        var plan=WarehouseModelCompiler.compile(templates(),List.of(54L,57L),BATCH);
        for(var step:plan)if("DWD".equals(step.definition().getLayer())) {
            var d=step.definition();var sources=plan.stream().filter(s->d.getUpstreamIds().contains(s.templateModelId())).map(WarehouseModelCompiler.Step::definition).toList();
            var originalSources=WarehouseSql.readTables(d.getSelectSql(),d.getDatabaseName());
            WarehouseModelCompiler.deduplicateInputs(d,sources);
            WarehouseModelCompiler.applyWindow(d,java.time.LocalDate.of(2024,1,1),java.time.LocalDate.of(2024,2,2));
            assertEquals(originalSources,WarehouseSql.readTables(d.getSelectSql(),d.getDatabaseName()));
            assertTrue(d.getSelectSql().toUpperCase(Locale.ROOT).contains("ROW_NUMBER()"));
            assertTrue(d.getSelectSql().contains("2024-02-02"));
            assertDoesNotThrow(()->WarehouseSql.schemaCheck(d,d.getDatabaseName()));
            assertDoesNotThrow(()->WarehouseSql.insert(d,d.getDatabaseName()));
        }
    }
}

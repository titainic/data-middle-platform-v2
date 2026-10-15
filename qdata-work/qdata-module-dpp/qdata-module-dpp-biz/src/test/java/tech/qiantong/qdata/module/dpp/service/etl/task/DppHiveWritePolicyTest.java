package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DppHiveWritePolicyTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final DppHiveWritePolicy policy=new DppHiveWritePolicy(jdbc);
    DppHiveWritePolicyTest(){when(jdbc.queryForObject(anyString(),eq(Long.class),any(),any())).thenAnswer(call->"ods_iot_db".equals(call.getArgument(2)) && "snapshot".equals(call.getArgument(3))?1L:0L);}
    @Test void queriesAndTableNamesInsideLiteralsAreReadOnly() {
        assertDoesNotThrow(()->policy.sql("SELECT 'ods_iot_db.snapshot' FROM ods_iot_db.snapshot","qdata_test"));verifyNoInteractions(jdbc);
    }
    @Test void ordinarySqlCannotAppendToAManagedSnapshot() {
        assertThrows(IllegalArgumentException.class,()->policy.sql("INSERT INTO ods_iot_db.snapshot SELECT * FROM ods_iot_db.source","qdata_test"));
    }
    @Test void sameShortNameInAnotherDatabaseDoesNotTargetTheSnapshot() {
        assertDoesNotThrow(()->policy.sql("INSERT INTO dwd_iot_db.snapshot SELECT * FROM ods_iot_db.snapshot","qdata_test"));
    }
    @Test void destructiveStatementsAreRejectedOfflineWithoutAnyHiveConnection() {
        for(String sql:new String[]{"DROP TABLE ods_iot_db.snapshot","TRUNCATE TABLE ods_iot_db.snapshot","INSERT OVERWRITE TABLE ods_iot_db.snapshot SELECT * FROM ods_iot_db.source"})assertThrows(IllegalArgumentException.class,()->policy.sql(sql,"qdata_test"));
    }
    @Test void integrationOutputCannotBypassTheSameIdentityGuard() {
        JSONObject params=JSONObject.of("writerDatasource",JSONObject.of("datasourceType","Hive","datasourceConfig","{\"dbname\":\"qdata_test\"}"),"target_table_name","ods_iot_db.snapshot");
        assertThrows(IllegalArgumentException.class,()->policy.integration(params));
    }
    @Test void integrationPreScriptsAreCheckedAndLegacyHiveSettingsRemainValid() {
        JSONObject params=JSONObject.of("writerDatasource",JSONObject.of("datasourceType","Hive","datasourceConfig","{\"dbname\":\"qdata_test\"}"),"target_table_name","ods_iot_db.source","preSql","SET hive.exec.dynamic.partition=true,SET hive.exec.dynamic.partition.mode=nonstrict");
        assertDoesNotThrow(()->policy.integration(params));params.put("postSql","INSERT INTO ods_iot_db.snapshot SELECT * FROM ods_iot_db.source");assertThrows(IllegalArgumentException.class,()->policy.integration(params));
    }
    @Test void ordinarySqlCannotWriteIntoItsRegisteredFiveLayerDatabase() {
        when(jdbc.queryForList(anyString(),eq("dwd_iot_db"),eq(9L))).thenReturn(List.of(Map.of("layer_code","DWD","space_id",1L,"engine_id",1L)));
        assertThrows(IllegalArgumentException.class,()->policy.sql("INSERT INTO dwd_iot_db.new_table SELECT * FROM qdata_test.source","qdata_test",9L));
        assertDoesNotThrow(()->policy.sql("SELECT * FROM dwd_iot_db.new_table","qdata_test",9L));
        assertDoesNotThrow(()->policy.sql("INSERT INTO dwd_iot_db.new_table SELECT * FROM qdata_test.source","qdata_test",13L));
        assertThrows(IllegalArgumentException.class,()->policy.sql("USE dwd_iot_db; INSERT INTO new_table SELECT 1","qdata_test",9L));
    }
    @Test void managedIntegrationRequiresRegisteredOdsAssetAndAppendMode() {
        when(jdbc.queryForList(anyString(),eq("ods_iot_db"),eq(9L))).thenReturn(List.of(Map.of("layer_code","ODS","space_id",1L,"engine_id",1L)));
        when(jdbc.queryForObject(contains("FROM da_asset"),eq(Long.class),any(),any(),any(),any(),any(),any(),any())).thenReturn(1L);
        JSONObject writer=JSONObject.of("datasourceType","Hive","datasourceId",9L,"target_asset_id",335L,"datasourceConfig","{\"dbname\":\"qdata_test\"}");
        JSONObject params=JSONObject.of("writerDatasource",writer,"target_datasource_id",9L,"target_table_name","ods_iot_db.water_quality__g01fresh20260924","writeModeType",2);
        assertDoesNotThrow(()->policy.integration(params));
        params.put("writeModeType",1);assertThrows(IllegalArgumentException.class,()->policy.integration(params));
        params.put("writeModeType",2);writer.remove("target_asset_id");assertThrows(IllegalArgumentException.class,()->policy.integration(params));
    }
    @Test void integrationCannotWriteDirectlyIntoDwd() {
        when(jdbc.queryForList(anyString(),eq("dwd_iot_db"),eq(9L))).thenReturn(List.of(Map.of("layer_code","DWD","space_id",1L,"engine_id",1L)));
        JSONObject params=JSONObject.of("writerDatasource",JSONObject.of("datasourceType","Hive","datasourceId",9L,"datasourceConfig","{\"dbname\":\"qdata_test\"}"),"target_table_name","dwd_iot_db.water_quality","writeModeType",2);
        assertThrows(IllegalArgumentException.class,()->policy.integration(params));
    }

    private JSONObject odsOutput(String table, String assetId) {
        when(jdbc.queryForList(anyString(),eq("ods_iot_db"),eq(1L))).thenReturn(List.of(Map.of("layer_code","ODS","space_id",7L,"engine_id",8L)));
        when(jdbc.queryForObject(contains("FROM da_asset"),eq(Long.class),any(),any(),any(),any(),any(),any(),any())).thenAnswer(call -> {
            Object[] args = call.getArguments();
            return (Long.valueOf(2L).equals(args[2]) && "air".equals(args[5])
                    || Long.valueOf(3L).equals(args[2]) && "soil".equals(args[5]))
                    && Long.valueOf(1L).equals(args[3]) && "ods_iot_db".equals(args[4])
                    && Long.valueOf(8L).equals(args[6]) && Long.valueOf(1L).equals(args[7])
                    && Long.valueOf(7L).equals(args[8]) ? 1L : 0L;
        });
        return JSONObject.of("writerDatasource",JSONObject.of("datasourceType","Hive","datasourceId",1L,
                "target_asset_id",assetId,"datasourceConfig","{\"dbname\":\"default\"}"),
                "target_datasource_id","1","target_asset_id",assetId,"target_table_name","ods_iot_db."+table,"writeModeType",2);
    }

    @Test void connectionOneCanAppendToDistinctAirAndSoilAssetIdentities() {
        assertDoesNotThrow(() -> policy.integration(odsOutput("air","2")));
        assertDoesNotThrow(() -> policy.integration(odsOutput("soil","3")));
    }

    @Test void legacyConnectionIdAndAnotherTablesAssetRemainRejected() {
        for(String id:List.of("1","3","999")) assertThrows(IllegalArgumentException.class, () -> policy.integration(odsOutput("air",id)));
    }

    @Test void correctedIdentityStillRequiresAppendAndConsistentConnection() {
        var params=odsOutput("air","2");
        params.put("writeModeType",1); assertThrows(IllegalArgumentException.class,()->policy.integration(params));
        params.put("writeModeType",2); params.put("target_datasource_id",9L);
        assertThrows(IllegalArgumentException.class,()->policy.integration(params));
    }

    @Test void correctedIdentityCannotWriteAnUnregisteredOrRetainedTarget() {
        assertThrows(IllegalArgumentException.class,()->policy.integration(odsOutput("unknown","2")));
        assertThrows(IllegalArgumentException.class,()->policy.integration(odsOutput("snapshot","2")));
    }

    @Test void mismatchedNamespaceSpaceOrEngineRejectsAnOtherwiseCorrectAsset() {
        var params=odsOutput("air","2");
        when(jdbc.queryForList(anyString(),eq("ods_iot_db"),eq(1L))).thenReturn(List.of(Map.of("layer_code","ODS","space_id",99L,"engine_id",8L)));
        assertThrows(IllegalArgumentException.class,()->policy.integration(params));
        when(jdbc.queryForList(anyString(),eq("ods_iot_db"),eq(1L))).thenReturn(List.of(Map.of("layer_code","ODS","space_id",7L,"engine_id",99L)));
        assertThrows(IllegalArgumentException.class,()->policy.integration(params));
    }
}

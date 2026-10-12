package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.module.da.service.asset.AssetReferenceGuard;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class AssetServiceReferenceIntegrationTest {
    SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;AssetReferenceGuard guard;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_ASSET_CHILD_URL");assertNotNull(url);assertTrue(url.contains("/qdata_asset_child_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=manager.getTransaction(new DefaultTransactionDefinition());guard=new AssetReferenceGuard(jdbc);
        copy("da_asset",189,990001,Map.of("type","2","datasource_id","-1","table_name","reference_fixture","database_name","reference_fixture"));
        copy("da_asset",189,990002,Map.of("type","3","datasource_id","-1","table_name","reference_fixture","database_name","reference_fixture"));
        jdbc.update("INSERT INTO da_asset_api(id,asset_id,url,http_method,developer_name,app_name) VALUES(990001,990001,'http://127.0.0.1:1','GET','fixture','fixture')");
        jdbc.update("INSERT INTO da_asset_gis(id,asset_id,url,http_method,type,coordinate_system) VALUES(990002,990002,'http://127.0.0.1:1','GET','1','EPSG:4326')");
    }
    @AfterEach void close(){manager.rollback(tx);source.destroy();}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+"("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void reference(String type){Long old=jdbc.queryForObject("SELECT MIN(id) FROM ds_api",Long.class);copy("ds_api",old,990003,Map.of("api_service_type","3","transmit_type",type,"api_id",type.equals("1")?"990001":"990002","config_json","{}","del_flag","0"));}
    @ParameterizedTest @ValueSource(strings={"1","2"}) void forwardingReferenceProtectsParent(String type){reference(type);long asset=type.equals("1")?990001:990002;var impact=guard.references(asset);assertEquals(true,impact.get("referenced"));assertEquals(1,((List<?>)impact.get("services")).size());assertThrows(IllegalArgumentException.class,()->guard.requireRemovable(List.of(asset)));}
    @ParameterizedTest @ValueSource(strings={"1","2"}) void archivedServiceDoesNotBlockParent(String type){reference(type);jdbc.update("UPDATE ds_api SET del_flag='1' WHERE id=990003");assertDoesNotThrow(()->guard.requireRemovable(List.of(type.equals("1")?990001L:990002L)));}
    @Test void identicalNumericIdWithDifferentServiceKindDoesNotCreateFalseReference(){reference("1");jdbc.update("UPDATE ds_api SET transmit_type='2' WHERE id=990003");assertEquals(false,guard.references(990001L).get("referenced"));}
    @Test void unrelatedParentRemainsRemovable(){reference("1");assertDoesNotThrow(()->guard.requireRemovable(List.of(990002L)));}
    @Test void parentBatchIsRejectedBeforeAnyArchive(){reference("2");assertThrows(IllegalArgumentException.class,()->guard.requireRemovable(List.of(990001L,990002L)));assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM da_asset WHERE id IN(990001,990002) AND del_flag='0'",Long.class));}
    @Test void impactListsQualityRulesAndFieldStandardsBeforeRejectingArchive(){
        long task=jdbc.queryForObject("SELECT MIN(id) FROM dpp_quality_task",Long.class);
        long rule=jdbc.queryForObject("SELECT MIN(id) FROM dpp_quality_task_evaluate",Long.class);
        long standard=jdbc.queryForObject("SELECT MIN(id) FROM dp_data_elem_asset_rel",Long.class);
        copy("dpp_quality_task",task,990005,Map.of("asset_id",990001,"del_flag","0"));
        copy("dpp_quality_task_evaluate",rule,990005,Map.of("task_id",990005,"name","P6引用规则","del_flag","0"));
        copy("dp_data_elem_asset_rel",standard,990005,Map.of("asset_id","990001","column_name","p6_test_column","del_flag","0"));
        var impact=guard.references(990001L);
        assertEquals(1,((List<?>)impact.get("qualityTasks")).size());
        assertEquals(1,((List<?>)impact.get("qualityRules")).size());
        assertEquals(1,((List<?>)impact.get("standards")).size());
        assertEquals(1L,impact.get("standardLinks"));
        assertThrows(IllegalArgumentException.class,()->guard.requireRemovable(List.of(990001L)));
    }
}

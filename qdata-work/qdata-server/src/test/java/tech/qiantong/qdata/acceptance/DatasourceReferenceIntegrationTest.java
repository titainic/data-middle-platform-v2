package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.module.da.service.datasource.DatasourceReferenceGuard;
import tech.qiantong.qdata.module.da.service.datasource.impl.DaDatasourceServiceImpl;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceProjectRelService;
import tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper;
import tech.qiantong.qdata.module.dpp.api.service.etl.DppEtlTaskService;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class DatasourceReferenceIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    DatasourceReferenceGuard guard;DaDatasourceServiceImpl service;DaDatasourceMapper mapper;IDaDatasourceProjectRelService relations;DppEtlTaskService tasks;IRedisService redis;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_DATASOURCE_REFERENCE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_datasource_reference_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        guard=new DatasourceReferenceGuard(jdbc);service=new DaDatasourceServiceImpl();mapper=mock(DaDatasourceMapper.class);relations=mock(IDaDatasourceProjectRelService.class);tasks=mock(DppEtlTaskService.class);redis=mock(IRedisService.class);
        ReflectionTestUtils.setField(service,"datasourceReferences",guard);ReflectionTestUtils.setField(service,"daDatasourceMapper",mapper);ReflectionTestUtils.setField(service,"daDatasourceProjectRelService",relations);ReflectionTestUtils.setField(service,"dppEtlTaskService",tasks);ReflectionTestUtils.setField(service,"redisService",redis);
    }
    @AfterEach void close(){manager.rollback(transaction);fixture.destroy();}
    void copy(String table,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" ORDER BY id LIMIT 1"));row.put("id",900001L);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    @Test void activeAndDisabledAssetReferencesBlockRemoval(){copy("da_asset",Map.of("datasource_id",900001L,"valid_flag","0"));assertTrue(guard.references(900001L).containsKey("da_asset"));assertThrows(IllegalArgumentException.class,()->service.removeDaDatasource(List.of(900001L)));verifyNoInteractions(mapper,relations,tasks);}
    @Test void RetiredModelIdentityIsStillReferenced(){copy("dp_model",Map.of("datasource_id",900001L,"del_flag","1"));assertTrue(guard.references(900001L).containsKey("dp_model"));}
    @Test void metadataAndQualityObjectReferencesAreDetected(){copy("mc_task",Map.of("datasource_id",900001L));copy("dpp_quality_task_obj",Map.of("datasource_id",900001L));var r=guard.references(900001L);assertTrue(r.containsKey("mc_task"));assertTrue(r.containsKey("dpp_quality_task_obj"));}
    @Test void managedEngineAndServingTargetBlockRemoval(){copy("dp_warehouse_engine",Map.of("datasource_id",900001L,"catalog_key","reference-fixture","connection_fingerprint","reference-fixture"));jdbc.update("UPDATE dp_warehouse_serving_target SET datasource_id=900001");var r=guard.references(900001L);assertTrue(r.containsKey("dp_warehouse_engine"));assertTrue(r.containsKey("dp_warehouse_serving_target"));}
    @Test void apiReferencesAreParsedStructurally(){copy("ds_api",Map.of("config_json","{\"sourceId\":\"900001\"}","api_url","/reference-fixture","api_version","fixture"));assertTrue(guard.references(900001L).containsKey("ds_api"));}
    @Test void developmentDraftAndHistoryRemainReferences(){copy("dpp_etl_task",Map.of("draft_json","{\"nodes\":[{\"parameters\":\"{\\\"datasourceId\\\":900001}\"}]}","code","reference-fixture"));copy("dpp_etl_node_log",Map.of("parameters","{\"datasourceId\":900001}","code","reference-fixture"));var r=guard.references(900001L);assertTrue(r.containsKey("dpp_etl_task"));assertTrue(r.containsKey("dpp_etl_node_log"));}
    @Test void mixedDeleteChecksEveryIdBeforeAnyMutation(){copy("da_asset",Map.of("datasource_id",900001L));assertThrows(IllegalArgumentException.class,()->service.removeDaDatasource(List.of(900002L,900001L)));assertThrows(IllegalArgumentException.class,()->service.removeDaDatasourceDppOrDa(List.of(900002L,900001L),0L));verifyNoInteractions(mapper,relations,tasks);}
    @Test void unreferencedConnectionReachesMetadataDeleteOnly(){assertTrue(guard.references(900002L).isEmpty());service.removeDaDatasource(List.of(900002L));verify(mapper).deleteBatchIds(List.of(900002L));verify(redis).fenceAndEvictDatasource(eq("900002"),anyString());verifyNoInteractions(relations,tasks);}
    @Test void malformedPersistedJsonRejectsDeletionBeforeMutation(){jdbc.update("UPDATE dpp_etl_node SET parameters='malformed' WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM dpp_etl_node) t)");assertThrows(IllegalArgumentException.class,()->service.removeDaDatasource(List.of(900002L)));verifyNoInteractions(mapper,relations,tasks);}
    @Test void originalHiveConnectionHasMultipleIndependentReferences(){var r=guard.references(9L);assertTrue(r.containsKey("dp_warehouse_engine"));assertTrue(r.containsKey("da_asset"));assertTrue(r.containsKey("dpp_quality_task_obj"));assertTrue(r.containsKey("dpp_quality_execution_snapshot"));}
}

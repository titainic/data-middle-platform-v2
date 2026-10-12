package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual MySQL transactions and retained metadata; Hive is an in-memory test double. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class WarehouseRecoveryMysqlIntegrationTest {
    JdbcTemplate jdbc;
    WarehouseBatchExecutor executor;
    WarehouseBatchRegistry registry;
    WarehouseService models;
    DataSourceFactory factory;
    IRedisService redis;
    WarehouseDefinition definition;
    String batchId;
    long taskId,instanceId,modelId,spaceId;
    final AtomicReference<String> owner=new AtomicReference<>("owner");
    final AtomicInteger hiveRequests=new AtomicInteger();
    final List<String> retainedOutput=new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("QDATA_RELIABILITY_URL");assertNotNull(url);assertTrue(url.contains("/qdata_reliability_"));
        var datasource=new DriverManagerDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"));
        jdbc=spy(new JdbcTemplate(datasource));
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(datasource));
        registry=mock(WarehouseBatchRegistry.class);models=mock(WarehouseService.class);
        factory=mock(DataSourceFactory.class);redis=mock(IRedisService.class);
        executor=new WarehouseBatchExecutor(jdbc,transaction,registry,models,factory,redis);
        taskId=System.nanoTime();instanceId=taskId+1;modelId=taskId+2;spaceId=taskId+3;batchId=UUID.randomUUID().toString();
        definition=new WarehouseDefinition();definition.setId(modelId);definition.setRevision(1);definition.setLayer("DWD");
        definition.setDatabaseName("dwd_iot_db");definition.setTableName("retained_"+taskId);definition.setDatasourceId(9L);
        var column=new WarehouseDefinition.Column();column.setName("device_id");column.setType("STRING");column.setSource("fixture");definition.getColumns().add(column);
        Map<String,Object> batch=template("dp_warehouse_batch");
        batch.putAll(Map.of("id",batchId,"space_id",spaceId,"task_id",taskId,"task_version",1,"status","READY","request_key",batchId.replace("-","")+batchId.replace("-",""),"canonical_key",UUID.randomUUID().toString().replace("-",""),"create_by","reliability-test"));
        batch.put("active_instance_id",null);batch.put("lease_owner",null);batch.put("retry_of",null);batch.put("finished_at",null);insert("dp_warehouse_batch",batch);
        var instance=template("dpp_etl_task_instance");instance.putAll(Map.of("id",instanceId,"task_id",taskId,"task_version",1,"status","1","del_flag","0"));insert("dpp_etl_task_instance",instance);
        for(int ordinal=0;ordinal<2;ordinal++) {
            var step=template("dp_warehouse_batch_step");step.putAll(Map.of("batch_id",batchId,"ordinal",ordinal,"model_id",modelId+ordinal,"node_code","node"+ordinal,"definition_json",JSON.toJSONString(definition),"dependencies_json",ordinal==0?"[]":"[0]","status","QUEUED"));
            step.put("instance_id",null);step.put("finished_at",null);step.put("output_fingerprint",null);insert("dp_warehouse_batch_step",step);
        }
        var node=template("dpp_etl_node_instance");node.putAll(Map.of("id",instanceId+100,"task_instance_id",instanceId,"status","1"));insert("dpp_etl_node_instance",node);
        when(registry.internal(batchId)).thenAnswer(call->jdbc.queryForMap("SELECT * FROM dp_warehouse_batch WHERE id=?",batchId));
        when(redis.renewLock(anyString(),anyString(),anyLong())).thenAnswer(call->Objects.equals(call.getArgument(1),owner.get()));
        when(redis.get(anyString())).thenAnswer(call->owner.get());
        when(models.getInternal(modelId)).thenAnswer(call->JSON.parseObject(JSON.toJSONString(definition),WarehouseDefinition.class));
        doAnswer(call->{hiveRequests.incrementAndGet();retainedOutput.add("retained-device");return null;}).when(models).execute(any(),eq("LOAD"),eq(batchId));
        var query=mock(DbQuery.class);var connection=mock(Connection.class);var statement=mock(Statement.class);var result=mock(ResultSet.class);
        when(factory.createDbQuery(any())).thenReturn(query);when(models.property(any())).thenReturn(new DbQueryProperty("Hive","never-connect","test","",10000,"dwd_iot_db",null));
        when(query.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);when(result.next()).thenReturn(true,false);when(result.getString(1)).thenReturn("retained-device");
    }
    Map<String,Object> template(String table){return new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM template_"+table+" LIMIT 1"));}
    void insert(String table,Map<String,Object> row){jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    String batchStatus(){return jdbc.queryForObject("SELECT status FROM dp_warehouse_batch WHERE id=?",String.class,batchId);}
    String stepStatus(int ordinal){return jdbc.queryForObject("SELECT status FROM dp_warehouse_batch_step WHERE batch_id=? AND ordinal=?",String.class,batchId,ordinal);}
    String runStatus(){return jdbc.queryForObject("SELECT status FROM dp_warehouse_run WHERE batch_id=? AND model_id=?",String.class,batchId,modelId);}
    void execute(){executor.execute(batchId,0,taskId,instanceId,"node0","owner");}
    void running(){
        jdbc.update("UPDATE dp_warehouse_batch SET status='RUNNING',active_instance_id=?,lease_owner='owner' WHERE id=?",instanceId,batchId);
        jdbc.update("UPDATE dp_warehouse_batch_step SET status='RUNNING',instance_id=? WHERE batch_id=? AND ordinal=0",instanceId,batchId);
        jdbc.update("INSERT INTO dp_warehouse_run(batch_id,model_id,revision,mode,status,definition_json,create_by) VALUES (?, ?, 1, 'MANAGED','RUNNING',?,'reliability-test')",batchId,modelId,JSON.toJSONString(definition));
    }
    void assertUnknown(){assertEquals("UNKNOWN",batchStatus());assertEquals("UNKNOWN",stepStatus(0));assertEquals("BLOCKED",stepStatus(1));assertEquals("UNKNOWN",runStatus());}

    @Test void runLogFailureRollsBackTheClaimBeforeHive(){
        doAnswer(call->{if(((String)call.getArgument(0)).startsWith("INSERT INTO dp_warehouse_run"))throw new TransientDataAccessResourceException("injected run-log outage");return call.callRealMethod();}).when(jdbc).update(anyString(),any(Object[].class));
        assertThrows(TransientDataAccessResourceException.class,this::execute);
        assertEquals("READY",batchStatus());assertEquals("QUEUED",stepStatus(0));assertEquals(0,hiveRequests.get());verifyNoInteractions(factory);
    }
    @Test void outputWithoutPersistedSuccessBecomesUnknownAndCannotBeRewritten(){
        doAnswer(call->{if(((String)call.getArgument(0)).startsWith("UPDATE dp_warehouse_batch_step SET status='SUCCESS'"))throw new TransientDataAccessResourceException("injected result persistence outage");return call.callRealMethod();}).when(jdbc).update(anyString(),any(Object[].class));
        assertThrows(TransientDataAccessResourceException.class,this::execute);executor.fail(batchId,taskId,instanceId,"result not committed");
        assertUnknown();assertThrows(IllegalArgumentException.class,this::execute);assertEquals(1,hiveRequests.get());assertEquals(List.of("retained-device"),retainedOutput);
        assertThrows(IllegalArgumentException.class,()->executor.complete(batchId,taskId,instanceId,"owner"));
    }
    @Test void completeMetadataOutageCanBeReconciledAfterStorageReturns(){
        AtomicBoolean unavailable=new AtomicBoolean(true);
        doAnswer(call->{String sql=call.getArgument(0);if(unavailable.get() && (sql.startsWith("UPDATE dp_warehouse_batch_step SET status='SUCCESS'")||sql.startsWith("UPDATE dp_warehouse_batch_step SET status=?,")))throw new TransientDataAccessResourceException("injected metadata outage");return call.callRealMethod();}).when(jdbc).update(anyString(),any(Object[].class));
        var error=assertThrows(TransientDataAccessResourceException.class,this::execute);assertEquals(1,error.getSuppressed().length);
        assertEquals("RUNNING",stepStatus(0));unavailable.set(false);owner.set(null);executor.reconcile(spaceId);
        assertUnknown();assertEquals(1,hiveRequests.get());assertEquals(List.of("retained-device"),retainedOutput);
        assertEquals("6",jdbc.queryForObject("SELECT status FROM dpp_etl_task_instance WHERE id=?",String.class,instanceId));
    }
    @Test void reconciliationIsAtomicWhenALaterMetadataWriteFails(){
        running();owner.set(null);AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{if(fail.get() && ((String)call.getArgument(0)).startsWith("UPDATE dp_warehouse_run SET status='UNKNOWN'"))throw new TransientDataAccessResourceException("injected reconcile outage");return call.callRealMethod();}).when(jdbc).update(anyString(),any(Object[].class));
        assertThrows(TransientDataAccessResourceException.class,()->executor.reconcile(spaceId));
        assertEquals("RUNNING",batchStatus());assertEquals("RUNNING",stepStatus(0));assertEquals("QUEUED",stepStatus(1));assertEquals("RUNNING",runStatus());
        fail.set(false);executor.reconcile(spaceId);executor.reconcile(spaceId);assertUnknown();verifyNoInteractions(factory);
    }
    @Test void anotherValidInstanceCannotBlockTheOwnersQueuedNodes(){
        running();var other=template("dpp_etl_task_instance");other.putAll(Map.of("id",instanceId+200,"task_id",taskId,"task_version",1,"del_flag","0"));insert("dpp_etl_task_instance",other);
        executor.fail(batchId,taskId,instanceId+200,"not the active instance");assertEquals("RUNNING",batchStatus());assertEquals("QUEUED",stepStatus(1));verifyNoInteractions(factory);
    }
    @Test void liveOwnerAndUnavailableRedisCannotBeMistakenForLostOwnership(){
        running();executor.reconcile(spaceId);assertEquals("RUNNING",batchStatus());
        when(redis.get(anyString())).thenThrow(new IllegalStateException("redis temporarily unavailable"));executor.reconcile(spaceId);
        assertEquals("RUNNING",batchStatus());assertEquals("RUNNING",runStatus());verifyNoInteractions(factory);
    }
    @Test void lateHiveCompletionAfterOwnerLossRetainsOutputWithoutPublishing() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{hiveRequests.incrementAndGet();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));retainedOutput.add("late-retained-device");return null;}).when(models).execute(any(),eq("LOAD"),eq(batchId));
        var worker=Executors.newSingleThreadExecutor();
        try {
            var pending=worker.submit(this::execute);assertTrue(entered.await(10,TimeUnit.SECONDS));owner.set(null);executor.reconcile(spaceId);assertUnknown();
            release.countDown();assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));
            assertUnknown();assertEquals(List.of("late-retained-device"),retainedOutput);assertEquals(1,hiveRequests.get());verifyNoInteractions(factory);
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void missingOwnerTokenDoesNotKeepAnOrphanPermanentlyRunning(){
        running();jdbc.update("UPDATE dp_warehouse_batch SET lease_owner=NULL WHERE id=?",batchId);owner.set(null);executor.reconcile(spaceId);assertUnknown();verifyNoInteractions(factory);
    }
}

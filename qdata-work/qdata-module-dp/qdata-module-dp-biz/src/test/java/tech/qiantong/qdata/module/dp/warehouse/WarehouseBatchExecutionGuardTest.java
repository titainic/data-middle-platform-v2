package tech.qiantong.qdata.module.dp.warehouse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WarehouseBatchExecutionGuardTest {
    static final String ID="7667f1f5-394e-43a4-889b-776990a270fd";
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final WarehouseBatchRegistry registry=mock(WarehouseBatchRegistry.class);
    final WarehouseService models=mock(WarehouseService.class);
    final DataSourceFactory factory=mock(DataSourceFactory.class);
    final IRedisService redis=mock(IRedisService.class);
    final WarehouseBatchExecutor executor=new WarehouseBatchExecutor(jdbc,mock(TransactionTemplate.class),registry,models,factory,redis);
    void setup(String status,String stepStatus) {
        when(redis.renewLock(anyString(),anyString(),anyLong())).thenReturn(true);
        when(registry.internal(ID)).thenReturn(Map.of("task_id",31L,"task_version",1,"status",status));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(100L),eq(31L),eq(1))).thenReturn(1L);
        when(jdbc.queryForList(anyString(),eq(ID),eq(0))).thenReturn(List.of(Map.of("node_code","node","status",stepStatus,"row_count",5000L,"dependencies_json","[]")));
    }
    @Test void expiredLeaseCannotOpenHiveOrClaimMetadata() {
        assertThrows(IllegalArgumentException.class,()->executor.execute(ID,0,31,100,"node","owner"));
        verifyNoInteractions(jdbc,registry,models,factory);
    }
    @Test void successfulBatchReplayHasNoHiveWriteOrNewPhysicalVersion() {
        setup("SUCCESS","SUCCESS");var result=executor.execute(ID,0,31,100,"node","owner");
        assertTrue(result.reused());assertEquals(5000,result.rows());verifyNoInteractions(models,factory);
    }
    @Test void failedOutputCannotBeRetriedInTheSameTarget() {
        setup("FAILED","FAILED");assertThrows(IllegalArgumentException.class,()->executor.execute(ID,0,31,100,"node","owner"));verifyNoInteractions(models,factory);
    }
    @Test void copiedNodeCannotAddressAnotherManagedStep() {
        setup("SUCCESS","SUCCESS");assertThrows(IllegalArgumentException.class,()->executor.execute(ID,0,31,100,"copied-node","owner"));verifyNoInteractions(models,factory);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FAILED","UNKNOWN","BLOCKED"})
    void terminalDependencyIsPersistedAsBlockedWithoutOpeningHive(String parentStatus) {
        dependency(parentStatus);when(jdbc.update(startsWith("UPDATE dp_warehouse_batch_step SET status='BLOCKED'"),any(),eq(ID),eq(0),eq(ID),eq(100L),eq("owner"))).thenReturn(1);
        assertThrows(tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution.DependencyBlocked.class,()->executor.execute(ID,0,31,100,"node","owner"));
        verifyNoInteractions(models,factory);
    }
    @Test void dependencyBlockCannotContinueIfItsStatusWasNotSaved() {
        dependency("FAILED");var failure=assertThrows(IllegalArgumentException.class,()->executor.execute(ID,0,31,100,"node","owner"));
        assertTrue(failure.getMessage().contains("未能可靠登记"));verifyNoInteractions(models,factory);
    }
    @Test void queuedDependencyIsAnExecutionOrderingErrorNotARecordedBlock() {
        dependency("QUEUED");assertThrows(IllegalArgumentException.class,()->executor.execute(ID,0,31,100,"node","owner"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));verifyNoInteractions(models,factory);
    }
    private void dependency(String status) {
        setup("RUNNING","QUEUED");
        when(jdbc.queryForList(anyString(),eq(ID),eq(0))).thenReturn(List.of(Map.of("node_code","node","status","QUEUED","row_count",0L,"dependencies_json","[1]")));
        when(jdbc.queryForObject(startsWith("SELECT status FROM dp_warehouse_batch_step"),eq(String.class),eq(ID),eq(1))).thenReturn(status);
    }
    @Test void liveOtherWorkerLeaseIsPreservedDuringReconciliation() {
        when(jdbc.queryForList(anyString(),eq(1L))).thenReturn(List.of(Map.of("id",ID,"task_id",31L,"lease_owner","active-owner","active_instance_id",100L)));
        when(redis.get("dpp:data-development:run:31")).thenReturn("active-owner");executor.reconcile(1);
        verify(jdbc,never()).update(anyString(),any(Object[].class));verifyNoInteractions(models,factory);
    }
}

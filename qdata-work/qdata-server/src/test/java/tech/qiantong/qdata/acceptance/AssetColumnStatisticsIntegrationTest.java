package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.da.service.assetColumn.AssetColumnStatistics;
import tech.qiantong.qdata.module.da.service.assetColumn.impl.DaAssetColumnServiceImpl;
import tech.qiantong.qdata.module.da.dal.mapper.assetColumn.DaAssetColumnMapper;
import tech.qiantong.qdata.module.da.dal.dataobject.assetColumn.DaAssetColumnDO;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.DaAssetColumnSaveReqVO;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class AssetColumnStatisticsIntegrationTest {
    SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;AssetColumnStatistics statistics;
    @BeforeEach void setup(){String url=System.getenv("QDATA_ASSET_COUNT_URL");assertNotNull(url);assertTrue(url.contains("/qdata_asset_count_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);transaction=manager.getTransaction(new DefaultTransactionDefinition());statistics=new AssetColumnStatistics(jdbc);copy(jdbc,"da_asset",189,990144,Map.of("field_count",99L));copy(jdbc,"da_asset_column",4044,991144,Map.of("asset_id",990144L));}
    @AfterEach void close(){if(!transaction.isCompleted())manager.rollback(transaction);source.destroy();}
    static void copy(JdbcTemplate jdbc,String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    long count(long id){return jdbc.queryForObject("SELECT field_count FROM da_asset WHERE id=?",Long.class,id);}
    @Test void countIsRecomputedFromCurrentCatalogFields(){statistics.lock(List.of(990144L));statistics.refresh(List.of(990144L));assertEquals(1,count(990144));}
    @Test void softDeletedFieldsAreExcluded(){jdbc.update("UPDATE da_asset_column SET del_flag='1' WHERE id=991144");statistics.lock(List.of(990144L));statistics.refresh(List.of(990144L));assertEquals(0,count(990144));}
    @Test void allDisplayedFieldsAreCountedRegardlessOfValidFlag(){jdbc.update("UPDATE da_asset_column SET valid_flag='0' WHERE id=991144");statistics.lock(List.of(990144L));statistics.refresh(List.of(990144L));assertEquals(1,count(990144));}
    @Test void otherAssetAndAuditFieldsRemainUntouched(){var before=jdbc.queryForMap("SELECT creator_id,create_by,create_time,updater_id,update_by,update_time FROM da_asset WHERE id=990144");long other=count(189);statistics.lock(List.of(990144L));statistics.refresh(List.of(990144L));assertEquals(other,count(189));assertEquals(before,jdbc.queryForMap("SELECT creator_id,create_by,create_time,updater_id,update_by,update_time FROM da_asset WHERE id=990144"));}
    @Test void missingParentIsRejected(){assertThrows(IllegalArgumentException.class,()->statistics.lock(List.of(999999999L)));}
    @Test void countAndFieldChangeRollbackTogether(){statistics.lock(List.of(990144L));copy(jdbc,"da_asset_column",4044,991145,Map.of("asset_id",990144L));statistics.refresh(List.of(990144L));assertEquals(2,count(990144));manager.rollback(transaction);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_column WHERE id=991145",Long.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM da_asset WHERE id=990144",Long.class));}
    @Test void updatesOutsideATransactionAreRejected(){manager.rollback(transaction);assertThrows(IllegalStateException.class,()->statistics.lock(List.of(189L)));assertThrows(IllegalStateException.class,()->statistics.refresh(List.of(189L)));}
    @Test void normalFieldCreationUpdatesParentCountInItsTransaction(){var service=new DaAssetColumnServiceImpl();var mapper=mock(DaAssetColumnMapper.class);ReflectionTestUtils.setField(service,"daAssetColumnMapper",mapper);ReflectionTestUtils.setField(service,"statistics",statistics);when(mapper.insert(any(DaAssetColumnDO.class))).thenAnswer(call->{DaAssetColumnDO row=call.getArgument(0);copy(jdbc,"da_asset_column",4044,991145,Map.of("asset_id",row.getAssetId(),"column_name",row.getColumnName()));row.setId(991145L);return 1;});var request=new DaAssetColumnSaveReqVO();request.setAssetId("990144");request.setColumnName("new_field");request.setColumnType("STRING");assertEquals(991145L,service.createDaAssetColumn(request));assertEquals(2,count(990144));}
    @Test void concurrentWritersKeepBothFieldsEvenAfterAnEarlierSnapshot() throws Exception {
        var concurrentSource=new DriverManagerDataSource(System.getenv("QDATA_ASSET_COUNT_URL"),"root",System.getenv("QDATA_NAMESPACE_PASSWORD"));var db=new JdbcTemplate(concurrentSource);var stats=new AssetColumnStatistics(db);var transactions=new TransactionTemplate(new DataSourceTransactionManager(concurrentSource));long asset=System.currentTimeMillis();copy(db,"da_asset",189,asset,Map.of("field_count",0L));
        var locked=new CountDownLatch(1);var snapshot=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{
            var first=executor.submit(()->transactions.execute(status->{stats.lock(List.of(asset));copy(db,"da_asset_column",4044,asset+1,Map.of("asset_id",asset,"column_name","concurrent_a"));stats.refresh(List.of(asset));locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var second=executor.submit(()->transactions.execute(status->{db.queryForObject("SELECT COUNT(*) FROM da_asset_column WHERE asset_id=?",Long.class,asset);snapshot.countDown();stats.lock(List.of(asset));copy(db,"da_asset_column",4044,asset+2,Map.of("asset_id",asset,"column_name","concurrent_b"));stats.refresh(List.of(asset));return null;}));
            assertTrue(snapshot.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->second.get(200,TimeUnit.MILLISECONDS));release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            assertEquals(2L,db.queryForObject("SELECT field_count FROM da_asset WHERE id=?",Long.class,asset));assertEquals(2L,db.queryForObject("SELECT COUNT(*) FROM da_asset_column WHERE asset_id=? AND del_flag='0'",Long.class,asset));
        }finally{release.countDown();executor.shutdownNow();}
    }
}

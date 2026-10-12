package tech.qiantong.qdata.module.ds.service.api.impl;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tech.qiantong.qdata.common.database.*;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.PageResult;
import tech.qiantong.qdata.common.exception.DependencyUnavailableException;
import tech.qiantong.qdata.common.httpClient.DependencyGuard;
import tech.qiantong.qdata.module.dp.api.warehouse.ServingRequestException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class ServiceSqlExecutorTest {
    final String run=UUID.randomUUID().toString();
    final String select="SELECT device_id,medium,reading,event_date,note FROM p6_service_sql_20260922 WHERE run_id=:run ORDER BY device_id";
    String url,password;DataSourceFactory factory;ServiceSqlExecutor executor;
    DbQueryProperty property(String name){var p=new DbQueryProperty("MySql","127.0.0.1","fixture","fixture-only",3307,name,"");p.setConfig(Map.of());p.setDatasourceConfig(Map.of());return p;}
    Connection connect()throws SQLException{return DriverManager.getConnection(url,"root",password);}
    @BeforeEach void setup()throws Exception {
        url=System.getenv("QDATA_NAMESPACE_URL");password=System.getenv("QDATA_NAMESPACE_PASSWORD");assertNotNull(url);assertTrue(url.contains("/qdata_")&&!url.contains("/qdata_v2?"));
        try(var connection=connect();var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS p6_service_sql_20260922(run_id VARCHAR(36),device_id VARCHAR(8),medium VARCHAR(8),reading DECIMAL(38,18),event_date DATE,note VARCHAR(64),PRIMARY KEY(run_id,device_id))");
            try(var insert=connection.prepareStatement("INSERT INTO p6_service_sql_20260922 VALUES(?,?,?,?,?,?)")) {
                for(int i=0;i<3;i++){insert.setString(1,run);insert.setString(2,"00"+(i+1));insert.setString(3,List.of("water","air","soil").get(i));insert.setBigDecimal(4,new BigDecimal(List.of("2.680000000000000001","0","99.99").get(i)));insert.setString(5,List.of("2024-01-31","2024-02-01","2024-02-29").get(i));insert.setNull(6,Types.VARCHAR);insert.executeUpdate();}
            }
        }
        factory=mock(DataSourceFactory.class);
        when(factory.createDbQuery(any())).thenAnswer(call->{var query=mock(DbQuery.class);when(query.getConnection()).thenAnswer(ignored->connect());return query;});
        executor=new ServiceSqlExecutor(factory,Duration.ofSeconds(4),2,10000,new DependencyGuard());
    }
    @AfterEach void close(){executor.close();}
    Object query(String type,int page,int size){return executor.query(1,property("fixture"),select,Map.of("run",run),type,page,size);}
    @Test void listRetainsStringIdentityDecimalsDatesAndNulls(){var rows=(List<Map<String,Object>>)query("2",1,20);assertEquals(3,rows.size());assertEquals("001",rows.get(0).get("device_id"));assertEquals(new BigDecimal("2.680000000000000001"),rows.get(0).get("reading"));assertEquals("2024-02-29",rows.get(2).get("event_date").toString());assertTrue(rows.get(0).containsKey("note"));assertNull(rows.get(0).get("note"));}
    @Test void pagingCountsAndReturnsSecondPage(){var page=(PageResult<Map<String,Object>>)query("3",2,2);assertEquals(3,page.getTotal());assertEquals(1,page.getData().size());assertEquals("003",page.getData().get(0).get("device_id"));assertEquals(2,page.getPageNum());}
    @Test void detailAndMissingDetailKeepOriginalShapes(){var one=(Map<String,Object>)query("1",1,20);assertEquals("001",one.get("device_id"));assertNull(executor.query(1,property("fixture"),select,Map.of("run","missing"),"1",1,20));}
    @Test void invalidPagesDoNotReachDependency(){for(int[] args:List.of(new int[]{0,20},new int[]{1,201},new int[]{100001,20}))assertEquals(400,assertThrows(ServingRequestException.class,()->query("3",args[0],args[1])).getStatus());verifyNoInteractions(factory);}
    @Test void oversizedListIsRejectedWithoutSilentlyTruncating(){executor.close();executor=new ServiceSqlExecutor(factory,Duration.ofSeconds(4),2,2,new DependencyGuard());assertEquals(400,assertThrows(ServingRequestException.class,()->query("2",1,20)).getStatus());var page=(PageResult<Map<String,Object>>)query("3",1,2);assertEquals(3,page.getTotal());assertEquals(2,page.getData().size());}
    @Test void invalidSavedSqlDoesNotTripHealthyDatasource(){for(int i=0;i<6;i++)assertEquals(400,assertThrows(ServingRequestException.class,()->executor.query(1,property("fixture"),"SELECT nonexistent_p6_column FROM p6_service_sql_20260922",Map.of(),"2",1,20)).getStatus());assertEquals(3,((List<?>)query("2",1,20)).size());}
    @Test void oversizedCellFailsWithoutReturningATruncatedValue(){var error=assertThrows(ServingRequestException.class,()->executor.query(1,property("fixture"),"SELECT REPEAT('x',16777217) AS payload",Map.of(),"1",1,20));assertEquals(400,error.getStatus());assertTrue(error.getMessage().contains("16MiB"));}
    @Test void mysqlUrlGetsServiceSpecificTimeouts(){query("1",1,20);var property=org.mockito.ArgumentCaptor.forClass(DbQueryProperty.class);verify(factory).createDbQuery(property.capture());String jdbc=property.getValue().trainToJdbcUrl();assertTrue(jdbc.contains("connectTimeout=5000"));assertTrue(jdbc.contains("socketTimeout=2000"));assertTrue(jdbc.contains("autoReconnect=false"));}
    @Test void jdbcTimeoutBoundsSlowQuery(){executor.close();executor=new ServiceSqlExecutor(factory,Duration.ofSeconds(4),1,10000,new DependencyGuard());long start=System.nanoTime();assertThrows(DependencyUnavailableException.class,()->executor.query(1,property("fixture"),"SELECT SLEEP(3) AS slow",Map.of(),"1",1,20));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2500);}
    @Test void totalDeadlineCancelsAndOtherSourceStillWorks(){executor.close();executor=new ServiceSqlExecutor(factory,Duration.ofMillis(180),3,10000,new DependencyGuard());long start=System.nanoTime();assertTrue(assertThrows(DependencyUnavailableException.class,()->executor.query(1,property("fixture"),"SELECT SLEEP(2) AS slow",Map.of(),"1",1,20)).getMessage().contains("总时间"));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1000);assertEquals(3,((List<?>)executor.query(2,property("fixture"),select,Map.of("run",run),"2",1,20)).size());}
    @Test void uninterruptibleConnectionRetainsItsSlotUntilDriverReturns()throws Exception {
        executor.close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        when(factory.createDbQuery(any())).thenAnswer(call->{DbQueryProperty p=call.getArgument(0);var db=mock(DbQuery.class);when(db.getConnection()).thenAnswer(ignored->{if("blocked".equals(p.getDbName())){calls.incrementAndGet();entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignore){}}return connect();});return db;});
        executor=new ServiceSqlExecutor(factory,Duration.ofMillis(180),2,10000,new DependencyGuard(4,1,5,Duration.ofSeconds(30),System::nanoTime));
        try {
            assertThrows(DependencyUnavailableException.class,()->executor.query(1,property("blocked"),select,Map.of("run",run),"2",1,20));assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertTrue(assertThrows(DependencyUnavailableException.class,()->executor.query(1,property("blocked"),select,Map.of("run",run),"2",1,20)).getMessage().contains("繁忙"));assertEquals(1,calls.get());
            assertEquals(3,((List<?>)executor.query(2,property("fixture"),select,Map.of("run",run),"2",1,20)).size());
        } finally {release.countDown();}
    }
}

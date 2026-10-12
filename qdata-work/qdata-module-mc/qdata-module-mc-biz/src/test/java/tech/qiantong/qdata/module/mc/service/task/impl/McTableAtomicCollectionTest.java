package tech.qiantong.qdata.module.mc.service.task.impl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.TableProcessResult;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnSaveReqVO;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McColumnDO;
import tech.qiantong.qdata.module.mc.service.metadata.IMcColumnService;
import tech.qiantong.qdata.module.mc.service.columnLog.IMcColumnLogService;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_MC_FENCE_URL",matches=".+")
class McTableAtomicCollectionTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean(destroyMethod="destroy") SingleConnectionDataSource dataSource(){
   String url=System.getenv("QDATA_MC_FENCE_URL");
   if(!url.split("\\?")[0].matches("jdbc:mysql://127\\.0\\.0\\.1:3307/qdata_metadata_fence_[a-z0-9_]+"))throw new IllegalArgumentException("Only isolated fixture allowed");
   return new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);
  }
  @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
  @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
  @Bean McTableTxService transactions(){return new McTableTxService();}
 }
 AnnotationConfigApplicationContext context;JdbcTemplate jdbc;McTaskServiceImpl service;
 IMcColumnService columns;IMcColumnLogService history;String failColumn,failLog;
 @BeforeEach void setup()throws Exception {
  context=new AnnotationConfigApplicationContext(Config.class);jdbc=context.getBean(JdbcTemplate.class);
  jdbc.execute("CREATE TEMPORARY TABLE p6_mc_atomic_probe(kind VARCHAR(20),name VARCHAR(40)) ENGINE=InnoDB");
  service=new McTaskServiceImpl();columns=mock(IMcColumnService.class);history=mock(IMcColumnLogService.class);
  inject("mcTableTxService",context.getBean(McTableTxService.class));inject("mcColumnService",columns);inject("mcColumnLogService",history);
  when(columns.createMcColumnList(any())).thenAnswer(call->{
   List<McColumnSaveReqVO> values=call.getArgument(0);String name=values.get(0).getColumnName();jdbc.update("INSERT INTO p6_mc_atomic_probe VALUES('column',?)",name);
   if(name.equals(failColumn))throw new IllegalStateException("Injected column write failure");var column=new McColumnDO();column.setColumnName(name);return List.of(column);
  });
  when(history.createMcColumnLog(anyList())).thenAnswer(call->{
   List<McColumnDO> values=call.getArgument(0);String name=values.get(0).getColumnName();jdbc.update("INSERT INTO p6_mc_atomic_probe VALUES('column_log',?)",name);
   if(name.equals(failLog))throw new IllegalStateException("Injected field history failure");return 1L;
  });
 }
 void inject(String name,Object value)throws Exception{var f=McTaskServiceImpl.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value);}
 @AfterEach void close(){context.close();}
 TableProcessResult collect(String name){return service.persistCollectedTable(()->{
  jdbc.update("INSERT INTO p6_mc_atomic_probe VALUES('table',?)",name);jdbc.update("INSERT INTO p6_mc_atomic_probe VALUES('table_log',?)",name);
  var column=new McColumnSaveReqVO();column.setColumnName(name);return new TableProcessResult(1L,0L,1L,List.of(column));
 });}
 long count(String name){return jdbc.queryForObject("SELECT COUNT(*) FROM p6_mc_atomic_probe WHERE name=?",Long.class,name);}
 @Test void fieldFailureRollsBackTableAndItsHistory(){failColumn="air";assertThrows(IllegalStateException.class,()->collect("air"));assertEquals(0,count("air"));verifyNoInteractions(history);}
 @Test void fieldHistoryFailureRollsBackTableAndAllColumns(){failLog="soil";assertThrows(IllegalStateException.class,()->collect("soil"));assertEquals(0,count("soil"));}
 @Test void failedMiddleTableDoesNotUndoCommittedNeighbours(){failColumn="air";assertEquals(1L,collect("water").getSuccessCount());assertThrows(IllegalStateException.class,()->collect("air"));assertEquals(1L,collect("soil").getSuccessCount());assertEquals(4,count("water"));assertEquals(0,count("air"));assertEquals(4,count("soil"));}
 @Test void successfulRetryCommitsExactlyOneCompleteTable(){failColumn="air";assertThrows(IllegalStateException.class,()->collect("air"));failColumn=null;collect("air");assertEquals(4,count("air"));}
 @Test void unchangedMetadataDoesNotCreateNewColumnHistory(){var result=new TableProcessResult(0L,0L,1L,List.of());assertSame(result,service.persistCollectedTable(()->result));verifyNoInteractions(columns,history);}
}

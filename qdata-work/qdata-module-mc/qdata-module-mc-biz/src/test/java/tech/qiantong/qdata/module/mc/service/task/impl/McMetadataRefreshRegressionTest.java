package tech.qiantong.qdata.module.mc.service.task.impl;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.*;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.*;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McDbDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskInstanceDO;
import tech.qiantong.qdata.module.mc.dal.mapper.metadata.McDbMapper;
import tech.qiantong.qdata.module.mc.service.metadata.*;
import tech.qiantong.qdata.module.mc.service.metadata.dialect.*;
import tech.qiantong.qdata.module.mc.service.tableLog.IMcTableLogService;
import tech.qiantong.qdata.module.mc.service.task.IMcTaskInstanceLogService;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McMetadataRefreshRegressionTest {
 private void inject(McTaskServiceImpl service,String name,Object value) throws Exception {
  var f=McTaskServiceImpl.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value);
 }
 private Object call(McTaskServiceImpl service,String name,Class<?>[] types,Object... args) throws Exception {
  var m=McTaskServiceImpl.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(service,args);
 }
 @Test void metricRefreshPreservesPublishedFieldsAndThenBecomesUnchanged() throws Exception {
  var service=new McTaskServiceImpl();var fields=mock(IMcColumnService.class);var tables=mock(IMcTableService.class);var versions=mock(IMcTableLogService.class);var dbMapper=mock(McDbMapper.class);
  inject(service,"mcColumnService",fields);inject(service,"mcTableService",tables);inject(service,"mcTableLogService",versions);inject(service,"mcDbMapper",dbMapper);inject(service,"mcTableTxService",new McTableTxService());inject(service,"redisService",mock(IRedisService.class));inject(service,"mcTaskInstanceLogService",mock(IMcTaskInstanceLogService.class));
  var task=new McTaskRespVO();task.setId(7L);task.setDatasourceId(9L);
  var instance=new McTaskInstanceDO();instance.setId(11L);
  var scope=new McDbSaveReqVO();scope.setId(116L);scope.setDbName("qdata_test");scope.setSchemaName("");
  var candidate=new McTableSaveReqVO();candidate.setId(1800L);candidate.setTaskId(7L);candidate.setDbId(116L);candidate.setDbName("qdata_test");candidate.setSchemaName("");candidate.setTableName("ods_env_water_quality");candidate.setStatus("0");
  var existing=BeanUtils.toBean(candidate,McTableRespVO.class);existing.setStatus("1");existing.setVersion(7);
  var db=new McDbDO();db.setId(116L);when(dbMapper.findById(116L)).thenReturn(db);
  var column=new DbColumn();column.setColName("device_id");column.setDataType("string");
  var dialect=mock(DatabaseDialect.class);var metadata=new DatabaseDialect.TableMetadata();metadata.setTableSize(100);metadata.setRowCount(5000L);when(dialect.getTableMetadata(db,"ods_env_water_quality")).thenReturn(metadata);
  try(var factory=mockStatic(DatabaseDialectFactory.class);var messages=mockStatic(MessageUtils.class)) {
   factory.when(()->DatabaseDialectFactory.getDialect(db)).thenReturn(dialect);
   @SuppressWarnings("unchecked") var incoming=(List<McColumnSaveReqVO>)call(service,"compareAndRecordColumns",new Class<?>[]{McTaskRespVO.class,McTaskInstanceDO.class,McDbSaveReqVO.class,McTableSaveReqVO.class,List.class},task,instance,scope,candidate,List.of(column));
   var governed=BeanUtils.toBean(incoming.get(0),McColumnRespVO.class);governed.setId(900L);governed.setDataElemId(42L);governed.setStatus("1");when(fields.getMcColumnList(any())).thenReturn(List.of(governed));
   var types=new Class<?>[]{McTaskRespVO.class,McTaskInstanceDO.class,McDbSaveReqVO.class,McTableSaveReqVO.class,List.class,List.class};
   var result=(TableProcessResult)call(service,"doProcessSingleTable",types,task,instance,scope,candidate,List.of(existing),List.of(column));
   assertEquals(1L,result.getUpdateCount());assertTrue(result.getMcColumnReqList().isEmpty());assertEquals(100,candidate.getStorageSize());assertEquals(5000L,candidate.getRowCount());assertEquals("1",candidate.getStatus());assertEquals(7,candidate.getVersion());verify(fields,never()).removeMcColumn(any(McColumnRespVO.class));assertEquals(42L,governed.getDataElemId());assertEquals(900L,governed.getId());
   existing.setStorageSize(100);existing.setRowCount(5000L);clearInvocations(tables,versions);
   var repeat=(TableProcessResult)call(service,"doProcessSingleTable",types,task,instance,scope,candidate,List.of(existing),List.of(column));
   assertEquals(0L,repeat.getUpdateCount());assertEquals(7,candidate.getVersion());verify(tables,never()).updateMcTable(any());verify(versions,never()).createMcTableLog(any(McTableSaveReqVO.class));
  }
 }
 @Test void realColumnChangeCarriesStandardAssociationAndPublicationState() throws Exception {
  var service=new McTaskServiceImpl();var req=new McColumnSaveReqVO();req.setColumnName("value");req.setColumnType("decimal(8,2)");var old=new McColumnRespVO();old.setColumnName("value");old.setColumnType("decimal(6,2)");old.setVersion(1);old.setDataElemId(42L);old.setStatus("1");
  var table=new McTableSaveReqVO();var previousTable=new McTableRespVO();previousTable.setVersion(3);boolean changed=(boolean)call(service,"isTableUpdated",new Class<?>[]{McTableSaveReqVO.class,McTableRespVO.class,List.class,List.class},table,previousTable,List.of(req),List.of(old));
  assertTrue(changed);assertEquals(4,table.getVersion());assertEquals(42L,req.getDataElemId());assertEquals("1",req.getStatus());assertEquals(2,req.getVersion());assertTrue(table.getUpdateMsg().contains("value"));
 }
 @Test void metricDetailsDoNotEraseExistingStructureChangeExplanation() throws Exception {
  var service=new McTaskServiceImpl();var mapper=mock(McDbMapper.class);inject(service,"mcDbMapper",mapper);var db=new McDbDO();when(mapper.findById(116L)).thenReturn(db);var dialect=mock(DatabaseDialect.class);var metadata=new DatabaseDialect.TableMetadata();metadata.setTableSize(100);when(dialect.getTableMetadata(db,"t")).thenReturn(metadata);
  var req=new McTableSaveReqVO();req.setUpdateMsg("Column changed;\n");req.setUpdateType("2");var old=new McTableRespVO();old.setDbId(116L);old.setTableName("t");
  try(var factory=mockStatic(DatabaseDialectFactory.class)) {factory.when(()->DatabaseDialectFactory.getDialect(db)).thenReturn(dialect);assertTrue((boolean)call(service,"isTableUpdated2",new Class<?>[]{McTableSaveReqVO.class,McTableRespVO.class},req,old));}
  assertTrue(req.getUpdateMsg().startsWith("Column changed;"));assertEquals("2,4",req.getUpdateType());assertEquals(100,req.getStorageSize());
 }
 @Test void collectionSuspendsOuterTransactionWhileTableTransactionsCommitIndependently() throws Exception {
  var source=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
  var method=McTaskServiceImpl.class.getMethod("runDaDiscoveryTask",Long.class);
  var attribute=source.getTransactionAttribute(method,McTaskServiceImpl.class);
  assertNotNull(attribute);assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED,attribute.getPropagationBehavior());
 }
 @Test void longUnicodeFailureStillPersistsTerminalFailedState() throws Exception {
  var service=new McTaskServiceImpl();var instances=mock(tech.qiantong.qdata.module.mc.service.task.IMcTaskInstanceService.class);inject(service,"mcTaskInstanceService",instances);
  var instance=new McTaskInstanceDO();instance.setId(11L);instance.setStartTime(new Date(System.currentTimeMillis()-1000));
  call(service,"markFail",new Class<?>[]{McTaskInstanceDO.class,Exception.class},instance,new RuntimeException("错误😀".repeat(400)));
  assertEquals("2",instance.getStatus());assertEquals(512,instance.getFailCause().codePointCount(0,instance.getFailCause().length()));assertTrue(instance.getFailCause().endsWith("..."));assertNotNull(instance.getEndTime());verify(instances).updateById(instance);
 }
 @Test void partiallyFailedCollectionCannotBeMarkedSuccessful() throws Exception {
  var service=new McTaskServiceImpl();var instance=new McTaskInstanceDO();instance.setStatus("1");instance.setTotalCount(20L);instance.setSuccessCount(9L);instance.setFailCount(11L);
  var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->call(service,"markSuccess",new Class<?>[]{McTaskInstanceDO.class},instance));
  assertInstanceOf(IllegalStateException.class,error.getCause());assertTrue(error.getCause().getMessage().contains("11"));assertEquals("1",instance.getStatus());
 }
}

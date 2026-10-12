package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.access.AccessDeniedException;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.module.da.service.asset.*;
import tech.qiantong.qdata.module.da.service.assetchild.api.impl.DaAssetApiParamServiceImpl;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamSaveReqVO;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.api.DaAssetApiParamMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class ApiParameterLifecycleIntegrationTest {
    static final long API=950001,ROOT=960001,CHILD=960002,HEADER=960003;
    SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;ApiParameterGraph graph;AssetChildReferences references;DaAssetApiParamServiceImpl service;org.apache.ibatis.session.SqlSession session;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_PARAMETER_LIFECYCLE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_parameter_lifecycle_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=manager.getTransaction(new DefaultTransactionDefinition());
        var user=new SysUser();user.setUserId(927L);user.setUserName("parameter-lifecycle-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(927L,null,user,Set.of("*:*:*")),null,List.of()));
        jdbc.update("INSERT INTO da_asset_api(id,asset_id,url,http_method,developer_name,app_name,valid_flag,del_flag) VALUES(?,238,'http://127.0.0.1:1','GET','fixture','fixture','1','0')",API);
        seed(ROOT,0L,"request","1","Object",null);seed(CHILD,ROOT,"sensor_id","1","string",null);seed(HEADER,0L,"X-Test-Header","3","string","retained");
        references=new AssetChildReferences(jdbc);graph=new ApiParameterGraph(jdbc,references);service=new DaAssetApiParamServiceImpl();ReflectionTestUtils.setField(service,"parameterGraph",graph);ReflectionTestUtils.setField(service,"childReferences",references);
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("parameter-lifecycle",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());config.addMapper(DaAssetApiParamMapper.class);session=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config).openSession();ReflectionTestUtils.setField(service,"daAssetApiParamMapper",session.getMapper(DaAssetApiParamMapper.class));
    }
    @AfterEach void close(){session.close();if(!tx.isCompleted())manager.rollback(tx);source.destroy();SecurityContextHolder.clearContext();}
    void seed(long id,Long parent,String name,String type,String column,String value){jdbc.update("INSERT INTO da_asset_api_param(id,api_id,parent_id,name,type,column_type,default_value,request_flag,valid_flag,del_flag,creator_id,create_by,create_time,update_time) VALUES(?,?,?,?,?,?,?,'0','1','0',925,'original-node','2026-09-21 08:00:00.123456','2026-09-21 08:00:00.123456')",id,API,parent,name,type,column,value);}
    DaAssetApiParamSaveReqVO node(Long id,String name,String type,String column){var r=new DaAssetApiParamSaveReqVO();r.setId(id);r.setApiId(API);r.setName(name);r.setType(type);r.setColumnType(column);return r;}
    List<DaAssetApiParamSaveReqVO> tree(){var root=node(ROOT,"request","1","Object");root.setDaAssetApiParamList(new ArrayList<>(List.of(node(CHILD,"sensor_id","1","string"))));var header=node(HEADER,"X-Test-Header","3","string");header.setDefaultValue("retained");return new ArrayList<>(List.of(root,header));}
    List<Map<String,Object>> snapshot(){return jdbc.queryForList("SELECT * FROM da_asset_api_param WHERE api_id=? ORDER BY id",API);}
    void reference(){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM ds_api ORDER BY id LIMIT 1"));row.put("id",970001L);row.put("api_service_type","3");row.put("transmit_type","1");row.put("api_id",String.valueOf(API));row.put("del_flag","0");jdbc.update("INSERT INTO ds_api("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void saveTree(List<DaAssetApiParamSaveReqVO> tree,Long api){service.createDaAssetApiParamDeep(tree,api,graph.version(api));}
    @Test void unchangedAggregateKeepsIdsAndEveryAuditField(){var before=snapshot();saveTree(tree(),API);assertEquals(before,snapshot());}
    @Test void editKeepsExistingIdentityCreatorAndPrecision(){var before=jdbc.queryForMap("SELECT creator_id,create_by,create_time FROM da_asset_api_param WHERE id=?",CHILD);var tree=tree();tree.get(0).getDaAssetApiParamList().get(0).setDescription("changed description");saveTree(tree,API);assertEquals(before,jdbc.queryForMap("SELECT creator_id,create_by,create_time FROM da_asset_api_param WHERE id=?",CHILD));assertEquals("changed description",jdbc.queryForObject("SELECT description FROM da_asset_api_param WHERE id=?",String.class,CHILD));assertEquals(927L,jdbc.queryForObject("SELECT updater_id FROM da_asset_api_param WHERE id=?",Long.class,CHILD));}
    @Test void newChildGetsNewIdWithoutReplacingSiblings(){var tree=tree();tree.get(0).getDaAssetApiParamList().add(node(null,"sample_time","1","string"));saveTree(tree,API);assertEquals(4L,jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_api_param WHERE api_id=? AND del_flag='0'",Long.class,API));assertEquals(ROOT,jdbc.queryForObject("SELECT parent_id FROM da_asset_api_param WHERE api_id=? AND name='sample_time'",Long.class,API));assertEquals(927L,jdbc.queryForObject("SELECT creator_id FROM da_asset_api_param WHERE api_id=? AND name='sample_time'",Long.class,API));assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_api_param WHERE id=? AND del_flag='0'",Long.class,CHILD));}
    @Test void newParentReceivesGeneratedIdBeforeItsChild(){var tree=tree();var parent=node(null,"output","2","Object");parent.setDaAssetApiParamList(List.of(node(null,"status","2","string")));tree.add(parent);saveTree(tree,API);assertNotNull(parent.getId());assertEquals(parent.getId(),jdbc.queryForObject("SELECT parent_id FROM da_asset_api_param WHERE api_id=? AND name='status'",Long.class,API));}
    @Test void removedNodeIsArchivedAndUnchangedNodesKeepAudit(){var before=jdbc.queryForMap("SELECT * FROM da_asset_api_param WHERE id=?",HEADER);var tree=tree();tree.get(0).setDaAssetApiParamList(List.of());saveTree(tree,API);assertEquals("1",jdbc.queryForObject("SELECT del_flag FROM da_asset_api_param WHERE id=?",String.class,CHILD));assertEquals(before,jdbc.queryForMap("SELECT * FROM da_asset_api_param WHERE id=?",HEADER));}
    @Test void foreignIdIsRejectedBeforeAnyModification(){var before=snapshot();var tree=tree();tree.get(0).getDaAssetApiParamList().add(node(9999999L,"foreign","1","string"));assertThrows(AccessDeniedException.class,()->saveTree(tree,API));assertEquals(before,snapshot());}
    @Test void duplicateExistingIdIsRejectedBeforeAnyModification(){var before=snapshot();var tree=tree();tree.add(node(CHILD,"duplicate","1","string"));assertThrows(AccessDeniedException.class,()->saveTree(tree,API));assertEquals(before,snapshot());}
    @Test void mismatchedApiCannotBeImportedIntoAggregate(){var tree=tree();tree.get(0).setApiId(API+1);assertThrows(AccessDeniedException.class,()->saveTree(tree,API));}
    @Test void parentIdMustMatchSubmittedTree(){var tree=tree();tree.get(0).getDaAssetApiParamList().get(0).setParentId(HEADER);assertThrows(IllegalArgumentException.class,()->saveTree(tree,API));}
    @Test void cyclicObjectGraphIsRejectedWithoutWrites(){var tree=tree();tree.get(0).getDaAssetApiParamList().add(tree.get(0));var before=snapshot();assertThrows(IllegalArgumentException.class,()->saveTree(tree,API));assertEquals(before,snapshot());}
    @ParameterizedTest @ValueSource(strings={"","0","4"}) void unknownParameterGroupIsRejected(String type){var tree=tree();tree.get(0).setType(type);assertThrows(IllegalArgumentException.class,()->saveTree(tree,API));}
    @Test void caseInsensitiveHeaderDuplicatesAreRejected(){var tree=tree();tree.add(node(null,"x-test-header","3","string"));assertThrows(IllegalArgumentException.class,()->saveTree(tree,API));}
    @Test void nonObjectCannotHaveNestedParameters(){var tree=tree();tree.get(0).setColumnType("string");assertThrows(IllegalArgumentException.class,()->saveTree(tree,API));}
    @Test void referencedApiAcceptsUnchangedTree(){reference();var before=snapshot();saveTree(tree(),API);assertEquals(before,snapshot());}
    @Test void referencedApiAllowsDescriptionWithoutContractChange(){reference();var tree=tree();tree.get(0).getDaAssetApiParamList().get(0).setDescription("approved description");saveTree(tree,API);assertEquals("approved description",jdbc.queryForObject("SELECT description FROM da_asset_api_param WHERE id=?",String.class,CHILD));}
    @Test void referencedApiCannotAddOrRemoveNodes(){reference();var before=snapshot();var tree=tree();tree.add(node(null,"new_input","1","string"));assertThrows(IllegalArgumentException.class,()->saveTree(tree,API));assertThrows(IllegalArgumentException.class,()->saveTree(List.of(),API));assertEquals(before,snapshot());}
    @Test void referencedApiCannotRenameExistingParameter(){reference();var tree=tree();tree.get(0).getDaAssetApiParamList().get(0).setName("different");assertThrows(IllegalArgumentException.class,()->saveTree(tree,API));}
    @Test void nullableRootAndZeroRootAreEquivalent(){jdbc.update("UPDATE da_asset_api_param SET parent_id=NULL WHERE id IN(?,?)",ROOT,HEADER);reference();var before=snapshot();saveTree(tree(),API);assertEquals(before,snapshot());}
    @Test void directUpdateCannotCreateCycle(){var request=node(ROOT,"request","1","Object");request.setParentId(CHILD);assertThrows(IllegalArgumentException.class,()->service.updateDaAssetApiParam(request));}
    @Test void directCreateRequiresValidParent(){var request=node(null,"bad","1","string");request.setParentId(9999999L);assertThrows(AccessDeniedException.class,()->service.createDaAssetApiParam(request));}
    @Test void directDeleteCannotLeaveOrphan(){assertThrows(IllegalArgumentException.class,()->service.removeDaAssetApiParam(List.of(ROOT)));}
    @Test void directUpdateKeepsDisabledFlag(){jdbc.update("UPDATE da_asset_api_param SET valid_flag='0' WHERE id=?",CHILD);var request=node(CHILD,"sensor_id","1","string");request.setDescription("metadata only");service.updateDaAssetApiParam(request);assertEquals("0",jdbc.queryForObject("SELECT valid_flag FROM da_asset_api_param WHERE id=?",String.class,CHILD));}
    @Test void parameterChangesRollbackTogether(){var tree=tree();tree.get(0).getDaAssetApiParamList().add(node(null,"rolled_back","1","string"));saveTree(tree,API);manager.rollback(tx);assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_api_param WHERE api_id=?",Long.class,API));}
    @Test void veryLargeTreeIsRejectedBeforeWrites(){var tree=new ArrayList<DaAssetApiParamSaveReqVO>();for(int i=0;i<10001;i++)tree.add(node(null,"field_"+i,"1","string"));var before=snapshot();assertThrows(IllegalArgumentException.class,()->saveTree(tree,API));assertEquals(before,snapshot());}
    @Test void atomicFlatImportSupportsValidParentRotation(){
        var first=node(ROOT,"request","1","Object");first.setParentId(CHILD);var second=node(CHILD,"sensor_id","1","Object");second.setParentId(0L);var requests=List.of(first,second);
        var children=org.mockito.Mockito.mock(AssetChildAccess.class);ReflectionTestUtils.setField(service,"children",children);
        org.mockito.Mockito.doReturn(requests).when(children).imports(org.mockito.ArgumentMatchers.eq(AssetChildAccess.Kind.PARAM),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.eq(DaAssetApiParamSaveReqVO.class));
        service.importDaAssetApiParam(List.of(),true,"ignored");assertEquals(CHILD,jdbc.queryForObject("SELECT parent_id FROM da_asset_api_param WHERE id=?",Long.class,ROOT));assertEquals(0L,jdbc.queryForObject("SELECT parent_id FROM da_asset_api_param WHERE id=?",Long.class,CHILD));
    }
    @Test void staleAggregateCannotArchiveAnotherEditorsNewNode(){String old=graph.version(API);var first=tree();first.get(0).getDaAssetApiParamList().add(node(null,"new_by_a","1","string"));service.createDaAssetApiParamDeep(first,API,old);var before=snapshot();var failure=assertThrows(tech.qiantong.qdata.common.exception.ServiceException.class,()->service.createDaAssetApiParamDeep(tree(),API,old));assertEquals(409,failure.getCode());assertEquals(before,snapshot());}
    @Test void existingTreeRequiresAnExpectedVersion(){assertThrows(tech.qiantong.qdata.common.exception.ServiceException.class,()->service.createDaAssetApiParamDeep(tree(),API));}
    @Test void responseTreeAndStoredRowsProduceTheSameVersion(){assertEquals(graph.version(API),ApiParameterGraph.versionOfTree(service.getDaAssetApiParamList(API)));}
    @Test void concurrentApiBindingCannotCreateTwoChildrenForOneAsset() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("QDATA_PARAMETER_LIFECYCLE_URL"),"root",System.getenv("QDATA_NAMESPACE_PASSWORD"));var db=new JdbcTemplate(ds);long asset=System.currentTimeMillis();
        var row=new LinkedHashMap<>(db.queryForMap("SELECT * FROM da_asset WHERE id=238"));row.put("id",asset);db.update("INSERT INTO da_asset("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
        var refs=new AssetChildReferences(db);var transactions=new TransactionTemplate(new DataSourceTransactionManager(ds));var created=new java.util.concurrent.CountDownLatch(1);var attempted=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var first=executor.submit(()->transactions.execute(status->{assertNull(refs.reserveApiSlot(asset,null));db.update("INSERT INTO da_asset_api(asset_id,url,http_method,developer_name,app_name) VALUES(?,'http://127.0.0.1:1','GET','fixture','concurrent')",asset);created.countDown();try{assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertTrue(created.await(5,java.util.concurrent.TimeUnit.SECONDS));var second=executor.submit(()->transactions.execute(status->{db.queryForObject("SELECT COUNT(*) FROM da_asset_api WHERE asset_id=?",Long.class,asset);attempted.countDown();return refs.reserveApiSlot(asset,null)==null;}));
            assertTrue(attempted.await(5,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->second.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();assertTrue(first.get(10,java.util.concurrent.TimeUnit.SECONDS));assertFalse(second.get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM da_asset_api WHERE asset_id=?",Long.class,asset));
        }finally{release.countDown();executor.shutdownNow();}
    }
}




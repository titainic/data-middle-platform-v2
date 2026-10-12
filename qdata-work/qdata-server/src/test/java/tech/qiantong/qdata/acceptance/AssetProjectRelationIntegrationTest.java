package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.da.service.asset.*;
import tech.qiantong.qdata.module.da.service.assetColumn.IDaAssetColumnService;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.da.controller.admin.asset.DaAssetController;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.*;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.DaAssetColumnController;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.*;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import tech.qiantong.qdata.common.core.domain.BaseEntity;
import org.springframework.beans.BeanWrapperImpl;
import org.junit.jupiter.params.provider.EnumSource;
import tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import tech.qiantong.qdata.module.da.controller.admin.assetchild.projectRel.vo.*;
import tech.qiantong.qdata.module.da.service.assetchild.projectRel.impl.DaAssetProjectRelServiceImpl;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.projectRel.DaAssetProjectRelMapper;
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class AssetProjectRelationIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;
    AssetProjectAssignmentAccess access;AssetProjectRelationIntegrity integrity;DaAssetProjectRelServiceImpl service;
    DataSourceFactory transport;org.apache.ibatis.session.SqlSession session;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_ASSET_RELATION_URL");assertNotNull(url);assertTrue(url.contains("/qdata_asset_relation_"));
        fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);
        manager=new DataSourceTransactionManager(fixture);tx=manager.getTransaction(new DefaultTransactionDefinition());actor(927);
        transport=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);
        for(long id:new long[]{9,13,16,17,18})when(sources.getDaDatasourceById(id)).thenAnswer(call->HiveEndpointFixture.source(jdbc,id));
        ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(new HiveNamespaceBrowser(jdbc,sources,transport));
        var data=new ProjectDataResourceAccess(jdbc,provider);var permissions=new ProjectPermissionService(jdbc);
        access=new AssetProjectAssignmentAccess(jdbc,data,new AssetProjectReadAccess(jdbc,data,permissions),permissions);
        integrity=new AssetProjectRelationIntegrity(jdbc);service=new DaAssetProjectRelServiceImpl();
        ReflectionTestUtils.setField(service,"assignments",access);ReflectionTestUtils.setField(service,"integrity",integrity);
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("relation",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),fixture));config.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());config.addMapper(DaAssetProjectRelMapper.class);
        session=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config).openSession();ReflectionTestUtils.setField(service,"daAssetProjectRelMapper",session.getMapper(DaAssetProjectRelMapper.class));
        seed(900144,925,List.of(144L));seed(900143,925,List.of(143L));seed(900145,925,List.of(143L,144L));seed(900001,925,List.of(1L));seed(900927,927,List.of());
    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{session.close();if(!tx.isCompleted())manager.rollback(tx);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("relation-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void seed(long id,long creator,List<Long> projects){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM da_asset WHERE id=189"));row.putAll(Map.of("id",id,"type","2","creator_id",creator,"datasource_id","-1"));row.put("dataset_id",null);
        jdbc.update("INSERT INTO da_asset("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
        for(Long project:projects)jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag,creator_id,create_by,create_time) SELECT ?,id,code,'1','0',925,'original','2026-09-21 10:00:00.123456' FROM att_project WHERE id=?",id,project);
    }
    Long rel(long asset,long project){return jdbc.queryForObject("SELECT id FROM da_asset_project_rel WHERE asset_id=? AND project_id=? AND del_flag='0'",Long.class,asset,project);}
    DaAssetProjectRelSaveReqVO request(long asset,long project){var r=new DaAssetProjectRelSaveReqVO();r.setAssetId(asset);r.setProjectId(project);r.setProjectCode("forged");return r;}
    void grantReader(String action){jdbc.update("INSERT INTO system_role_menu(role_id,menu_id) SELECT 20,menu_id FROM system_menu WHERE perms=? AND NOT EXISTS(SELECT 1 FROM system_role_menu r WHERE r.role_id=20 AND r.menu_id=system_menu.menu_id)","da:assetProjectRel:"+action);}
    List<Map<String,Object>> rows(long asset){return jdbc.queryForList("SELECT * FROM da_asset_project_rel WHERE asset_id=? ORDER BY id",asset);}

    @Test void readIncludesBothProjectsAndSharedButNotForeign(){assertTrue(access.ids("list").containsAll(List.of(rel(900144,144),rel(900143,143),rel(900145,143))));assertFalse(access.ids("list").contains(rel(900001,1)));}
    @Test void exportRequiresProjectSpecificFunction(){assertTrue(access.ids("export").contains(rel(900144,144)));assertFalse(access.ids("export").contains(rel(900143,143)));}
    @Test void foreignRelationDetailDenied(){assertThrows(AccessDeniedException.class,()->access.get(rel(900001,1)));}
    @Test void targetReadonlyCannotBorrowWriterFunction(){assertThrows(AccessDeniedException.class,()->access.create(request(900144,143)));}
    @Test void existingReadonlyOwnerCannotBeBypassed(){assertThrows(AccessDeniedException.class,()->access.create(request(900143,144)));}
    @Test void sharedWriteRequiresBothOwnerFunctions(){var r=request(900145,144);r.setId(rel(900145,144));assertThrows(AccessDeniedException.class,()->access.edit(r));}
    @Test void foreignTargetCannotBeAssigned(){assertThrows(AccessDeniedException.class,()->access.create(request(900144,1)));}
    @Test void ownPrivateAssetCanReceiveFirstAssignment(){var r=request(900927,144);access.create(r);assertEquals(927L,r.getCreatorId());assertEquals(927L,r.getUpdatorId());assertEquals(jdbc.queryForObject("SELECT code FROM att_project WHERE id=144",String.class),r.getProjectCode());assertNotNull(service.createDaAssetProjectRel(r));}
    @Test void privateAssetImportCanReceiveFirstAssignment(){var r=new DaAssetProjectRelRespVO();r.setAssetId(900927L);r.setProjectId(144L);assertEquals(1,access.imports(List.of(r),false).size());}
    @Test void appendPreservesExistingOwnerIdAndAudit(){grantReader("add");var before=rows(900144);var r=request(900144,143);access.create(r);service.createDaAssetProjectRel(r);assertEquals(before.get(0),rows(900144).get(0));assertEquals(2,rows(900144).size());}
    @Test void repeatedCreateReturnsSameIdWithoutChangingAudit(){var before=rows(900144);var r=request(900144,144);access.create(r);assertEquals(rel(900144,144),service.createDaAssetProjectRel(r));assertEquals(before,rows(900144));}
    @Test void relationIdentityCannotBeMoved(){var r=request(900144,143);r.setId(rel(900144,144));assertThrows(AccessDeniedException.class,()->access.edit(r));r.setProjectId(144L);r.setAssetId(900143L);assertThrows(AccessDeniedException.class,()->access.edit(r));}
    @Test void editPreservesCreationAuditAndCanonicalCode(){var before=rows(900144).get(0);var r=request(900144,144);r.setId(rel(900144,144));r.setCreatorId(1L);r.setCreateBy("forged");access.edit(r);service.updateDaAssetProjectRel(r);var after=rows(900144).get(0);for(String key:List.of("creator_id","create_by","create_time","project_code","asset_id","project_id"))assertEquals(before.get(key),after.get(key));assertEquals(927L,((Number)after.get("updater_id")).longValue());}
    @Test void lastOwnerCannotBeRemoved(){var ids=List.of(rel(900144,144));assertThrows(IllegalArgumentException.class,()->access.remove(ids));assertThrows(IllegalArgumentException.class,()->service.removeDaAssetProjectRel(ids));assertEquals("0",rows(900144).get(0).get("del_flag"));}
    @Test void batchCannotRemoveAllOwners(){grantReader("remove");var ids=List.of(rel(900145,143),rel(900145,144));assertThrows(IllegalArgumentException.class,()->access.remove(ids));assertThrows(IllegalArgumentException.class,()->service.removeDaAssetProjectRel(ids));}
    @Test void authorizedRemovalArchivesOnlyChosenRelation(){grantReader("remove");var id=rel(900145,143);access.remove(List.of(id));assertEquals(1,service.removeDaAssetProjectRel(List.of(id)));assertEquals("1",jdbc.queryForObject("SELECT del_flag FROM da_asset_project_rel WHERE id=?",String.class,id));assertNotNull(rel(900145,144));assertEquals(927L,jdbc.queryForObject("SELECT updater_id FROM da_asset_project_rel WHERE id=?",Long.class,id));}
    @Test void mixedImportPreflightHasNoPartialWrites(){var before=rows(900144);var a=new DaAssetProjectRelRespVO();a.setId(rel(900144,144));var b=new DaAssetProjectRelRespVO();b.setId(rel(900143,143));assertThrows(AccessDeniedException.class,()->service.importDaAssetProjectRel(List.of(a,b),true,"ignored"));assertEquals(before,rows(900144));}
    @Test void physicalSourceMustBeAssignedToTargetProject(){grantReader("add");jdbc.update("UPDATE da_asset SET type='1',datasource_id='16',database_name='fixture',table_name='example' WHERE id=900144");assertThrows(AccessDeniedException.class,()->access.create(request(900144,143)));}
    @Test void hiveNamespaceCannotBeWidenedThroughSourceAlias(){grantReader("add");jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT 13,id,code,'1' FROM att_project WHERE id=143");jdbc.update("UPDATE da_asset SET type='1',datasource_id='13',database_name='ods_iot_db',table_name='water' WHERE id=900144");assertThrows(AccessDeniedException.class,()->access.create(request(900144,143)));}
    @Test void managedAssetCannotBeMovedToAnotherSpaceProject(){grantReader("add");jdbc.update("INSERT INTO dp_warehouse_space(id,name,project_id,environment_code,engine_id,databases_json) VALUES(980001,'relation-test',144,'test',1,'{}')");jdbc.update("INSERT INTO dp_warehouse_dataset(id,space_id,code,name,layer_code) VALUES(980001,980001,'relation-test','relation-test','ODS')");Long dataset=980001L;jdbc.update("UPDATE da_asset SET dataset_id=? WHERE id=900144",dataset);assertThrows(AccessDeniedException.class,()->access.create(request(900144,143)));}
    @Test void disabledProjectIsRejected(){jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertThrows(AccessDeniedException.class,()->access.create(request(900927,144)));}
    @Test void missingOrDuplicatedImportIdsAreRejected(){var r=new DaAssetProjectRelRespVO();assertThrows(IllegalArgumentException.class,()->access.imports(List.of(r),true));r.setId(rel(900144,144));assertThrows(IllegalArgumentException.class,()->access.imports(List.of(r,r),true));}
    @Test void rollbackRetainsOriginalRelationships(){grantReader("add");var r=request(900144,143);access.create(r);service.createDaAssetProjectRel(r);manager.rollback(tx);assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_project_rel WHERE asset_id=900144",Long.class));}

    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentAssignmentsPreserveUniquePairAndLastOwner(boolean adding) throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("QDATA_ASSET_RELATION_URL"),"root",System.getenv("QDATA_NAMESPACE_PASSWORD"));var db=new JdbcTemplate(ds);long asset=System.currentTimeMillis();
        var row=new LinkedHashMap<>(db.queryForMap("SELECT * FROM da_asset WHERE id=189"));row.put("id",asset);
        db.update("INSERT INTO da_asset("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
        db.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code) SELECT ?,id,code FROM att_project WHERE id=144",asset);
        if(!adding)db.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code) SELECT ?,id,code FROM att_project WHERE id=143",asset);
        var ids=db.queryForList("SELECT id FROM da_asset_project_rel WHERE asset_id=? ORDER BY id",Long.class,asset);
        var guard=new AssetProjectRelationIntegrity(db);var transactions=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
        var ready=new java.util.concurrent.CountDownLatch(1);var attempted=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->transactions.execute(status->{actor(927);if(adding){assertNull(guard.existingOrValidate(request(asset,143)));db.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code) SELECT ?,id,code FROM att_project WHERE id=143",asset);}else{guard.remove(List.of(ids.get(0)));guard.archive(List.of(ids.get(0)));}ready.countDown();try{assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));var second=pool.submit(()->transactions.execute(status->{actor(927);db.queryForObject("SELECT COUNT(*) FROM da_asset_project_rel WHERE asset_id=?",Long.class,asset);attempted.countDown();if(adding)return guard.existingOrValidate(request(asset,143))!=null;assertThrows(IllegalArgumentException.class,()->guard.remove(List.of(ids.get(1))));return true;}));
            assertTrue(attempted.await(5,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->second.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();assertTrue(first.get(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue(second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(adding?2L:1L,db.queryForObject("SELECT COUNT(*) FROM da_asset_project_rel WHERE asset_id=? AND valid_flag='1' AND del_flag='0'",Long.class,asset));
        }finally{release.countDown();pool.shutdownNow();}
    }
}

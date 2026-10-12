package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.dpp.service.etl.*;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.*;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.*;
import tech.qiantong.qdata.module.mc.service.task.*;
import tech.qiantong.qdata.module.mc.controller.admin.task.*;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.*;
import java.util.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real retained platform metadata. No production writes or Hive transport. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class NativeResourceScopeIntegrationTest {
    JdbcTemplate jdbc;TransactionTemplate tx;DataSourceFactory factory;org.springframework.jdbc.datasource.SingleConnectionDataSource fixture;
    DevelopmentResourceAccess development;CollectionResourceAccess collection;ProjectDataResourceAccess resources;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_NATIVE_SCOPE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_native_scope_"));
        fixture=new org.springframework.jdbc.datasource.SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);tx=new TransactionTemplate(new DataSourceTransactionManager(fixture));
        factory=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);
        var hive=HiveEndpointFixture.source(jdbc,9L);when(sources.getDaDatasourceById(9L)).thenReturn(hive);
        var namespaces=new HiveNamespaceBrowser(jdbc,sources,factory);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);
        resources=new ProjectDataResourceAccess(jdbc,provider);development=new DevelopmentResourceAccess(jdbc,resources,new tech.qiantong.qdata.security.web.service.ProjectPermissionService(jdbc));collection=new CollectionResourceAccess(jdbc,resources,new tech.qiantong.qdata.module.mc.service.metadata.MetadataResourceAccess(jdbc,resources,MetadataProjectFixture.access(jdbc,resources)),new CollectionProjectAccess(jdbc,resources,new tech.qiantong.qdata.security.web.service.ProjectPermissionService(jdbc)));
    }
    @AfterEach void noHive(){try{verifyNoInteractions(factory);}finally{if(fixture!=null)fixture.destroy();}}
    MockedStatic<SecurityUtils> actor(long user){
        var actor=mockStatic(SecurityUtils.class);actor.when(SecurityUtils::getUserId).thenReturn(user);actor.when(SecurityUtils::getUsername).thenReturn(user==925?"qdata_resume":"qdata_scope_test");
        actor.when(()->SecurityUtils.hasPermi(anyString())).thenAnswer(call->((String)call.getArgument(0)).matches("(mc|dpp|da|dp):.*:(list|query|index|info|detail|export)$"));return actor;
    }
    @ParameterizedTest @EnumSource(DevelopmentResourceAccess.Child.class)
    void everyDevelopmentChildUsesItsStoredProjectAndTaskType(DevelopmentResourceAccess.Child kind){
        List<Long> owned;try(var ignored=actor(925)){owned=development.childIds(kind);assertFalse(owned.isEmpty(),kind.name());assertDoesNotThrow(()->development.child(kind,owned.get(0)));}
        try(var ignored=actor(926)){assertTrue(development.childIds(kind).isEmpty());assertThrows(AccessDeniedException.class,()->development.child(kind,owned.get(0)));}
    }
    @ParameterizedTest @EnumSource(CollectionResourceAccess.Child.class)
    void everyCollectionChildUsesItsOwningTask(CollectionResourceAccess.Child kind){
        List<Long> owned;try(var ignored=actor(925)){owned=collection.childIds(kind);assertFalse(owned.isEmpty(),kind.name());assertDoesNotThrow(()->collection.child(kind,owned.get(0)));}
        try(var ignored=actor(926)){assertTrue(collection.childIds(kind).isEmpty());assertThrows(AccessDeniedException.class,()->collection.child(kind,owned.get(0)));}
    }
    @ParameterizedTest @EnumSource(CollectionResourceAccess.History.class)
    void historicalMetadataKeepsItsSourceNamespace(CollectionResourceAccess.History kind){
        List<Long> owned;try(var ignored=actor(925)){owned=collection.historyIds(kind);assertFalse(owned.isEmpty(),kind.name());assertDoesNotThrow(()->collection.history(kind,owned.get(0)));}
        try(var ignored=actor(926)){assertTrue(collection.historyIds(kind).isEmpty());assertThrows(AccessDeniedException.class,()->collection.history(kind,owned.get(0)));}
    }
    @Test void nodeLogsAreRejectedBeforeRedisOrTheLogService(){
        Long id=jdbc.queryForObject("SELECT MAX(id) FROM dpp_etl_node_instance WHERE project_id=1 AND del_flag='0'",Long.class);
        var controller=new DppEtlNodeInstanceController();var service=mock(IDppEtlNodeInstanceService.class);var logs=mock(IDppEtlNodeInstanceLogService.class);var redis=mock(tech.qiantong.qdata.redis.service.IRedisService.class);
        ReflectionTestUtils.setField(controller,"resources",development);ReflectionTestUtils.setField(controller,"dppEtlNodeInstanceService",service);ReflectionTestUtils.setField(controller,"dppEtlNodeInstanceLogService",logs);ReflectionTestUtils.setField(controller,"redisService",redis);
        try(var ignored=actor(926)){
            assertThrows(AccessDeniedException.class,()->controller.getInfo(id));assertThrows(AccessDeniedException.class,()->controller.getLogInfo(id));assertThrows(AccessDeniedException.class,()->controller.getLogDetail(id));
            var response=new org.springframework.mock.web.MockHttpServletResponse();assertThrows(AccessDeniedException.class,()->controller.downloadLog(response,id,"probe"));assertEquals(0,response.getContentAsByteArray().length);verifyNoInteractions(service,logs,redis);
        }
        try(var ignored=actor(925)){
            controller.getInfo(id);verify(service).getDppEtlNodeInstanceById(id);
            var row=new tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlNodeInstanceDO();row.setId(id);when(service.getDppEtlNodeInstanceById(id)).thenReturn(row);when(logs.getLog(id)).thenReturn("fixture-log");
            assertEquals("fixture-log\n",controller.getLogInfo(id).get("data"));
        }
    }
    @Test void missingProjectFiltersCannotTurnNodeStatisticsGlobal(){
        var controller=new DppEtlNodeInstanceController();var service=mock(IDppEtlNodeInstanceService.class);ReflectionTestUtils.setField(controller,"resources",development);ReflectionTestUtils.setField(controller,"dppEtlNodeInstanceService",service);
        try(var ignored=actor(926)){
            controller.statistics(null,null,"3");verify(service).getStatistics(143L,null,"3");
            assertThrows(AccessDeniedException.class,()->controller.statistics(1L,null,"3"));verifyNoMoreInteractions(service);
        }
    }
    @Test void collectionCallbackCannotStartAnotherProjectsTaskEvenWithExecutionPermission(){
        var controller=new McTaskExecutorController();var service=mock(IMcTaskService.class);ReflectionTestUtils.setField(controller,"resources",collection);ReflectionTestUtils.setField(controller,"mcTaskService",service);
        try(var ignored=actor(926)){ignored.when(()->SecurityUtils.hasPermi("mc:metadata:table:edit")).thenReturn(true);assertThrows(AccessDeniedException.class,()->controller.runExecuteTask(9L));verifyNoInteractions(service);}
        try(var ignored=actor(925);var messages=mockStatic(tech.qiantong.qdata.common.utils.MessageUtils.class)){
            messages.when(()->tech.qiantong.qdata.common.utils.MessageUtils.message("mc.task.execute.success",9L)).thenReturn("fixture-success");
            ignored.when(()->SecurityUtils.hasPermi("mc:metadata:table:edit")).thenReturn(true);when(service.runDaDiscoveryTask(9L)).thenReturn(true);assertEquals("fixture-success",controller.runExecuteTask(9L).getData());verify(service).runDaDiscoveryTask(9L);
        }
    }
    @Test void aSharedConnectionDoesNotAuthorizeAnUnassignedHiveNamespace(){
        tx.executeWithoutResult(status->{
            jdbc.update("UPDATE mc_task_scope SET db_name='unassigned_namespace' WHERE task_id=9 AND db_name='ods_iot_db'");
            try(var ignored=actor(925)){assertFalse(collection.taskIds().contains(9L));assertThrows(AccessDeniedException.class,()->collection.task(9L,"read"));}
            status.setRollbackOnly();
        });
    }
    @Test void collectionRequestsRejectSourceSwapsForeignScopeIdsAndUnboundedHiveCollection(){
        try(var ignored=actor(925)){
            ignored.when(()->SecurityUtils.hasPermi("mc:metadata:table:edit")).thenReturn(true);ignored.when(()->SecurityUtils.hasPermi("mc:metadata:table:add")).thenReturn(true);
            var request=new McTaskSaveReqVO();request.setId(9L);request.setDatasourceId(12L);assertThrows(AccessDeniedException.class,()->collection.existingRequest(request,"execute"));
            request.setId(null);request.setDatasourceId(9L);request.setCollectionScope("2");assertThrows(AccessDeniedException.class,()->collection.save(request,false));
            request.setCollectionScope("1");var scope=new McTaskScopeSaveReqVO();scope.setDbName("ods_iot_db");request.setScopeSaveReqVOS(List.of(scope));assertDoesNotThrow(()->collection.save(request,false));
            scope.setTaskId(8L);assertThrows(AccessDeniedException.class,()->collection.save(request,false));scope.setTaskId(null);scope.setId(jdbc.queryForObject("SELECT MIN(id) FROM mc_task_scope WHERE task_id=8",Long.class));assertThrows(AccessDeniedException.class,()->collection.save(request,false));
        }
    }
    @Test void discoveryUsesDatasourceIdentityAndPrunesTheDatabaseList(){
        var controller=new McTaskController();var service=mock(IMcTaskService.class);ReflectionTestUtils.setField(controller,"resources",collection);ReflectionTestUtils.setField(controller,"mcTaskService",service);
        try(var ignored=actor(926)){assertThrows(AccessDeniedException.class,()->controller.getRealtimeMcTaskScopeList(9L));verifyNoInteractions(service);}
        var allowed=new tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskScopeDO();allowed.setDbName("dwd_iot_db");var foreign=new tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskScopeDO();foreign.setDbName("unassigned_namespace");
        when(service.getRealtimeMcTaskScopeList(9L)).thenReturn(List.of(allowed,foreign));
        try(var ignored=actor(925)){assertEquals(List.of("dwd_iot_db"),controller.getRealtimeMcTaskScopeList(9L).getData().stream().map(McTaskScopeRespVO::getDbName).toList());assertThrows(AccessDeniedException.class,()->collection.source(12L));}
    }
    @Test void everyGeneratedDevelopmentEndpointRefusesDirectMutation() throws Exception {
        for(String name:List.of("DppEtlNode","DppEtlNodeLog","DppEtlTaskLog","DppEtlTaskNodeRel","DppEtlTaskNodeRelLog","DppEtlNodeInstance","DppEtlTaskInstance")){
            var controller=Class.forName("tech.qiantong.qdata.module.dpp.controller.admin.etl."+name+"Controller").getConstructor().newInstance();ReflectionTestUtils.setField(controller,"resources",development);assertGeneratedMutationsDenied(controller);
        }
    }
    @Test void everyGeneratedCollectionEndpointRefusesDirectMutation() throws Exception {
        for(String name:List.of("task.McTaskScope","task.McTaskScheduler","task.McTaskInstance","task.McTaskInstanceLog","tableLog.McTableLog","columnLog.McColumnLog","tableColumnRelLog.McTableColumnRelLog")){
            var controller=Class.forName("tech.qiantong.qdata.module.mc.controller.admin."+name+"Controller").getConstructor().newInstance();ReflectionTestUtils.setField(controller,"resources",collection);assertGeneratedMutationsDenied(controller);
        }
    }
    private void assertGeneratedMutationsDenied(Object controller) throws Exception {
        for(Method method:controller.getClass().getDeclaredMethods())if(Set.of("add","edit","remove","importData").contains(method.getName())){
            List<Object> args=new ArrayList<>();for(Class<?> type:method.getParameterTypes()){
                if(type==Long[].class)args.add(new Long[]{1L});else if(type==boolean.class)args.add(false);else if(type==org.springframework.web.multipart.MultipartFile.class)args.add(new org.springframework.mock.web.MockMultipartFile("file",new byte[0]));else args.add(type.getConstructor().newInstance());
            }
            var exception=assertThrows(InvocationTargetException.class,()->method.invoke(controller,args.toArray()));assertInstanceOf(AccessDeniedException.class,exception.getCause(),controller.getClass().getName()+"."+method.getName());
        }
    }

    @Test void taskSavesCannotReuseAnotherProjectsNodeCodes(){
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO system_role_menu(role_id,menu_id) SELECT 17,m.menu_id FROM system_menu m WHERE m.perms='dpp:developTask:add' AND NOT EXISTS(SELECT 1 FROM system_role_menu r WHERE r.role_id=17 AND r.menu_id=m.menu_id)");

        String code=jdbc.queryForObject("SELECT code FROM dpp_etl_node WHERE project_id=1 AND del_flag='0' ORDER BY id DESC LIMIT 1",String.class);
        var request=new DppEtlNewNodeSaveReqVO();request.setProjectId(143L);request.setProjectCode(Long.valueOf(jdbc.queryForObject("SELECT code FROM att_project WHERE id=143",String.class)));request.setType("3");request.setTaskDefinitionList(com.alibaba.fastjson2.JSON.toJSONString(List.of(Map.of("code",code))));
        try(var ignored=actor(926)){ignored.when(()->SecurityUtils.hasPermi("dpp:developTask:add")).thenReturn(true);assertThrows(AccessDeniedException.class,()->development.request(request,"add"));}
        request.setProjectId(1L);request.setProjectCode(Long.valueOf(jdbc.queryForObject("SELECT code FROM att_project WHERE id=1",String.class)));
        try(var ignored=actor(925)){ignored.when(()->SecurityUtils.hasPermi("dpp:developTask:add")).thenReturn(true);assertDoesNotThrow(()->development.request(request,"add"));}
            status.setRollbackOnly();
        });
    }
    @Test void aNewNodeCannotEmbedAnUnownedSubprocess(){
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO system_role_menu(role_id,menu_id) SELECT 17,m.menu_id FROM system_menu m WHERE m.perms='dpp:developTask:add' AND NOT EXISTS(SELECT 1 FROM system_role_menu r WHERE r.role_id=17 AND r.menu_id=m.menu_id)");

        String taskCode=jdbc.queryForObject("SELECT code FROM dpp_etl_task WHERE id=52",String.class);
        var request=new DppEtlNewNodeSaveReqVO();request.setProjectId(143L);request.setProjectCode(Long.valueOf(jdbc.queryForObject("SELECT code FROM att_project WHERE id=143",String.class)));request.setType("3");request.setTaskDefinitionList(com.alibaba.fastjson2.JSON.toJSONString(List.of(Map.of("code","native-scope-new-node","taskParams",Map.of("processDefinitionCode",taskCode)))));
        try(var ignored=actor(926)){ignored.when(()->SecurityUtils.hasPermi("dpp:developTask:add")).thenReturn(true);assertThrows(AccessDeniedException.class,()->development.request(request,"add"));}
            status.setRollbackOnly();
        });
    }

    @Test void historicalRelationsUseThePublishedVersionIdentity(){
        Long id=jdbc.queryForObject("SELECT MAX(r.id) FROM dpp_etl_task_node_rel_log r JOIN dpp_etl_task_log l ON l.code=r.task_code AND l.version=r.task_version AND l.project_id=r.project_id JOIN dpp_etl_task t ON t.code=r.task_code AND t.project_id=r.project_id WHERE r.task_id=l.id AND r.task_id<>t.id AND r.project_id=1 AND r.del_flag='0'",Long.class);assertNotNull(id);
        try(var ignored=actor(925)){assertDoesNotThrow(()->development.child(DevelopmentResourceAccess.Child.RELATION_LOG,id));}
        try(var ignored=actor(926)){assertThrows(AccessDeniedException.class,()->development.child(DevelopmentResourceAccess.Child.RELATION_LOG,id));}
    }
    @Test void realMysqlAppliesNodeScopeBeforeCountingAndPaging(){
        var config=configuration();config.addMapper(tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlNodeMapper.class);var sessions=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);
        try(var session=sessions.openSession();var ignored=actor(925)){
            var mapper=session.getMapper(tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlNodeMapper.class);var request=new DppEtlNodePageReqVO();request.setPageSize(1);var ids=development.childIds(DevelopmentResourceAccess.Child.NODE);assertTrue(ids.size()>1);request.setAccessibleIds(ids);
            var page=mapper.selectPage(request);assertEquals(ids.size(),page.getTotal());assertEquals(1,page.getRows().size());request.setAccessibleIds(List.of());assertEquals(0,mapper.selectPage(request).getTotal());
        }
    }
    @Test void joinedCollectionQueriesApplyScopeAndRejectSortExpressions(){
        var config=configuration();com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());
        config.addMapper(tech.qiantong.qdata.module.mc.dal.mapper.task.McTaskMapper.class);var sessions=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);
        try(var session=sessions.openSession();var ignored=actor(925)){
            var mapper=session.getMapper(tech.qiantong.qdata.module.mc.dal.mapper.task.McTaskMapper.class);var request=new McTaskPageReqVO();request.setPageSize(1);request.setAccessibleIds(collection.taskIds());
            var page=mapper.selectPage(request);assertTrue(page.getTotal()>0);assertEquals(1,page.getRows().size());request.setAccessibleIds(List.of());assertEquals(0,mapper.selectPage(request).getTotal());request.setOrderByColumn("(SELECT password FROM system_user LIMIT 1)");assertThrows(IllegalArgumentException.class,()->mapper.selectPage(request));
        }
    }
    private com.baomidou.mybatisplus.core.MybatisConfiguration configuration(){
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("native-scope",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),jdbc.getDataSource()));config.setMapUnderscoreToCamelCase(true);
        var pagination=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));config.addInterceptor(pagination);return config;
    }
}

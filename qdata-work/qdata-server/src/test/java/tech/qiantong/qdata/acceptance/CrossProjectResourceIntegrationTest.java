package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.dpp.service.etl.DevelopmentResourceAccess;
import tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess;
import tech.qiantong.qdata.module.mc.service.metadata.MetadataResourceAccess;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual isolated MySQL metadata; all Hive transport factories are test doubles. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class CrossProjectResourceIntegrationTest {
    JdbcTemplate jdbc;TransactionTemplate tx;DataSourceFactory factory;
    ProjectDataResourceAccess resources;DevelopmentResourceAccess development;MetadataResourceAccess metadata;QualityResourceAccess quality;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_CROSS_PROJECT_URL");assertNotNull(url);assertTrue(url.contains("/qdata_cross_project_"));
        var datasource=new DriverManagerDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"));jdbc=new JdbcTemplate(datasource);tx=new TransactionTemplate(new DataSourceTransactionManager(datasource));
        factory=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);
        var hive=HiveEndpointFixture.source(jdbc,9L);when(sources.getDaDatasourceById(9L)).thenReturn(hive);
        var namespaces=new HiveNamespaceBrowser(jdbc,sources,factory);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);
        resources=new ProjectDataResourceAccess(jdbc,provider);development=new DevelopmentResourceAccess(jdbc,resources,new tech.qiantong.qdata.security.web.service.ProjectPermissionService(jdbc));metadata=new MetadataResourceAccess(jdbc,resources,MetadataProjectFixture.access(jdbc,resources));quality=new QualityResourceAccess(jdbc,resources,new tech.qiantong.qdata.module.dpp.service.qa.QualityProjectAccess(jdbc,resources,new tech.qiantong.qdata.security.web.service.ProjectPermissionService(jdbc)));
    }
    @AfterEach void noHive(){verifyNoInteractions(factory);}
    MockedStatic<SecurityUtils> actor(long id){
        var actor=mockStatic(SecurityUtils.class);actor.when(SecurityUtils::getUserId).thenReturn(id);actor.when(SecurityUtils::getUsername).thenReturn(id==926?"qdata_scope_test":"qdata_resume");
        actor.when(()->SecurityUtils.hasPermi(anyString())).thenAnswer(call->{String permission=call.getArgument(0);return permission.startsWith("da:")||permission.startsWith("dp:")||permission.startsWith("dpp:")||permission.startsWith("mc:") ? permission.matches(".*:(list|query|index|export)$"):false;});
        return actor;
    }
    @Test void exactPermissionNamesSurviveCaseInsensitiveMysqlCollation() throws Exception {
        var config=new org.apache.ibatis.session.Configuration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("scope",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),jdbc.getDataSource()));
        config.getTypeAliasRegistry().registerAlias("SysMenu",tech.qiantong.qdata.common.core.domain.entity.SysMenu.class);
        try(var xml=getClass().getResourceAsStream("/mapper/system/SysMenuMapper.xml")){
            assertNotNull(xml);new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml,config,"mapper/system/SysMenuMapper.xml",config.getSqlFragments()).parse();
        }
        var sessions=new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        try(var session=sessions.openSession()){
            var mapper=session.getMapper(tech.qiantong.qdata.module.system.mapper.SysMenuMapper.class);
            var permissions=new HashSet<>(mapper.selectMenuPermsByUserId(926L));
            assertTrue(permissions.contains("da:datasource:query"));assertTrue(permissions.contains("da:dataSource:query"));
            assertTrue(mapper.selectMenuPermsByUserId(999999L).isEmpty());assertFalse(permissions.contains("system:user:add"));
        }
    }
    @Test void taskDetailsDraftAndMutationsStopBeforeTheBusinessService(){
        var service=mock(tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService.class);
        var controller=new tech.qiantong.qdata.module.dpp.controller.admin.etl.DppEtlTaskController();ReflectionTestUtils.setField(controller,"dppEtlTaskService",service);ReflectionTestUtils.setField(controller,"resourceAccess",development);
        try(var ignored=actor(926)){
            assertEquals(Set.of(143L),resources.visibleProjectIds());assertFalse(development.taskIds().contains(52L));
            assertThrows(AccessDeniedException.class,()->controller.getInfo(52L));
            assertThrows(AccessDeniedException.class,()->controller.getuUpdateQueryInfo(52L));
            assertThrows(AccessDeniedException.class,()->controller.getupdateQueryFront(52L));
            assertThrows(AccessDeniedException.class,()->controller.startDppEtlTask(52L));
            assertThrows(AccessDeniedException.class,()->controller.remove(new Long[]{52L}));
            var update=new tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO();update.setId("52");update.setProjectId(143L);
            assertThrows(AccessDeniedException.class,()->controller.updateReleaseJobTask(update));verifyNoInteractions(service);
        }
        try(var ignored=actor(925)){assertTrue(development.taskIds().contains(52L));controller.getInfo(52L);verify(service).getDppEtlTaskById(52L);}
    }
    @Test void projectIdentifiersCannotBeMixedAcrossTheSameRequest(){
        try(var ignored=actor(926)){
            String own=jdbc.queryForObject("SELECT code FROM att_project WHERE id=143",String.class);
            String other=jdbc.queryForObject("SELECT code FROM att_project WHERE id=1",String.class);
            assertDoesNotThrow(()->development.project(143L,own));assertThrows(AccessDeniedException.class,()->development.project(143L,other));
            assertThrows(AccessDeniedException.class,()->development.project(1L,own));
        }
    }
    @Test void aViewerCannotEditOrExecuteEvenAnOwnedTask(){
        tx.executeWithoutResult(status->{
            var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM dpp_etl_task WHERE id=52"));row.put("id",900001L);row.put("project_id",143L);row.put("project_code",jdbc.queryForObject("SELECT code FROM att_project WHERE id=143",String.class));row.put("code","scope-fixture-"+UUID.randomUUID());
            jdbc.update("INSERT INTO dpp_etl_task ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
            try(var ignored=actor(926)){
                assertDoesNotThrow(()->development.task(900001L));assertTrue(development.taskIds().contains(900001L));
                assertThrows(AccessDeniedException.class,()->development.task(900001L,"edit"));assertThrows(AccessDeniedException.class,()->development.task(900001L,"execute"));
            }
            status.setRollbackOnly();
        });
    }
    @Test void metadataTablesAndColumnsUseTheirOwningNamespace(){
        Long column=jdbc.queryForObject("SELECT MIN(id) FROM mc_column WHERE table_id=1845 AND del_flag='0'",Long.class);assertNotNull(column);
        var tableService=mock(tech.qiantong.qdata.module.mc.service.metadata.IMcTableService.class);var tableController=new tech.qiantong.qdata.module.mc.controller.admin.metadata.McTableController();ReflectionTestUtils.setField(tableController,"mcTableService",tableService);ReflectionTestUtils.setField(tableController,"resources",metadata);
        var columnService=mock(tech.qiantong.qdata.module.mc.service.metadata.IMcColumnService.class);var columnController=new tech.qiantong.qdata.module.mc.controller.admin.metadata.McColumnController();ReflectionTestUtils.setField(columnController,"mcColumnService",columnService);ReflectionTestUtils.setField(columnController,"resources",metadata);
        try(var ignored=actor(926)){
            assertTrue(metadata.tableIds().isEmpty());assertThrows(AccessDeniedException.class,()->tableController.getInfo(1845L));assertThrows(AccessDeniedException.class,()->columnController.getInfo(column));
            assertThrows(AccessDeniedException.class,()->tableController.remove(new Long[]{1845L}));assertThrows(AccessDeniedException.class,()->columnController.remove(new Long[]{column}));verifyNoInteractions(tableService,columnService);
        }
        try(var ignored=actor(925)){assertTrue(metadata.tableIds().contains(1845L));assertDoesNotThrow(()->metadata.column(column));}
    }
    @Test void mismatchedDatabaseAndDatasourceCannotBeRegistered(){
        var table=jdbc.queryForMap("SELECT db_id,db_name,table_name FROM mc_table WHERE id=1845");
        try(var ignored=actor(925)){
            assertThrows(AccessDeniedException.class,()->metadata.tableRequest(null,12L,((Number)table.get("db_id")).longValue(),String.valueOf(table.get("db_name")),String.valueOf(table.get("table_name"))));
        }
    }
    @Test void homepageTotalsMatchVisibleAssetsAndHideForeignTasks(){
        var home=new tech.qiantong.qdata.server.controller.OverviewController(jdbc,resources,quality);
        try(var ignored=actor(926)){
            var result=home.overview().getData();assertEquals("accessible",result.get("scope"));var totals=(Map<?,?>)result.get("totals");
            assertEquals(0L,totals.get("assets"));assertEquals(0L,totals.get("integration"));assertEquals(0L,totals.get("development"));
            for(Object value:(List<?>)result.get("qualitySuccess"))assertEquals(0L,value);
        }
        try(var ignored=actor(925)){
            var totals=(Map<?,?>)home.overview().getData().get("totals");assertEquals((long)resources.visibleAssetIds().size(),totals.get("assets"));assertTrue(((Number)totals.get("assets")).longValue()>0);
        }
    }
    @Test void instanceLogsDownloadsAndMutationsUseTheOwningTask(){
        Long instance=jdbc.queryForObject("SELECT MIN(id) FROM dpp_etl_task_instance WHERE task_id=52 AND del_flag='0'",Long.class);assertNotNull(instance);
        var service=mock(tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskInstanceService.class);
        var controller=new tech.qiantong.qdata.module.dpp.controller.admin.etl.DppEtlTaskInstanceController();ReflectionTestUtils.setField(controller,"resources",development);ReflectionTestUtils.setField(controller,"dppEtlTaskInstanceService",service);
        try(var ignored=actor(926)){
            assertThrows(AccessDeniedException.class,()->controller.getInfo(instance));
            assertThrows(AccessDeniedException.class,()->controller.getTaskInfo(instance));
            assertThrows(AccessDeniedException.class,()->controller.getLogByTaskInstanceId(instance));
            var response=new org.springframework.mock.web.MockHttpServletResponse();assertThrows(AccessDeniedException.class,()->controller.downloadLog(response,instance,"probe"));assertEquals(0,response.getContentAsByteArray().length);
            var update=new tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskInstanceSaveReqVO();update.setId(instance);update.setTaskId(52L);update.setProjectId(143L);
            assertThrows(AccessDeniedException.class,()->controller.edit(update));assertThrows(AccessDeniedException.class,()->controller.remove(new Long[]{instance}));verifyNoInteractions(service);
        }
        try(var ignored=actor(925)){assertDoesNotThrow(()->controller.getInfo(instance));verify(service).getDppEtlTaskInstanceById(instance);}
    }
    @Test void instanceCannotBeReparentedOrEditedByAnOwnedViewer(){
        tx.executeWithoutResult(status->{
            jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id IN(SELECT role_id FROM system_user_role WHERE user_id=925) AND m.perms IN('dpp:developTask:edit','dpp:integratioTask:edit','dpp:etlTask:edit')");

        Long instance=jdbc.queryForObject("SELECT MIN(id) FROM dpp_etl_task_instance WHERE task_id=52 AND del_flag='0'",Long.class);
        try(var ignored=actor(925)){
            var update=new tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskInstanceSaveReqVO();update.setId(instance);update.setTaskId(49L);
            assertThrows(AccessDeniedException.class,()->development.instanceRequest(update,true));
            update.setTaskId(52L);assertThrows(AccessDeniedException.class,()->development.instanceRequest(update,true));
        }
        try(var ignored=actor(926)){assertEquals(143L,development.resolveProject(null,null));assertThrows(AccessDeniedException.class,()->development.resolveProject(1L,null));}
            status.setRollbackOnly();
        });
    }
    @Test void sourceTreePrunesForeignSourcesAndEmptyParents(){
        var source=new tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSourceTreeRespVO();source.setId(1L);source.setName("fixture");source.setType("SOURCE");
        var connection=new tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSourceTreeRespVO();connection.setId(9L);connection.setName("fixture-connection");connection.setType("DATASOURCE");
        var database=new tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSourceTreeRespVO();database.setId(1L);database.setName("dwd_iot_db");database.setType("DATABASE");
        connection.setChildren(List.of(database));source.setChildren(List.of(connection));
        try(var ignored=actor(925)){var visible=metadata.sourceTree(List.of(source));assertEquals(1,visible.size());assertEquals(1,visible.get(0).getChildren().size());assertEquals(1,visible.get(0).getChildren().get(0).getChildren().size());}
        try(var ignored=actor(926)){assertTrue(metadata.sourceTree(List.of(source)).isEmpty());}
    }
    @Test void databaseDetailsAndWritesCannotCrossProjectOrNamespace(){
        Long database=jdbc.queryForObject("SELECT db_id FROM mc_table WHERE id=1845",Long.class);assertNotNull(database);
        var controller=new tech.qiantong.qdata.module.mc.controller.admin.metadata.McDbController();var service=mock(tech.qiantong.qdata.module.mc.service.metadata.IMcDbService.class);ReflectionTestUtils.setField(controller,"resources",metadata);ReflectionTestUtils.setField(controller,"mcDbService",service);
        try(var ignored=actor(926)){
            assertTrue(metadata.databaseIds().isEmpty());assertThrows(AccessDeniedException.class,()->controller.getInfo(database));assertThrows(AccessDeniedException.class,()->controller.remove(new Long[]{database}));verifyNoInteractions(service);
        }
        try(var ignored=actor(925)){
            assertTrue(metadata.databaseIds().contains(database));assertThrows(AccessDeniedException.class,()->metadata.databaseRequest(database,9L,"unassigned_namespace"));
        }
    }
    @Test void nestedMetadataResponsesHideConnectionSecretsAndScopeCounts(){
        Long database=jdbc.queryForObject("SELECT db_id FROM mc_table WHERE id=1845",Long.class);
        var db=new tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McDbRespVO();db.setId(database);db.setDatasourceId(9L);db.setDatasourceConfig("{\"password\":\"fixture-secret\",\"dbname\":\"dwd_iot_db\"}");
        var table=new tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McTableRespVO();table.setDbRespVO(db);
        try(var ignored=actor(925)){var shown=metadata.present(table).getDbRespVO();assertFalse(shown.getDatasourceConfig().contains("fixture-secret"));assertTrue(shown.getTableCount()>0);assertTrue(db.getDatasourceConfig().contains("fixture-secret"));}
        try(var ignored=actor(926)){var shown=metadata.present(table).getDbRespVO();assertEquals(0,shown.getTableCount());assertEquals(0,shown.getColumnCount());assertEquals(0L,shown.getDataRowCount());}
    }
    @Test void metadataSearchScopesAllUnionBranchesBeforePaging() throws Exception {
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("search-scope",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),jdbc.getDataSource()));config.setMapUnderscoreToCamelCase(true);
        config.getTypeAliasRegistry().registerAlias("McDbDO",tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McDbDO.class);
        var pagination=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));config.addInterceptor(pagination);
        try(var xml=getClass().getResourceAsStream("/mapper/mc/metadata/McDbMapper.xml")){assertNotNull(xml);new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml,config,"mapper/mc/metadata/McDbMapper.xml",config.getSqlFragments()).parse();}
        var sessions=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);
        try(var session=sessions.openSession()){
            var mapper=session.getMapper(tech.qiantong.qdata.module.mc.dal.mapper.metadata.McDbMapper.class);var request=new tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McMetaSearchRespDTO();
            request.setAccessibleDatabaseIds(List.of());request.setAccessibleTableIds(List.of(1845L));
            long columns=jdbc.queryForObject("SELECT COUNT(*) FROM mc_column WHERE table_id=1845 AND del_flag='0'",Long.class);
            var first=mapper.selectMetaSearchPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1,1),request);assertEquals(columns+1,first.getTotal());assertEquals(1,first.getRecords().size());
            request.setTypes(List.of("2"));var tables=mapper.selectMetaSearchPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1,1),request);assertEquals(1,tables.getTotal());assertEquals(1845L,tables.getRecords().get(0).getId());
            request.setTypes(null);request.setAccessibleTableIds(List.of());assertEquals(0,mapper.selectMetaSearchPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1,1),request).getTotal());
            request.setAccessibleTableIds(List.of(1845L));request.setKeyword("' OR 1=1 --");assertEquals(0,mapper.selectMetaSearchPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1,1),request).getTotal());
        }
    }

    @Test void serviceEnabledHomepageCountsOnlyItsProjectApisAndLogs(){
        var home=new tech.qiantong.qdata.server.controller.OverviewController(jdbc,resources,quality);
        for(long user:new long[]{925L,926L})try(var ignored=actor(user)){
            ignored.when(()->SecurityUtils.hasPermi("ds:api:list")).thenReturn(true);
            ignored.when(()->SecurityUtils.hasPermi("ds:apiLog:list")).thenReturn(true);
            var result=home.overview().getData();var totals=(Map<?,?>)result.get("totals");
            if(user==926L){assertEquals(0L,totals.get("apis"));for(Object value:(List<?>)result.get("apiCalls"))assertEquals(0L,value);}
            else {
                long expected=jdbc.queryForObject("SELECT COUNT(*) FROM ds_api a WHERE a.del_flag='0' AND (a.creator_id=925 OR EXISTS (SELECT 1 FROM dp_warehouse_serving_product p JOIN dp_warehouse_space s ON s.id=p.space_id WHERE p.api_id=a.id AND s.project_id=1))",Long.class);
                assertTrue(expected>0);assertEquals(expected,totals.get("apis"));assertEquals(7,((List<?>)result.get("apiCalls")).size());
            }
        }
    }

}

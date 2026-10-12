package tech.qiantong.qdata.acceptance;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.ds.controller.admin.api.DsApiController;
import tech.qiantong.qdata.module.ds.controller.admin.apiLog.DsApiLogController;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.*;
import tech.qiantong.qdata.module.ds.dal.dataobject.dto.ResParam;
import tech.qiantong.qdata.module.ds.service.api.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real retained platform metadata and normal ownership rules; all data execution interfaces are doubles. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class ServiceProjectScopeIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;TransactionTemplate tx;DataSourceFactory factory;
    ProjectDataResourceAccess data;ProjectPermissionService permissions;ServiceResourceAccess resources;IDsApiService service;DsApiController controller;IDaDatasourceApiService sourceApi;IDaDatasourceService sourceService;HiveNamespaceBrowser namespaces;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_SERVICE_SCOPE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_service_scope_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);tx=new TransactionTemplate(new DataSourceTransactionManager(fixture));
        factory=mock(DataSourceFactory.class);sourceService=mock(IDaDatasourceService.class);sourceApi=mock(IDaDatasourceApiService.class);
        for(long id:new long[]{9,12}){
            var source=HiveEndpointFixture.source(jdbc,id);when(sourceService.getDaDatasourceById(id)).thenReturn(source);
            var api=new DaDatasourceRespDTO();api.setId(id);api.setDatasourceType(source.getDatasourceType());api.setDatasourceConfig(source.getDatasourceConfig());when(sourceApi.getDatasourceById(id)).thenReturn(api);
        }
        namespaces=new HiveNamespaceBrowser(jdbc,sourceService,factory);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);data=new ProjectDataResourceAccess(jdbc,provider);permissions=new ProjectPermissionService(jdbc);resources=new ServiceResourceAccess(jdbc,data,permissions,sourceApi);
        service=mock(IDsApiService.class);controller=new DsApiController();ReflectionTestUtils.setField(controller,"resources",resources);ReflectionTestUtils.setField(controller,"dsApiService",service);
    }
    @AfterEach void clear(){try{verifyNoInteractions(factory);}finally{SecurityContextHolder.clearContext();fixture.destroy();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("service-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    Long apiId(){return jdbc.queryForObject("SELECT MIN(id) FROM ds_api WHERE project_id=1 AND del_flag='0'",Long.class);}
    void copy(String table,long original,long id,Map<String,Object> changes){
        var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",original));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(key->"`"+key+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    }
    DsApiDO definition(Long project,long source,String sql){var api=new DsApiDO();api.setProjectId(project);api.setApiServiceType("2");api.setStatus("0");var config=new ExecuteConfig();config.setApiServiceType("2");config.setSourceId(String.valueOf(source));config.setSqlText(sql);api.setExecuteConfig(config);return api;}
    void alias(long project,String database,String host) {
        var source=HiveEndpointFixture.source(jdbc,9L);source.setId(900009L);if(host!=null)source.setIp(host);
        var config=JSON.parseObject(source.getDatasourceConfig());config.put("dbname",database);config.put("username","alternate-fixture-reader");config.put("password","");source.setDatasourceConfig(config.toJSONString());
        copy("da_datasource",9L,900009L,Map.of("datasource_config",source.getDatasourceConfig(),"ip",source.getIp()));
        jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT 900009,id,code,'1' FROM att_project WHERE id=?",project);
        when(sourceService.getDaDatasourceById(900009L)).thenReturn(source);
        var api=new DaDatasourceRespDTO();api.setId(900009L);api.setDatasourceType("Hive");api.setDatasourceConfig(source.getDatasourceConfig());when(sourceApi.getDatasourceById(900009L)).thenReturn(api);
    }
    @Test void existingEngineFingerprintRemainsByteCompatible() {
        var source=HiveEndpointFixture.source(jdbc,9L);
        String fingerprint=tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.fingerprint(new tech.qiantong.qdata.common.database.constants.DbQueryProperty(source.getDatasourceType(),source.getIp(),source.getPort(),source.getDatasourceConfig()));
        assertEquals(jdbc.queryForObject("SELECT connection_fingerprint FROM dp_warehouse_engine WHERE datasource_id=9",String.class),fingerprint);
    }
    @Test void secondConnectionReusesAllFiveNamespacesForTheirOwningProject() {
        tx.executeWithoutResult(status->{alias(1,"ods_iot_db",null);actor(925);
            for(String layer:List.of("ods","dwd","dws","dwt","ads"))assertDoesNotThrow(()->data.requireMetadataTable(900009L,layer+"_iot_db.example"));
            assertEquals(Set.of(1L),data.owningHiveProjects(900009L,"example"));status.setRollbackOnly();});
    }
    @Test void secondConnectionCannotGrantManagedDefaultDatabaseToAnotherProject() {
        tx.executeWithoutResult(status->{alias(143,"ods_iot_db",null);actor(926);
            assertTrue(data.visibleDatasourceIds().contains(900009L));
            assertThrows(AccessDeniedException.class,()->data.requireMetadataTable(900009L,"example"));
            assertThrows(IllegalArgumentException.class,()->namespaces.authorizeQuery(900009L,"SELECT * FROM ods_iot_db.example"));status.setRollbackOnly();});
    }
    @Test void apiProjectCannotBorrowSecondConnectionsNamespaceFromAnotherMembership() {
        tx.executeWithoutResult(status->{alias(143,"ods_iot_db",null);
            jdbc.update("INSERT INTO att_project_user_rel(project_id,user_id,valid_flag,del_flag) VALUES(143,925,'1','0')");
            jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT 900009,id,code,'1' FROM att_project WHERE id=1");
            actor(925);assertDoesNotThrow(()->data.requireMetadataTable(900009L,"example"));
            assertThrows(AccessDeniedException.class,()->resources.query(143L,900009L,"SELECT * FROM example",false));
            assertDoesNotThrow(()->resources.query(1L,900009L,"SELECT * FROM example",false));status.setRollbackOnly();});
    }
    @Test void changedEndpointCannotAutoGrantAnAlreadyManagedDefaultDatabase() {
        tx.executeWithoutResult(status->{alias(1,"ods_iot_db","unregistered-fixture-endpoint");actor(925);
            assertThrows(AccessDeniedException.class,()->data.requireMetadataTable(900009L,"example"));status.setRollbackOnly();});
    }
    @Test void successfulSparkBatchGrantsOnlyItsProjectAndVerifiedEndpoint() {
        tx.executeWithoutResult(status->{
            alias(1,"qdata_test","spark-fixture-endpoint");actor(925);
            var source=sourceService.getDaDatasourceById(900009L);
            var property=new tech.qiantong.qdata.common.database.constants.DbQueryProperty(source.getDatasourceType(),source.getIp(),source.getPort(),source.getDatasourceConfig());
            String fingerprint=tech.qiantong.qdata.common.database.core.HiveEndpointIdentity.fingerprint(property);
            String batch=UUID.randomUUID().toString();
            String key=UUID.randomUUID().toString().replace("-","")+"0".repeat(32);
            String request=JSON.toJSONString(Map.of("computeEngine","KYUUBI_SPARK","computeDatasourceId",900009,"computeEndpointFingerprint",fingerprint));
            jdbc.update("INSERT INTO dp_warehouse_batch(id,space_id,name,request_key,attempt,window_start,window_end,status,request_json,plan_json,input_json,create_by,creator_id) VALUES (?,1,'spark-scope-fixture',?,1,'2024-01-01','2024-01-31',?,?,'[]','[]','fixture',925)",batch,key,"FAILED",request);
            assertThrows(IllegalArgumentException.class,()->namespaces.authorizeQuery(900009L,"SELECT * FROM ads_iot_db.example"));
            jdbc.update("UPDATE dp_warehouse_batch SET status='SUCCESS',request_json=? WHERE id=?",JSON.toJSONString(Map.of("computeEngine","KYUUBI_SPARK","computeDatasourceId",900009,"computeEndpointFingerprint","wrong-endpoint")),batch);
            assertThrows(IllegalArgumentException.class,()->namespaces.authorizeQuery(900009L,"SELECT * FROM ads_iot_db.example"));
            jdbc.update("UPDATE dp_warehouse_batch SET request_json=? WHERE id=?",request,batch);
            assertDoesNotThrow(()->namespaces.authorizeQuery(900009L,"SELECT * FROM ads_iot_db.example"));
            assertEquals(Set.of(1L),namespaces.owningProjects(900009L,"ads_iot_db.example"));
            assertThrows(IllegalArgumentException.class,()->namespaces.authorizeQuery(900009L,"SELECT * FROM other_project_scope.example"));
            status.setRollbackOnly();
        });
    }
    @Test void assignedUnmanagedDefaultDatabaseRetainsItsExistingMeaning() {
        tx.executeWithoutResult(status->{alias(143,"unmanaged_fixture",null);actor(926);
            assertDoesNotThrow(()->data.requireMetadataTable(900009L,"example"));
            assertTrue(data.owningHiveProjects(900009L,"example").isEmpty());
            assertThrows(IllegalArgumentException.class,()->namespaces.authorizeQuery(900009L,"SELECT a.id FROM example a JOIN ods_iot_db.example b ON a.id=b.id"));status.setRollbackOnly();});
    }
    @Test void endpointIdentityDoesNotReplaceActualConnectionAssignment() {
        tx.executeWithoutResult(status->{alias(143,"ods_iot_db",null);actor(925);
            assertThrows(AccessDeniedException.class,()->data.requireMetadataTable(900009L,"example"));status.setRollbackOnly();});
    }
    @Test void omittedDatabaseOnNativeTableListStillChecksManagedDefaultBeforeTransport() {
        tx.executeWithoutResult(status->{alias(143,"ods_iot_db",null);actor(926);
            var nativeService=spy(new tech.qiantong.qdata.module.da.service.datasource.impl.DaDatasourceServiceImpl());
            doReturn(sourceService.getDaDatasourceById(900009L)).when(nativeService).getDaDatasourceById(900009L);
            ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);
            ReflectionTestUtils.setField(nativeService,"hiveNamespaces",provider);ReflectionTestUtils.setField(nativeService,"dataSourceFactory",factory);
            assertThrows(IllegalArgumentException.class,()->nativeService.getDbTables(900009L));status.setRollbackOnly();});
    }
    @Test void warehouseApisInheritTheirDeclaredSpaceProject(){actor(925);assertEquals(4,resources.apiIds("read").size());assertTrue(resources.apiIds("read").contains(apiId()));actor(926);assertTrue(permissions.has(143L,"ds:api:query"));assertTrue(resources.apiIds("read").isEmpty());}
    @Test void foreignDetailsAndMutationsStopBeforeServiceInvocation(){
        actor(926);Long id=apiId();assertThrows(AccessDeniedException.class,()->controller.getInfo(id));var update=definition(143L,9,"SELECT 1");update.setId(id);assertThrows(AccessDeniedException.class,()->controller.updateDataApi(update));assertThrows(AccessDeniedException.class,()->controller.remove(new Long[]{id}));assertThrows(AccessDeniedException.class,()->controller.serviceTesting(update));verifyNoInteractions(service);
    }
    @Test void anExistingApiCannotMoveToAnotherProject(){actor(925);var api=definition(143L,9,"SELECT 1");api.setId(apiId());assertThrows(AccessDeniedException.class,()->resources.prepare(api,"edit",true));}
    @Test void aReadonlyProjectRoleCannotCreateAnApiDespiteBroadCachedPermissions(){actor(926);assertFalse(permissions.has(143L,"ds:api:add"));var api=definition(143L,9,"SELECT 1");assertThrows(AccessDeniedException.class,()->resources.prepare(api,"add",false));}
    @Test void aSingleEligibleProjectCanBeSelectedWithoutGuessingAcrossProjects(){actor(925);var api=definition(null,9,"SELECT 1");resources.prepare(api,"add",false);assertEquals(1L,api.getProjectId());}
    @Test void aConnectionMustBeAssignedToTheApiProject(){actor(925);assertThrows(AccessDeniedException.class,()->resources.query(143L,9L,"SELECT 1",false));assertDoesNotThrow(()->resources.query(1L,9L,"SELECT * FROM dwd_iot_db.water_quality",false));}
    @Test void disabledConnectionsRemainConfigurableButCannotBeExecuted(){actor(925);assertDoesNotThrow(()->resources.query(1L,12L,"SELECT 1",false));assertThrows(AccessDeniedException.class,()->resources.query(1L,12L,"SELECT 1",true));}
    @Test void readonlyChecksRejectWritesAndReflectionBeforeAnyDataTransport(){actor(925);for(String sql:List.of("DELETE FROM dwd_iot_db.water_quality","SELECT reflect('java.lang.Runtime','exec','fixture')","SELECT 1; SELECT 2"))assertThrows(IllegalArgumentException.class,()->resources.query(1L,9L,sql,true));}
    @Test void sharedConnectionsCannotPackageAnotherProjectsWarehouseData(){
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO att_project_user_rel(project_id,user_id,valid_flag,del_flag) VALUES(143,925,'1','0')");jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT 9,id,code,'1' FROM att_project WHERE id=143");Long engine=jdbc.queryForObject("SELECT MIN(id) FROM dp_warehouse_engine WHERE datasource_id=9",Long.class);
            jdbc.update("INSERT INTO dp_warehouse_space(id,name,project_id,environment_code,engine_id,databases_json,revision,create_by) VALUES(900001,'service-scope-fixture',143,'fixture',?,'{}',1,'fixture')",engine);
            jdbc.update("INSERT INTO dp_warehouse_namespace(engine_id,database_name,space_id,layer_code) VALUES(?,'other_project_scope',900001,'ODS')",engine);
            actor(925);assertDoesNotThrow(()->data.requireMetadataTable(9L,"other_project_scope.example"));assertThrows(AccessDeniedException.class,()->resources.query(1L,9L,"SELECT * FROM other_project_scope.example",false));status.setRollbackOnly();
        });
    }
    @Test void persistedConfigIsExactlyTheValidatedExecutionConfig(){actor(925);var api=definition(1L,9,"SELECT 1");api.setConfigJson("{\"sourceId\":\"12\",\"apiServiceType\":\"2\",\"sqlText\":\"DELETE FROM private_table\"}");resources.definition(api,false);assertEquals("SELECT 1",JSON.parseObject(api.getConfigJson()).getString("sqlText"));assertEquals("9",JSON.parseObject(api.getConfigJson()).getString("sourceId"));}
    @Test void serviceTypesCannotDisagreeWithTheExecutionConfiguration(){actor(925);var api=definition(1L,9,"SELECT 1");api.setApiServiceType("1");assertThrows(IllegalArgumentException.class,()->resources.definition(api,false));}
    @Test void sqlParsingCannotUseAnUnassignedDatasource(){actor(926);var request=new SqlParseDto();request.setProjectId(143L);request.setSourceId("9");request.setSqlText("SELECT * FROM dwd_iot_db.water_quality");assertThrows(AccessDeniedException.class,()->controller.sqlParse(request));verifyNoInteractions(service);}
    @Test void legacyUnassignedApisRemainPrivateToTheirCreator(){
        tx.executeWithoutResult(status->{var changes=new HashMap<String,Object>();changes.put("project_id",null);changes.put("creator_id",925L);changes.put("api_url","/fixture/private");copy("ds_api",apiId(),900001,changes);actor(925);assertTrue(resources.apiIds("read").contains(900001L));actor(926);assertFalse(resources.apiIds("read").contains(900001L));assertThrows(AccessDeniedException.class,()->resources.api(900001L,"read"));status.setRollbackOnly();});
    }
    @Test void deletedApiHistoryRemainsScopedAndReadable(){
        tx.executeWithoutResult(status->{
            copy("ds_api",apiId(),900001,Map.of("project_id",143L,"creator_id",926L,"api_url","/fixture/history","del_flag","1"));Long log=jdbc.queryForObject("SELECT MIN(id) FROM ds_api_log WHERE api_id=?",Long.class,apiId());assertNotNull(log);copy("ds_api_log",log,900002,Map.of("api_id",900001L));
            actor(926);assertFalse(resources.apiIds("read").contains(900001L));assertTrue(resources.apiIds("logs").contains(900001L));assertDoesNotThrow(()->resources.log(900002L));actor(925);assertThrows(AccessDeniedException.class,()->resources.log(900002L));status.setRollbackOnly();
        });
    }
    @Test void logsCannotBeForgedThroughTheCrudController(){var logs=new DsApiLogController();ReflectionTestUtils.setField(logs,"resources",resources);assertThrows(AccessDeniedException.class,()->logs.add(new tech.qiantong.qdata.module.ds.controller.admin.apiLog.vo.DsApiLogSaveReqVO()));assertThrows(AccessDeniedException.class,()->logs.remove(new Long[]{1L}));}
    @Test void logPresentationRemovesCredentials(){var value=new tech.qiantong.qdata.module.ds.controller.admin.apiLog.vo.DsApiLogRespVO();value.setCallerParams("{\"token\":\"fixture-token\",\"pageSize\":1}");value.setMsg("password=fixture-pass");resources.present(value);assertFalse(value.getCallerParams().contains("fixture-token"));assertFalse(value.getMsg().contains("fixture-pass"));}
    @Test void theLegacyLogQueryPermissionStillResolvesWithinItsProject(){
        tx.executeWithoutResult(status->{
            copy("ds_api",apiId(),900001,Map.of("project_id",143L,"creator_id",926L,"api_url","/fixture/legacy-log"));
            jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=17 AND m.perms IN ('ds:apiLog:list','ds:apiLog:export')");
            actor(926);assertTrue(permissions.has(143L,"ds:apilog:query"));assertFalse(permissions.has(143L,"ds:apiLog:list"));assertTrue(resources.apiIds("logs").contains(900001L));status.setRollbackOnly();
        });
    }
    @Test void readonlyApiPresentationMasksNestedForwardHeadersToo(){
        actor(926);var api=new tech.qiantong.qdata.module.ds.controller.admin.api.vo.DsApiRespVO();api.setProjectId(143L);api.setHeaderJson("{\"Authorization\":\"fixture-direct-token\"}");api.setConfigJson("{\"headerJson\":[{\"paramName\":\"Authorization\",\"value\":\"Bearer fixture-nested-token\"}],\"apiServiceType\":\"3\"}");resources.present(api);assertFalse(api.getCanEdit());assertFalse(api.getHeaderJson().contains("fixture-direct-token"));assertFalse(api.getConfigJson().contains("fixture-nested-token"));
    }
    @Test void hiveFormSqlUsesAQualifiedTableOnlyOnce(){
        var api=definition(1L,9,"SELECT 1");api.setApiServiceType("1");api.getExecuteConfig().setApiServiceType("1");api.getExecuteConfig().setDbType("Hive");api.getExecuteConfig().setDbName("qdata_test");api.getExecuteConfig().setTableName("dwd_iot_db.water_quality");var field=new ResParam();field.setFieldName("device_id");api.setResParamsList(List.of(field));api.setReqParamsList(List.of());
        var implementation=new tech.qiantong.qdata.module.ds.service.api.impl.DsApiServiceImpl();String sql=ReflectionTestUtils.invokeMethod(implementation,"sqlJdbcNamedParameterBuild",api);assertTrue(sql.contains("dwd_iot_db.water_quality"));assertFalse(sql.contains("qdata_test.dwd_iot_db"));
    }
    @Test void apiAndLogPaginationUseTheServerScope(){
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("service-scope",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),fixture));config.setMapUnderscoreToCamelCase(true);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());var pagination=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));config.addInterceptor(pagination);config.addMapper(tech.qiantong.qdata.module.ds.dal.mapper.api.DsApiMapper.class);config.addMapper(tech.qiantong.qdata.module.ds.dal.mapper.apiLog.DsApiLogMapper.class);
        var sessions=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);try(var session=sessions.openSession()){
            var mapper=session.getMapper(tech.qiantong.qdata.module.ds.dal.mapper.api.DsApiMapper.class);var request=new tech.qiantong.qdata.module.ds.controller.admin.api.vo.DsApiPageReqVO();request.setPageSize(1);actor(925);request.setAccessibleApiIds(resources.apiIds("read"));var page=mapper.selectPage(request);assertEquals(4,page.getTotal());assertEquals(1,page.getRows().size());actor(926);request.setAccessibleApiIds(resources.apiIds("read"));assertEquals(0,mapper.selectPage(request).getTotal());
            var logMapper=session.getMapper(tech.qiantong.qdata.module.ds.dal.mapper.apiLog.DsApiLogMapper.class);var logRequest=new tech.qiantong.qdata.module.ds.controller.admin.apiLog.vo.DsApiLogPageReqVO();logRequest.setAccessibleApiIds(List.of());assertEquals(0,logMapper.selectPage(logRequest).getTotal());
        }
    }
}

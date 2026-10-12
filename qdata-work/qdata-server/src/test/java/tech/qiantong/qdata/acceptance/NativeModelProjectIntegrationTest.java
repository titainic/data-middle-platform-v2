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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.dp.controller.admin.model.*;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.*;
import tech.qiantong.qdata.module.dp.dal.dataobject.model.*;
import tech.qiantong.qdata.module.dp.service.model.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real project grants and native mappers; physical materialization is always a service double. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class NativeModelProjectIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    ModelResourceAccess access;ProjectDataResourceAccess data;ProjectPermissionService permissions;DataSourceFactory transport;
    IDaDatasourceService sourceService;IDaDatasourceApiService sourceApi;IDpModelService models;IDpModelColumnService columns;IDpModelMaterializedService runs;
    DpModelController modelController;DpModelColumnController columnController;DpModelMaterializedController runController;
    String previousDatabaseType;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_NATIVE_MODEL_SCOPE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_native_model_scope_"));
        fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        previousDatabaseType=tech.qiantong.qdata.mybatis.config.MasterDataSourceConfig.getDatabaseType();new tech.qiantong.qdata.mybatis.config.MasterDataSourceConfig().setDatabaseType("mysql");
        transport=mock(DataSourceFactory.class);sourceService=mock(IDaDatasourceService.class);sourceApi=mock(IDaDatasourceApiService.class);
        for(long id:new long[]{9,12,13}){var source=HiveEndpointFixture.source(jdbc,id);when(sourceService.getDaDatasourceById(id)).thenReturn(source);var dto=new DaDatasourceRespDTO();dto.setId(id);dto.setDatasourceType(source.getDatasourceType());dto.setIp(source.getIp());dto.setPort(source.getPort());dto.setDatasourceConfig(source.getDatasourceConfig());dto.setDatasourceName("fixture-source-"+id);when(sourceApi.getDatasourceById(id)).thenReturn(dto);}
        var namespace=new HiveNamespaceBrowser(jdbc,sourceService,transport);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespace);data=new ProjectDataResourceAccess(jdbc,provider);permissions=new ProjectPermissionService(jdbc);access=new ModelResourceAccess(jdbc,data,permissions);
        models=mock(IDpModelService.class);columns=mock(IDpModelColumnService.class);runs=mock(IDpModelMaterializedService.class);
        modelController=new DpModelController();ReflectionTestUtils.setField(modelController,"resources",access);ReflectionTestUtils.setField(modelController,"dpModelService",models);
        columnController=new DpModelColumnController();ReflectionTestUtils.setField(columnController,"resources",access);ReflectionTestUtils.setField(columnController,"dpModelColumnService",columns);
        runController=new DpModelMaterializedController();ReflectionTestUtils.setField(runController,"resources",access);ReflectionTestUtils.setField(runController,"dpModelMaterializedService",runs);ReflectionTestUtils.setField(runController,"models",models);ReflectionTestUtils.setField(runController,"sources",sourceApi);ReflectionTestUtils.setField(runController,"dataAccess",data);
        for(long project:new long[]{1,143,144})seedModel(900000+project,project);
        seedColumn(910143,900143,"b_value");seedColumn(910144,900144,"a_value");actor(927);
    }
    @AfterEach void clear(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();new tech.qiantong.qdata.mybatis.config.MasterDataSourceConfig().setDatabaseType(previousDatabaseType);}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("native-model-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seedModel(long id,long project){var values=new HashMap<String,Object>();values.put("project_id",project);values.put("warehouse_config",null);values.put("dataset_id",null);values.put("datasource_id",null);values.put("database_name",null);values.put("model_name","fixture_"+id);values.put("model_comment","native-scope-fixture");values.put("creator_id",927L);values.put("status","0");values.put("document_id",null);values.put("cat_code",null);copy("dp_model",13,id,values);var model=new DpModelDO();model.setId(id);model.setModelName("fixture_"+id);model.setTableCase("2");model.setProjectId(project);model.setDatasourceConfig("{\"password\":\"fixture-secret\"}");when(models.getDpModelById(id)).thenReturn(model);}
    void seedColumn(long id,long parent,String name){Long old=jdbc.queryForObject("SELECT MIN(id) FROM dp_model_column WHERE model_id=13 AND del_flag='0'",Long.class);copy("dp_model_column",old,id,Map.of("model_id",parent,"eng_name",name,"cn_name",name));}
    void assign(long source,long project){jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT ?,id,code,'1' FROM att_project p WHERE p.id=? AND NOT EXISTS(SELECT 1 FROM da_datasource_project_rel r WHERE r.datasource_id=? AND r.project_id=p.id AND r.valid_flag='1')",source,project,source);}
    DpModelSaveReqVO definition(Long project){var d=new DpModelSaveReqVO();d.setProjectId(project);d.setModelName("fixture_model");d.setModelComment("测试模型");d.setTableType("1");return d;}
    DpModelColumnSaveReqVO field(Long parent,Long id,String name){var d=new DpModelColumnSaveReqVO();d.setModelId(parent);d.setId(id);d.setEngName(name);d.setCnName(name);d.setColumnType("VARCHAR");d.setColumnLength(32L);return d;}
    DpMaterializedMethodReqVO materialize(long model,long source){var d=new DpMaterializedMethodReqVO();d.setModelId(List.of(model));d.setDatasourceId(source);d.setReleaseMode("2");d.setIp("client-spoof");d.setPort(1L);d.setDatasourceType("MySQL");d.setDatasourceConfig("{\"dbname\":\"forged\",\"username\":\"fake\",\"password\":\"fake\"}");return d;}
    void published(long model){jdbc.update("UPDATE dp_model SET status='1' WHERE id=?",model);}
    @Test void targetProjectCapabilitiesIgnoreCachedWildcard(){assertEquals(Set.of(900143L,900144L),new HashSet<>(access.modelIds("list",null,false)));assertTrue(access.capabilities(900144L).get("edit"));assertFalse(access.capabilities(900143L).get("edit"));}
    @Test void foreignAndLegacyDetailsFailBeforeBusinessService(){assertThrows(AccessDeniedException.class,()->modelController.getInfo(13L));assertThrows(AccessDeniedException.class,()->modelController.getInfo(900001L));verifyNoInteractions(models);}
    @Test void explicitForeignProjectFilterIsRejected(){var request=new DpModelPageReqVO();request.setProjectId(1L);assertThrows(AccessDeniedException.class,()->modelController.list(request));verifyNoInteractions(models);}
    @Test void clientCannotSupplyAccessibleModelIds(){var request=new DpModelPageReqVO();request.setAccessibleModelIds(List.of(13L));when(models.getDpModelPage(any())).thenReturn(new tech.qiantong.qdata.common.core.page.PageResult<>(List.of(),0L));modelController.list(request);assertEquals(Set.of(900143L,900144L),new HashSet<>(request.getAccessibleModelIds()));}
    @Test void ownDetailReportsRealCapabilitiesAndNeverDatasourceCredentials(){var result=modelController.getInfo(900144L).getData();assertNull(result.getDatasourceConfig());assertEquals(144L,result.getProjectId());assertFalse(result.getManagedWarehouse());assertTrue(result.getCapabilities().get("edit"));}
    @Test void readonlyProjectCannotCreateEditOrRemove(){assertThrows(AccessDeniedException.class,()->modelController.add(definition(143L)));var d=definition(143L);d.setId(900143L);assertThrows(AccessDeniedException.class,()->modelController.edit(d));assertThrows(AccessDeniedException.class,()->modelController.remove(new Long[]{900143L}));verifyNoInteractions(models);}
    @Test void definitionCannotMoveBetweenOwnedProjects(){var d=definition(143L);d.setId(900144L);assertThrows(AccessDeniedException.class,()->modelController.edit(d));verifyNoInteractions(models);}
    @Test void newDefinitionRequiresFiveLayerWorkspaceAndRespectsProjectScope(){assertThrows(AccessDeniedException.class,()->modelController.add(definition(null)));var d=definition(144L);assertThrows(IllegalArgumentException.class,()->modelController.add(d));verify(models,never()).createDpModel(any());}
    @Test void existingMetadataAuditCannotBeForged(){var d=definition(144L);d.setId(900144L);d.setCreatorId(1L);d.setCreateBy("spoof");d.setCreateTime(new Date(0));modelController.edit(d);assertNull(d.getCreatorId());assertNull(d.getCreateBy());assertNull(d.getCreateTime());assertEquals(927L,d.getUpdatorId());}
    @Test void allBatchDeleteParentsAreCheckedBeforeAnyRemoval(){assertThrows(AccessDeniedException.class,()->modelController.remove(new Long[]{900144L,900143L}));verifyNoInteractions(models);}
    @Test void managedDefinitionIsNotWritableFromLegacyEditor(){actor(925);Long id=jdbc.queryForObject("SELECT MIN(id) FROM dp_model WHERE JSON_UNQUOTE(JSON_EXTRACT(warehouse_config,'$.spaceId'))='1'",Long.class);assertThrows(AccessDeniedException.class,()->access.editable(id,"edit"));assertThrows(AccessDeniedException.class,()->access.editable(id,"import"));}
    @Test void publishedDefinitionCannotBeEditedInPlace(){published(900144);var d=definition(144L);d.setId(900144L);assertThrows(IllegalArgumentException.class,()->modelController.edit(d));assertThrows(IllegalArgumentException.class,()->columnController.edit(field(900144L,910144L,"a_value")));verifyNoInteractions(models,columns);}
    @Test void emptyLogicalModelCannotBePublished(){jdbc.update("UPDATE dp_model_column SET del_flag='1' WHERE model_id=900144");assertThrows(IllegalArgumentException.class,()->modelController.updateStatus(900144L,1L));verifyNoInteractions(models);}
    @Test void declaredDatasourceMustBelongToTheDefinitionProject(){assign(9,143);var d=definition(144L);d.setDatasourceId("9");assertThrows(AccessDeniedException.class,()->modelController.add(d));verifyNoInteractions(models);}
    @Test void originalPrivateModelIsOnlyReadableByItsCreator(){actor(2);assertDoesNotThrow(()->access.model(13L,"query"));actor(927);assertThrows(AccessDeniedException.class,()->access.model(13L,"query"));}
    @Test void nativeColumnsCannotReadAnotherProjectsParent(){var req=new DpModelColumnSaveReqVO();req.setModelId(13L);assertThrows(AccessDeniedException.class,()->columnController.getDpModelColumnList(req));assertThrows(AccessDeniedException.class,()->columnController.getInfo(jdbc.queryForObject("SELECT MIN(id) FROM dp_model_column WHERE model_id=13 AND del_flag='0'",Long.class)));verifyNoInteractions(columns);}
    @Test void childCannotMoveToAnotherParentEvenIfNewParentIsEditable(){assertThrows(AccessDeniedException.class,()->columnController.edit(field(900144L,910143L,"moved")));verifyNoInteractions(columns);}
    @Test void batchColumnsRejectForeignParentBeforeBusinessWrite(){assertThrows(AccessDeniedException.class,()->columnController.editList(List.of(field(900144L,910144L,"a_value"),field(900143L,910143L,"b_value"))));verifyNoInteractions(columns);}
    @Test void replacementRequiresRemovalCapabilityForOmittedColumns(){seedColumn(911144,900144,"a_extra");jdbc.update("DELETE r FROM system_role_menu r JOIN system_menu m ON m.menu_id=r.menu_id WHERE r.role_id=19 AND m.perms='dp:model:modelcolumn:remove'");assertThrows(AccessDeniedException.class,()->columnController.editList(List.of(field(900144L,910144L,"a_value"))));assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM dp_model_column WHERE model_id=900144 AND del_flag='0'",Long.class));verifyNoInteractions(columns);}
    @Test void duplicateColumnIdentityCannotRewriteSameRowTwice(){assertThrows(IllegalArgumentException.class,()->columnController.editList(List.of(field(900144L,910144L,"first"),field(900144L,910144L,"second"))));verifyNoInteractions(columns);}
    @Test void publishedModelCanBeWithdrawnWithoutEditingItsDefinition(){published(900144);when(models.updateStatus(900144L,0L)).thenReturn(true);assertDoesNotThrow(()->modelController.updateStatus(900144L,0L));verify(models).updateStatus(900144L,0L);}
    @Test void legacyHiveMaterializationCannotBypassFiveLayerVersions(){assign(9,144);published(900144);assertThrows(AccessDeniedException.class,()->runController.createMaterializedTable(materialize(900144,9)));verifyNoInteractions(runs);}
    @Test void sourceAssignedThroughAnotherMembershipCannotPublishTargetProject(){assign(9,143);published(900144);assertThrows(AccessDeniedException.class,()->runController.createMaterializedTable(materialize(900144,9)));verifyNoInteractions(runs);}
    @Test void disabledDatasourceCannotBeUsedForMaterialization(){assign(12,144);published(900144);assertThrows(AccessDeniedException.class,()->runController.createMaterializedTable(materialize(900144,12)));verifyNoInteractions(runs);}
    @Test void draftModelCannotSkipLogicalPublication(){assign(9,144);assertThrows(IllegalArgumentException.class,()->runController.createMaterializedTable(materialize(900144,9)));verifyNoInteractions(runs);}
    @Test void nativePlainModelCannotTargetManagedHiveNamespace(){actor(925);published(900001);assertThrows(AccessDeniedException.class,()->runController.createMaterializedTable(materialize(900001,13)));verifyNoInteractions(runs);}
    @Test void materializationRecordsCannotBeForgedOrDeleted(){assertThrows(AccessDeniedException.class,()->runController.add(new DpModelMaterializedSaveReqVO()));assertThrows(AccessDeniedException.class,()->runController.edit(new DpModelMaterializedSaveReqVO()));assertThrows(AccessDeniedException.class,()->runController.remove(new Long[]{1L}));verifyNoInteractions(runs);}
    @Test void resolvedCredentialsAreExcludedFromOperationAudit() throws Exception {var annotation=DpModelMaterializedController.class.getMethod("createMaterializedTable",DpMaterializedMethodReqVO.class).getAnnotation(tech.qiantong.qdata.common.annotation.Log.class);assertFalse(annotation.isSaveRequestData());assertFalse(annotation.isSaveResponseData());}
    @Test void datasourceModelColumnShortcutChecksModelOwnership(){actor(925);var controller=new tech.qiantong.qdata.module.da.controller.admin.datasource.DaDatasourceController(sourceService,mock(tech.qiantong.qdata.module.da.service.asset.IDaAssetService.class),mock(HiveNamespaceBrowser.class));ObjectProvider<tech.qiantong.qdata.module.dp.api.service.model.ModelManagementAccess> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(access);ReflectionTestUtils.setField(controller,"modelAccess",provider);ReflectionTestUtils.setField(controller,"resourceAccess",data);ReflectionTestUtils.setField(controller,"daDatasourceService",sourceService);var json=new cn.hutool.json.JSONObject().set("id",9).set("type","Hive").set("tableName","water_quality").set("modelId",900143).set("isOld",false);assertThrows(AccessDeniedException.class,()->controller.getColumnsList(json));verify(sourceService,never()).getColumnsList(any());}
    @Test void disabledProjectImmediatelyRevokesNativeResources(){jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertFalse(access.modelIds("list",null,false).contains(900144L));assertThrows(AccessDeniedException.class,()->modelController.getInfo(900144L));assertThrows(AccessDeniedException.class,()->columnController.getInfo(910144L));}
    @Test void nativeMapperPaginationFiltersBeforeCounting(){
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("native-model",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),fixture));config.setMapUnderscoreToCamelCase(true);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());var pagination=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));config.addInterceptor(pagination);config.addMapper(tech.qiantong.qdata.module.dp.dal.mapper.model.DpModelMapper.class);config.addMapper(tech.qiantong.qdata.module.dp.dal.mapper.model.DpModelColumnMapper.class);config.addMapper(tech.qiantong.qdata.module.dp.dal.mapper.model.DpModelMaterializedMapper.class);
        try(var session=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config).openSession()){
            var mapper=session.getMapper(tech.qiantong.qdata.module.dp.dal.mapper.model.DpModelMapper.class);var req=new DpModelPageReqVO();req.setPageSize(1);req.setAccessibleModelIds(access.modelIds("list",null,false));assertEquals(2,mapper.selectPage(req).getTotal());assertEquals(1,mapper.selectPage(req).getRows().size());req.setAccessibleModelIds(List.of());assertEquals(0,mapper.selectPage(req).getTotal());
            var columnMapper=session.getMapper(tech.qiantong.qdata.module.dp.dal.mapper.model.DpModelColumnMapper.class);var cq=new DpModelColumnPageReqVO();cq.setPageSize(1);cq.setAccessibleModelIds(access.modelIds("columnQuery",null,false));assertEquals(2,columnMapper.selectPage(cq).getTotal());cq.setAccessibleModelIds(List.of());assertEquals(0,columnMapper.selectPage(cq).getTotal());
            var materialized=session.getMapper(tech.qiantong.qdata.module.dp.dal.mapper.model.DpModelMaterializedMapper.class);var mq=new DpModelMaterializedPageReqVO();mq.setAccessibleModelIds(List.of());assertEquals(0,materialized.selectPage(mq).getTotal());
        }
    }
    void seedHistory(){assign(9,144);jdbc.update("INSERT INTO dp_model_materialized(id,model_id,model_name,model_alias,status,message,sql_command,del_flag,datasource_id,datasource_name,datasource_type) VALUES(920144,900144,'fixture','fixture','3','password=fixture-secret','token=fixture-secret','0',9,'fixture-source','Hive')");var value=new DpModelMaterializedDO();value.setId(920144L);value.setModelId(900144L);value.setMessage("password=fixture-secret");value.setSqlCommand("token=fixture-secret");when(runs.getDpModelMaterializedById(920144L)).thenReturn(value);}
    @Test void historyOfDeletedModelRemainsScopedAndRedacted(){seedHistory();jdbc.update("UPDATE dp_model SET del_flag='1' WHERE id=900144");assertFalse(access.modelIds("list",null,false).contains(900144L));assertTrue(access.modelIds("materializationRead",null,true).contains(900144L));var result=runController.getInfo(920144L).getData();assertFalse(result.getMessage().contains("fixture-secret"));assertFalse(result.getSqlCommand().contains("fixture-secret"));}
    @Test void anotherProjectCannotReadMaterializationHistory(){seedHistory();actor(926);assertThrows(AccessDeniedException.class,()->runController.getInfo(920144L));verify(runs,never()).getDpModelMaterializedById(anyLong());}
    DpModelRespVO importRow(long id,long project){var r=new DpModelRespVO();r.setId(id);r.setProjectId(project);r.setModelName("import_fixture");r.setModelComment("import fixture");r.setTableType("1");return r;}
    @Test void importChecksAllRowsBeforeUpdatingAnyModel() throws Exception {
        var rows=List.of(importRow(900144,144),importRow(900143,143));
        try(var parser=mockConstruction(tech.qiantong.qdata.common.utils.poi.ExcelUtil.class,(mock,ctx)->doReturn(rows).when(mock).importExcel(any(java.io.InputStream.class)))){
            assertThrows(AccessDeniedException.class,()->modelController.importData(new org.springframework.mock.web.MockMultipartFile("file",new byte[0]),true,null));verifyNoInteractions(models);
        }
    }
    @Test void importCannotCreateUnboundModels() throws Exception {
        assertThrows(IllegalArgumentException.class,()->modelController.importData(new org.springframework.mock.web.MockMultipartFile("file",new byte[0]),false,144L));
        verifyNoInteractions(models);
    }
    @Test void importCannotBypassManagedModelEditorGuard() throws Exception {
        actor(925);long id=jdbc.queryForObject("SELECT MIN(id) FROM dp_model WHERE JSON_UNQUOTE(JSON_EXTRACT(warehouse_config,'$.spaceId'))='1'",Long.class);var rows=List.of(importRow(id,1));
        try(var parser=mockConstruction(tech.qiantong.qdata.common.utils.poi.ExcelUtil.class,(mock,ctx)->doReturn(rows).when(mock).importExcel(any(java.io.InputStream.class)))){
            assertThrows(AccessDeniedException.class,()->modelController.importData(new org.springframework.mock.web.MockMultipartFile("file",new byte[0]),true,1L));verifyNoInteractions(models);
        }
    }

    @Test void explicitJsonNullSpaceRetainsPrivateLegacySemantics(){jdbc.update("UPDATE dp_model SET warehouse_config=JSON_SET(warehouse_config,'$.spaceId',NULL) WHERE id=13");actor(2);assertDoesNotThrow(()->access.model(13L,"query"));actor(927);assertThrows(AccessDeniedException.class,()->access.model(13L,"query"));}
    @Test void modelCreatorWithoutDatasourcePermissionGetsEmptySourceOptions(){assertTrue(access.datasourceOptions(144L).isEmpty());assertThrows(AccessDeniedException.class,()->access.datasourceOptions(143L));}
    @Test void sourceOptionsUseSelectedProjectAndOmitConnectionSecrets(){jdbc.update("INSERT INTO system_role_menu(role_id,menu_id) SELECT 19,menu_id FROM system_menu WHERE perms='da:dataSource:query' AND status='0'");assign(9,143);assertTrue(access.datasourceOptions(144L).isEmpty());assign(9,144);var rows=access.datasourceOptions(144L);assertEquals(1,rows.size());assertEquals(9L,((Number)rows.get(0).get("id")).longValue());assertFalse(rows.get(0).containsKey("datasourceConfig"));assertEquals(true,rows.get(0).get("validFlag"));}

    @Test void physicalTargetOptionsExcludeHiveAndRequireEveryModelProject(){published(900144);assign(9,144);assign(13,143);assertTrue(access.materializationSources(List.of(900144L)).isEmpty());published(900143);assertThrows(AccessDeniedException.class,()->access.materializationSources(List.of(900144L,900143L)));}

}

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

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class AssetChildProjectIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    AssetMutationAccess mutations;AssetProjectReadAccess read;DataSourceFactory transport;DaAssetController controller;DaAssetColumnController columnController;IDaAssetService service;IDaAssetColumnService columnService;
    AssetChildAccess children;AssetChildReferences references;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_ASSET_CHILD_URL");assertNotNull(url);assertTrue(url.contains("/qdata_asset_child_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        transport=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);for(long id:new long[]{9,13,16,17,18})when(sources.getDaDatasourceById(id)).thenAnswer(call->HiveEndpointFixture.source(jdbc,id));
        ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(new HiveNamespaceBrowser(jdbc,sources,transport));var data=new ProjectDataResourceAccess(jdbc,provider);var permissions=new ProjectPermissionService(jdbc);mutations=new AssetMutationAccess(jdbc,data,permissions);read=new AssetProjectReadAccess(jdbc,data,permissions);
        service=mock(IDaAssetService.class);columnService=mock(IDaAssetColumnService.class);controller=new DaAssetController();columnController=new DaAssetColumnController();
        ReflectionTestUtils.setField(controller,"resourceAccess",data);ReflectionTestUtils.setField(controller,"readAccess",read);ReflectionTestUtils.setField(controller,"mutations",mutations);ReflectionTestUtils.setField(controller,"daAssetService",service);
        ReflectionTestUtils.setField(columnController,"resourceAccess",data);ReflectionTestUtils.setField(columnController,"readAccess",read);ReflectionTestUtils.setField(columnController,"mutations",mutations);ReflectionTestUtils.setField(columnController,"referenceGuard",new AssetReferenceGuard(jdbc));ReflectionTestUtils.setField(columnController,"daAssetColumnService",columnService);
        actor(927);references=new AssetChildReferences(jdbc);children=new AssetChildAccess(jdbc,read,mutations,references);
        for(var kind:AssetChildAccess.Kind.values())for(int project:new int[]{144,143,145})seedChild(kind,project);

    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("asset-mutation-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seed(long suffix,long source,List<Long> projects){long id=900000+suffix;copy("da_asset",189,id,Map.of("name","asset_write_"+suffix,"datasource_id",String.valueOf(source),"creator_id",925L));for(Long project:projects)jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag) SELECT ?,id,code,'1','0' FROM att_project WHERE id=?",id,project);copy("da_asset_column",4044,910000+suffix,Map.of("asset_id",id,"creator_id",925L));}
    DaAssetSaveReqVO edit(long id){var r=new DaAssetSaveReqVO();r.setId(id);r.setDescription("scope edit");return r;}
    DaAssetColumnSaveReqVO column(long id){var r=new DaAssetColumnSaveReqVO();r.setId(id);r.setColumnComment("scope comment");return r;}

    String table(Kind k){return "da_asset_"+k.name().toLowerCase(Locale.ROOT).replace("param","api_param");}
    long asset(Kind k,int p){return 900000+(k==Kind.PARAM?0:k.ordinal())*1000+p;}
    long id(Kind k,int p){return 910000+k.ordinal()*1000+p;}
    String stem(Kind k){return k==Kind.PARAM?"ApiParam":k.name().substring(0,1)+k.name().substring(1).toLowerCase(Locale.ROOT);}
    String segment(Kind k){return k==Kind.PARAM?"api":k.name().toLowerCase(Locale.ROOT);}
    void insertRequired(String table,long id,Map<String,Object> changes){
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",id);
        for(var column:jdbc.queryForList("SELECT COLUMN_NAME name,DATA_TYPE type,COLUMN_DEFAULT default_value,IS_NULLABLE nullable FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",table)){
            String name=String.valueOf(column.get("name")),type=String.valueOf(column.get("type"));if(row.containsKey(name) || changes.containsKey(name))continue;
            if("NO".equals(column.get("nullable")) && column.get("default_value")==null){row.put(name,Set.of("bigint","int","decimal","tinyint").contains(type)?0L: type.contains("date")?new java.sql.Timestamp(System.currentTimeMillis()):"char".equals(type)?"1":"fixture");}
        }
        row.putAll(Map.of("valid_flag","1","del_flag","0","creator_id",925L,"create_by","original-child-creator"));row.putAll(changes);
        jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    }
    void seedChild(Kind k,int p){
        if(k!=Kind.PARAM){copy("da_asset",189,asset(k,p),Map.of("type",String.valueOf(k.ordinal()+2),"valid_flag","1","creator_id",925L));for(long project:p==145?List.of(143L,144L):List.of((long)p))jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag) SELECT ?,id,code,'1','0' FROM att_project WHERE id=?",asset(k,p),project);}
        insertRequired(table(k),id(k,p),k==Kind.PARAM?Map.of("api_id",id(Kind.API,p),"name","scope_param","type","2","parent_id",0L):Map.of("asset_id",asset(k,p)));
    }
    Class<? extends BaseEntity> saveType(Kind k) throws Exception{return Class.forName("tech.qiantong.qdata.module.da.controller.admin.assetchild."+segment(k)+".vo.DaAsset"+stem(k)+"SaveReqVO").asSubclass(BaseEntity.class);}
    BaseEntity request(Kind k,int p,boolean update)throws Exception{var r=saveType(k).getConstructor().newInstance();var bean=new BeanWrapperImpl(r);if(update)bean.setPropertyValue("id",id(k,p));else bean.setPropertyValue(k==Kind.PARAM?"apiId":"assetId",k==Kind.PARAM?id(Kind.API,p):asset(k,p));return r;}
    @ParameterizedTest @EnumSource(Kind.class) void listIncludesBothMembershipsButExportExcludesReadonly(Kind k){assertTrue(children.ids(k,"list").containsAll(List.of(id(k,144),id(k,143),id(k,145))));assertFalse(children.ids(k,"export").contains(id(k,143)));assertTrue(children.ids(k,"export").contains(id(k,144)));}
    @ParameterizedTest @EnumSource(Kind.class) void readonlyUpdateCannotBorrowWriterPermission(Kind k)throws Exception{assertThrows(AccessDeniedException.class,()->children.save(k,request(k,143,true),true));}
    @ParameterizedTest @EnumSource(Kind.class) void sharedUpdateRequiresAllOwners(Kind k)throws Exception{assertThrows(AccessDeniedException.class,()->children.save(k,request(k,145,true),true));}
    @ParameterizedTest @EnumSource(Kind.class) void writerUpdatePreservesCreationAudit(Kind k)throws Exception{var r=request(k,144,true);r.setCreatorId(1L);r.setCreateBy("forged");children.save(k,r,true);assertEquals(925L,r.getCreatorId());assertEquals("original-child-creator",r.getCreateBy());assertEquals(927L,r.getUpdatorId());}
    @ParameterizedTest @EnumSource(Kind.class) void parentCannotBeMigrated(Kind k)throws Exception{var r=request(k,144,true);new BeanWrapperImpl(r).setPropertyValue(k==Kind.PARAM?"apiId":"assetId",k==Kind.PARAM?id(Kind.API,143):asset(k,143));assertThrows(AccessDeniedException.class,()->children.save(k,r,true));}
    @ParameterizedTest @EnumSource(Kind.class) void mixedRemovalsAreRejectedBeforeAnyDelete(Kind k){assertThrows(AccessDeniedException.class,()->children.remove(k,List.of(id(k,144),id(k,143))));assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM "+table(k)+" WHERE id=? AND del_flag='0'",Long.class,id(k,144)));}
    @ParameterizedTest @EnumSource(Kind.class) void foreignParentCannotReceiveChild(Kind k)throws Exception{assertThrows(AccessDeniedException.class,()->children.save(k,request(k,143,false),false));}
    @ParameterizedTest @EnumSource(value=Kind.class,names={"API","GIS","GEO","VIDEO","FILES"}) void oneToOneResourceCannotBeDuplicated(Kind k)throws Exception{assertThrows(IllegalArgumentException.class,()->children.save(k,request(k,144,false),false));}
    @ParameterizedTest @EnumSource(Kind.class) void wrongAssetTypeIsRejected(Kind k)throws Exception{jdbc.update("UPDATE da_asset SET type='1' WHERE id=?",asset(k,144));assertThrows(AccessDeniedException.class,()->children.save(k,request(k,144,true),true));}
    @Test void parameterCannotUseAnotherApisParent()throws Exception{var r=request(Kind.PARAM,144,true);new BeanWrapperImpl(r).setPropertyValue("parentId",id(Kind.PARAM,143));assertThrows(AccessDeniedException.class,()->children.save(Kind.PARAM,r,true));}
    @Test void parameterCannotBecomeItsOwnAncestor()throws Exception{var r=request(Kind.PARAM,144,true);new BeanWrapperImpl(r).setPropertyValue("parentId",id(Kind.PARAM,144));assertThrows(IllegalArgumentException.class,()->children.save(Kind.PARAM,r,true));}
    @Test void parameterCannotBeDeletedWhileChildrenRemain(){insertRequired("da_asset_api_param",950001,Map.of("api_id",id(Kind.API,144),"parent_id",id(Kind.PARAM,144),"name","child","type","2"));assertThrows(IllegalArgumentException.class,()->children.remove(Kind.PARAM,List.of(id(Kind.PARAM,144))));}
    @Test void parameterTreeRequiresActualProjectFunction(){assertDoesNotThrow(()->children.parameterTree(id(Kind.API,144)));jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=20 AND m.perms='da:assetApiParam:list'");assertThrows(AccessDeniedException.class,()->children.parameterTree(id(Kind.API,143)));}
    @Test void importPreflightRejectsReadonlyRow(){var a=new DaAssetApiRespVO();a.setId(id(Kind.API,144));var b=new DaAssetApiRespVO();b.setId(id(Kind.API,143));assertThrows(AccessDeniedException.class,()->children.imports(Kind.API,List.of(a,b),true,DaAssetApiSaveReqVO.class));}
    @Test void importCannotCreateCycleAcrossOtherwiseValidEdits(){insertRequired("da_asset_api_param",950001,Map.of("api_id",id(Kind.API,144),"parent_id",0L,"name","another","type","2"));var a=new tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamRespVO();a.setId(id(Kind.PARAM,144));a.setParentId(950001L);var b=new tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamRespVO();b.setId(950001L);b.setParentId(id(Kind.PARAM,144));assertThrows(IllegalArgumentException.class,()->children.imports(Kind.PARAM,List.of(a,b),true,DaAssetApiParamSaveReqVO.class));}
    void reference(Kind kind){long source=jdbc.queryForObject("SELECT MIN(id) FROM ds_api",Long.class);copy("ds_api",source,960001,Map.of("api_service_type","3","transmit_type",kind==Kind.GIS?"2":"1","api_id",String.valueOf(id(kind,144)),"del_flag","0"));}
    @ParameterizedTest @EnumSource(value=Kind.class,names={"API","GIS"}) void referencedEndpointCannotBeChangedOrDeleted(Kind k)throws Exception{reference(k);var r=request(k,144,true);new BeanWrapperImpl(r).setPropertyValue("url","http://127.0.0.1:1/changed");assertThrows(IllegalArgumentException.class,()->children.save(k,r,true));assertThrows(IllegalArgumentException.class,()->children.remove(k,List.of(id(k,144))));}
    @Test void referencedApiCannotRebuildParameterIds(){reference(Kind.API);assertThrows(IllegalArgumentException.class,()->references.replaceParameters(id(Kind.API,144),true));}
    @Test void referencedParameterCannotChangeContract()throws Exception{reference(Kind.API);var r=request(Kind.PARAM,144,true);new BeanWrapperImpl(r).setPropertyValue("name","changed");assertThrows(IllegalArgumentException.class,()->children.save(Kind.PARAM,r,true));}
    @Test void permittedApplicationNameIsVisibleInCardProjection(){var r=new DaAssetRespVO();r.setId(asset(Kind.API,144));r.setType("2");jdbc.update("UPDATE da_asset_api SET app_name='Project application' WHERE id=?",id(Kind.API,144));assertEquals("Project application",read.present(r).getAppName());}
    @Test void controllerOverwritesClientSuppliedScopeBeforeQuery(){var controller=new tech.qiantong.qdata.module.da.controller.admin.assetchild.api.DaAssetApiController();var service=mock(tech.qiantong.qdata.module.da.service.assetchild.api.IDaAssetApiService.class);ReflectionTestUtils.setField(controller,"children",children);ReflectionTestUtils.setField(controller,"daAssetApiService",service);when(service.getDaAssetApiPage(any())).thenReturn(tech.qiantong.qdata.common.core.page.PageResult.empty());var query=new tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiPageReqVO();query.setAuthorizedIds(List.of(99999999L));controller.list(query);assertFalse(query.getAuthorizedIds().contains(99999999L));assertTrue(query.getAuthorizedIds().contains(id(Kind.API,144)));}

    @ParameterizedTest @EnumSource(Kind.class) void spreadsheetRoundTripRetainsResourceConfiguration(Kind kind) throws Exception {
        var type=Class.forName("tech.qiantong.qdata.module.da.controller.admin.assetchild."+segment(kind)+".vo.DaAsset"+stem(kind)+"RespVO");var row=type.getConstructor().newInstance();var bean=new BeanWrapperImpl(row);
        Map<String,Object> fields=switch(kind){
            case API->Map.of("url","http://127.0.0.1:1","httpMethod","GET","appName","Scoped application","developerName","Scoped developer");
            case GIS->Map.of("url","http://127.0.0.1:1","httpMethod","GET","type","1","coordinateSystem","EPSG:4326");
            case GEO->Map.of("fileName","scope.geojson","fileUrl","/retained/scope.geojson","fileType","geojson","elementType","1","coordinateSystem","EPSG:4326");
            case VIDEO->Map.of("ip","127.0.0.1","port",1L,"protocol","RTSP","platform","1","config","{}");
            case FILES->Map.of("url","/retained/scope.csv","type","csv","startColumn",2,"startData",3);
            case PARAM->Map.of("name","scope_parameter","type","2","columnType","STRING","defaultValue","default","exampleValue","example","description","description");
        };
        fields.forEach(bean::setPropertyValue);var response=new org.springframework.mock.web.MockHttpServletResponse();
        new tech.qiantong.qdata.common.utils.poi.ExcelUtil(type).exportExcel(response,List.of(row),"child-roundtrip");
        var imported=new tech.qiantong.qdata.common.utils.poi.ExcelUtil(type).importExcel(new java.io.ByteArrayInputStream(response.getContentAsByteArray()));
        var actual=new BeanWrapperImpl(imported.get(0));for(String field:fields.keySet())assertEquals(bean.getPropertyValue(field),actual.getPropertyValue(field),kind+"."+field);
    }
    @ParameterizedTest @EnumSource(Kind.class) void businessUpdateNeverRewritesCreationAudit(Kind kind) throws Exception {
        var serviceType=Class.forName("tech.qiantong.qdata.module.da.service.assetchild."+segment(kind)+".impl.DaAsset"+stem(kind)+"ServiceImpl");var service=serviceType.getConstructor().newInstance();
        var mapperType=Class.forName("tech.qiantong.qdata.module.da.dal.mapper.assetchild."+segment(kind)+".DaAsset"+stem(kind)+"Mapper");var mapper=mock(mapperType);
        ReflectionTestUtils.setField(service,"daAsset"+stem(kind)+"Mapper",mapper);
        if(kind==Kind.API || kind==Kind.GIS || kind==Kind.PARAM)ReflectionTestUtils.setField(service,"childReferences",references);
        if(kind==Kind.PARAM)ReflectionTestUtils.setField(service,"parameterGraph",new ApiParameterGraph(jdbc,references));
        if(kind==Kind.FILES){ReflectionTestUtils.setField(service,"lifecycle",mock(FileAssetLifecycle.class));when(((com.baomidou.mybatisplus.core.mapper.BaseMapper)mapper).updateById(any(tech.qiantong.qdata.module.da.dal.dataobject.assetchild.files.DaAssetFilesDO.class))).thenReturn(1);}
        if(kind==Kind.GEO){when(((tech.qiantong.qdata.module.da.dal.mapper.assetchild.geo.DaAssetGeoMapper)mapper).selectById(anyLong())).thenAnswer(call->{var stored=jdbc.queryForMap("SELECT asset_id,file_url FROM da_asset_geo WHERE id=?",(Object)call.getArgument(0));var existing=new tech.qiantong.qdata.module.da.dal.dataobject.assetchild.geo.DaAssetGeoDO();existing.setAssetId(((Number)stored.get("asset_id")).longValue());existing.setFileUrl((String)stored.get("file_url"));return existing;});ReflectionTestUtils.setField(service,"uploadedFiles",mock(tech.qiantong.qdata.common.file.UploadedFileAccess.class));}
        var request=request(kind,144,true);request.setCreatorId(1L);request.setCreateBy("forged");request.setCreateTime(new java.util.Date());
        serviceType.getMethod("updateDaAsset"+stem(kind),saveType(kind)).invoke(service,request);
        var captured=org.mockito.ArgumentCaptor.forClass(Object.class);
        verify((com.baomidou.mybatisplus.core.mapper.BaseMapper)mapper).updateById(captured.capture());
        var entity=new BeanWrapperImpl(captured.getValue());assertNull(entity.getPropertyValue("creatorId"));assertNull(entity.getPropertyValue("createBy"));assertNull(entity.getPropertyValue("createTime"));
    }

}

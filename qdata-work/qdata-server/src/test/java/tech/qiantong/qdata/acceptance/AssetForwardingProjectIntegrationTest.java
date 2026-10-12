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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class AssetForwardingProjectIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    AssetMutationAccess mutations;AssetProjectReadAccess read;DataSourceFactory transport;DaAssetController controller;DaAssetColumnController columnController;IDaAssetService service;IDaAssetColumnService columnService;
    AssetForwardingAccess forwarding;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_ASSET_FORWARD_URL");assertNotNull(url);assertTrue(url.contains("/qdata_asset_forward_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        transport=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);for(long id:new long[]{9,13,16,17,18})when(sources.getDaDatasourceById(id)).thenAnswer(call->HiveEndpointFixture.source(jdbc,id));
        ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(new HiveNamespaceBrowser(jdbc,sources,transport));var data=new ProjectDataResourceAccess(jdbc,provider);var permissions=new ProjectPermissionService(jdbc);mutations=new AssetMutationAccess(jdbc,data,permissions);read=new AssetProjectReadAccess(jdbc,data,permissions);
        service=mock(IDaAssetService.class);columnService=mock(IDaAssetColumnService.class);controller=new DaAssetController();columnController=new DaAssetColumnController();
        ReflectionTestUtils.setField(controller,"resourceAccess",data);ReflectionTestUtils.setField(controller,"readAccess",read);ReflectionTestUtils.setField(controller,"mutations",mutations);ReflectionTestUtils.setField(controller,"daAssetService",service);
        ReflectionTestUtils.setField(columnController,"resourceAccess",data);ReflectionTestUtils.setField(columnController,"readAccess",read);ReflectionTestUtils.setField(columnController,"mutations",mutations);ReflectionTestUtils.setField(columnController,"referenceGuard",new AssetReferenceGuard(jdbc));ReflectionTestUtils.setField(columnController,"daAssetColumnService",columnService);
        seed(144,16,List.of(144L));seed(143,17,List.of(143L));seed(145,18,List.of(143L,144L));actor(927);forwarding=new AssetForwardingAccess(jdbc,mutations);
        for(long id:new long[]{900144,900143,900145}){jdbc.update("UPDATE da_asset SET type='2',valid_flag='1' WHERE id=?",id);jdbc.update("INSERT INTO da_asset_api(id,asset_id,url,http_method,developer_name,app_name,valid_flag,del_flag) VALUES(?,?,'http://127.0.0.1:1','GET','fixture','fixture','1','0')",id,id);}
        for(long suffix:new long[]{144,143,145}){long id=920000+suffix;copy("da_asset",900000+suffix,id,Map.of("type","3"));var projects=suffix==145?List.of(143L,144L):List.of(suffix);for(long project:projects)jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag) SELECT ?,id,code,'1','0' FROM att_project WHERE id=?",id,project);jdbc.update("INSERT INTO da_asset_gis(id,asset_id,url,type,http_method,coordinate_system,valid_flag,del_flag) VALUES(?,?,'http://127.0.0.1:1','1','GET','EPSG:4326','1','0')",id,id);}

    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("asset-mutation-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seed(long suffix,long source,List<Long> projects){long id=900000+suffix;copy("da_asset",189,id,Map.of("name","asset_write_"+suffix,"datasource_id",String.valueOf(source),"creator_id",925L));for(Long project:projects)jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag) SELECT ?,id,code,'1','0' FROM att_project WHERE id=?",id,project);copy("da_asset_column",4044,910000+suffix,Map.of("asset_id",id,"creator_id",925L));}
    DaAssetSaveReqVO edit(long id){var r=new DaAssetSaveReqVO();r.setId(id);r.setDescription("scope edit");return r;}
    DaAssetColumnSaveReqVO column(long id){var r=new DaAssetColumnSaveReqVO();r.setId(id);r.setColumnComment("scope comment");return r;}

    long child(String kind,int project){return (kind.equals("api")?900000:920000)+project;}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void writerCanSimulateOwnedResource(String kind){long id=child(kind,144);assertEquals(id,forwarding.authorize(kind,id,null));}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void readonlyProjectCannotBorrowWriterGrant(String kind){long id=child(kind,143);assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,id,null));}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void sharedSimulationRequiresAllOwners(String kind){long id=child(kind,145);assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,id,null));}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void forgedParentCannotAuthorizeAnotherResource(String kind){long id=child(kind,144);assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,id,900143L));}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void disabledChildCannotSendRequests(String kind){long id=child(kind,144);jdbc.update("UPDATE da_asset_"+kind+" SET valid_flag='0' WHERE id=?",id);assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,id,null));}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void deletedChildCannotSendRequests(String kind){long id=child(kind,144);jdbc.update("UPDATE da_asset_"+kind+" SET del_flag='1' WHERE id=?",id);assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,id,null));}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void wrongAssetTypeIsRejected(String kind){long id=child(kind,144);jdbc.update("UPDATE da_asset SET type='1' WHERE id=?",id);assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,id,null));}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void missingIdentifierCannotSendRequests(String kind){assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,null,null));assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,99999999L,null));}
    @ParameterizedTest @ValueSource(strings={"api","gis"}) void disabledParentCannotBeInvoked(String kind){long id=child(kind,144);jdbc.update("UPDATE da_asset SET valid_flag='0' WHERE id=?",id);assertThrows(AccessDeniedException.class,()->forwarding.authorize(kind,id,null));}
    @Test void apiServiceDeniesBeforeOutboundExecution(){var service=spy(new tech.qiantong.qdata.module.da.service.assetchild.api.impl.DaAssetApiServiceImpl());ReflectionTestUtils.setField(service,"forwardingAccess",forwarding);var response=new org.springframework.mock.web.MockHttpServletResponse();doNothing().when(service).executeServiceForwarding(any(),any(),any());var request=new DaAssetApiReqVO();request.setId(900143L);assertThrows(AccessDeniedException.class,()->service.queryServiceForwarding(response,request));verify(service,never()).executeServiceForwarding(any(),any(),any());}
    @Test void apiServicePassesActualParentAndStoredChildToExecution(){var service=spy(new tech.qiantong.qdata.module.da.service.assetchild.api.impl.DaAssetApiServiceImpl());ReflectionTestUtils.setField(service,"forwardingAccess",forwarding);var response=new org.springframework.mock.web.MockHttpServletResponse();doNothing().when(service).executeServiceForwarding(any(),any(),any());var request=new DaAssetApiReqVO();request.setId(900144L);request.setQueryParams(Map.of());service.queryServiceForwarding(response,request);assertEquals(900144L,request.getAssetId());verify(service).executeServiceForwarding(response,900144L,Map.of());}
    @Test void gisServiceDeniesBeforeOutboundExecution(){var service=spy(new tech.qiantong.qdata.module.da.service.assetchild.gis.impl.DaAssetGisServiceImpl());ReflectionTestUtils.setField(service,"forwardingAccess",forwarding);var response=new org.springframework.mock.web.MockHttpServletResponse();doNothing().when(service).executeServiceForwarding(any(),any(),any());var request=new tech.qiantong.qdata.module.da.controller.admin.assetchild.gis.vo.DaAssetGisReqVO();request.setId(920143L);assertThrows(AccessDeniedException.class,()->service.queryServiceForwarding(response,request));verify(service,never()).executeServiceForwarding(any(),any(),any());}
    @Test void gisServicePassesAuthorizedChildToExecution(){var service=spy(new tech.qiantong.qdata.module.da.service.assetchild.gis.impl.DaAssetGisServiceImpl());ReflectionTestUtils.setField(service,"forwardingAccess",forwarding);var response=new org.springframework.mock.web.MockHttpServletResponse();doNothing().when(service).executeServiceForwarding(any(),any(),any());var request=new tech.qiantong.qdata.module.da.controller.admin.assetchild.gis.vo.DaAssetGisReqVO();request.setId(920144L);request.setQueryParams(Map.of());service.queryServiceForwarding(response,request);assertEquals(920144L,request.getAssetId());verify(service).executeServiceForwarding(response,920144L,Map.of());}
}

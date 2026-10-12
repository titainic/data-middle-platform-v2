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
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.da.service.asset.*;
import tech.qiantong.qdata.module.da.service.assetColumn.IDaAssetColumnService;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.da.controller.admin.asset.DaAssetController;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.*;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.DaAssetColumnController;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class AssetReadProjectIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    ProjectDataResourceAccess data;AssetProjectReadAccess access;DataSourceFactory transport;IDaAssetService service;IDaAssetColumnService columnService;DaAssetController controller;DaAssetColumnController columnController;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_ASSET_READ_URL");assertNotNull(url);assertTrue(url.contains("/qdata_asset_read_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        transport=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);for(long id:new long[]{9,13,16,17,18})when(sources.getDaDatasourceById(id)).thenAnswer(call->HiveEndpointFixture.source(jdbc,id));
        ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(new HiveNamespaceBrowser(jdbc,sources,transport));data=new ProjectDataResourceAccess(jdbc,provider);access=new AssetProjectReadAccess(jdbc,data,new ProjectPermissionService(jdbc));
        service=mock(IDaAssetService.class);controller=new DaAssetController();ReflectionTestUtils.setField(controller,"resourceAccess",data);ReflectionTestUtils.setField(controller,"readAccess",access);ReflectionTestUtils.setField(controller,"daAssetService",service);
        columnService=mock(IDaAssetColumnService.class);columnController=new DaAssetColumnController();ReflectionTestUtils.setField(columnController,"resourceAccess",data);ReflectionTestUtils.setField(columnController,"readAccess",access);ReflectionTestUtils.setField(columnController,"daAssetColumnService",columnService);
        seed(144,16,List.of(144L));seed(143,17,List.of(143L));seed(145,18,List.of(143L,144L));actor(927);
    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("asset-read-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seed(long suffix,long source,List<Long> projects){long id=900000+suffix;copy("da_asset",188,id,Map.of("name","asset_scope_"+suffix,"datasource_id",String.valueOf(source),"table_name","scope_table","database_name","scope_db","creator_id",925L));for(Long project:projects)jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag) SELECT ?,id,code,'1','0' FROM att_project WHERE id=?",id,project);Long template=jdbc.queryForObject("SELECT MIN(id) FROM da_asset_column WHERE asset_id=99 AND del_flag='0'",Long.class);copy("da_asset_column",template,910000+suffix,Map.of("asset_id",id,"column_name","scope_value"));}
    @Test void listAndDetailCapabilitiesCannotBeBorrowedAcrossProjects(){assertTrue(access.ids("da:asset:list").containsAll(List.of(900143L,900144L,900145L)));assertDoesNotThrow(()->access.asset(900144L,"da:asset:query"));assertThrows(AccessDeniedException.class,()->access.asset(900143L,"da:asset:query"));}
    @Test void sharedAssetReadNeedsOneOwningProjectGrant(){assertDoesNotThrow(()->access.asset(900145L,"da:asset:query"));assertTrue(access.ids("da:asset:export").contains(900145L));}
    @Test void exportUsesActualProjectFunction(){assertTrue(access.ids("da:asset:export").contains(900144L));assertFalse(access.ids("da:asset:export").contains(900143L));}
    @Test void controllerRejectsReaderDetailBeforeDataService(){assertThrows(AccessDeniedException.class,()->controller.getInfo(900143L));verifyNoInteractions(service);}
    @Test void columnPermissionIsCheckedInItsParentProject(){assertDoesNotThrow(()->access.column(910144L,"da:assetColumn:query"));assertThrows(AccessDeniedException.class,()->access.column(910143L,"da:assetColumn:query"));}
    @Test void columnExportRejectsBeforeWritingResponse(){var request=new DaAssetColumnPageReqVO();request.setAssetId("900143");var response=new org.springframework.mock.web.MockHttpServletResponse();assertThrows(AccessDeniedException.class,()->columnController.export(response,request));assertEquals(0,response.getContentAsByteArray().length);verifyNoInteractions(columnService);}
    @Test void columnListCannotBorrowGlobalFieldPermission(){var request=new DaAssetColumnPageReqVO();request.setAssetId("900143");assertThrows(AccessDeniedException.class,()->columnController.list(request));verifyNoInteractions(columnService);}
    @Test void columnDetailCannotBorrowGlobalFieldPermission(){assertThrows(AccessDeniedException.class,()->columnController.getInfo(910143L));verifyNoInteractions(columnService);}
    @Test void listReplacesForgedScopeBeforePagination(){when(service.getDaAssetPage(any(),eq("1"))).thenReturn(PageResult.empty());var request=new DaAssetPageReqVO();request.setAccessibleAssetIds(List.of(113L));controller.list(request);assertFalse(request.getAccessibleAssetIds().contains(113L));assertTrue(request.getAccessibleAssetIds().contains(900143L));}
    @Test void explicitForeignProjectCannotReturnAnotherListOrExport(){var request=new DaAssetPageReqVO();request.setProjectId(1L);assertThrows(AccessDeniedException.class,()->controller.list(request));assertThrows(AccessDeniedException.class,()->controller.export(new org.springframework.mock.web.MockHttpServletResponse(),request));verifyNoInteractions(service);}
    @Test void exportFilterReplacesForgedAccessibleIds(){when(service.getDaAssetPage(any(),eq("1"))).thenReturn(PageResult.empty());var request=new DaAssetPageReqVO();request.setAccessibleAssetIds(List.of(900143L));controller.export(new org.springframework.mock.web.MockHttpServletResponse(),request);assertFalse(request.getAccessibleAssetIds().contains(900143L));assertTrue(request.getAccessibleAssetIds().contains(900144L));}
    @Test void noPermissionMembershipDoesNotExposeAssets(){jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=20 AND m.perms LIKE 'da:asset%'");assertFalse(access.ids("da:asset:list").contains(900143L));assertTrue(access.ids("da:asset:list").contains(900144L));}
    @Test void inactiveProjectCannotUseItsGrants(){jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertThrows(AccessDeniedException.class,()->access.asset(900144L,"da:asset:query"));assertThrows(AccessDeniedException.class,()->access.asset(900145L,"da:asset:query"));}
    @Test void managedSpaceOwnershipOverridesManualSharing(){Long dataset=jdbc.queryForObject("SELECT MIN(dataset_id) FROM da_asset WHERE dataset_id IS NOT NULL",Long.class);assertNotNull(dataset);jdbc.update("UPDATE da_asset SET dataset_id=? WHERE id=900144",dataset);assertThrows(AccessDeniedException.class,()->access.asset(900144L,"da:asset:query"));}
    @Test void metadataProjectionShowsPerAssetQueryCapability(){var row=new DaAssetRespVO();row.setId(900143L);assertFalse(access.present(row).getReadCapabilities().get("query"));row.setId(900144L);assertTrue(access.present(row).getReadCapabilities().get("query"));}
    @Test void previewCannotUseDiscoveryTaskToReplaceAuthorizedTarget(){var request=new cn.hutool.json.JSONObject();request.put("id",900144);request.put("taskId",7);request.put("tableName","other_table");assertThrows(AccessDeniedException.class,()->controller.getPreview(request));verifyNoInteractions(service);}
    @Test void previewRejectsDisabledSourceBeforeOpeningTransport(){assertThrows(AccessDeniedException.class,()->access.preview(900144L,new cn.hutool.json.JSONObject()));}
    @Test void authorizedPreviewGuardDoesNotItselfOpenTransport(){jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=16");assertDoesNotThrow(()->access.preview(900144L,new cn.hutool.json.JSONObject()));}
    @Test void registeredAssetCannotLaunderForeignHiveFunction(){jdbc.update("INSERT INTO att_project_user_rel(project_id,user_id,valid_flag,del_flag) VALUES(1,927,'1','0')");jdbc.update("UPDATE da_asset SET datasource_id=9,database_name='dwd_iot_db',table_name='water_quality' WHERE id=900144");assertThrows(AccessDeniedException.class,()->access.preview(900144L,new cn.hutool.json.JSONObject()));}
    @Test void ruleLookupCannotAggregateForeignAssetWithSamePhysicalName(){jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=18");jdbc.update("UPDATE da_asset SET datasource_id=18 WHERE id IN(900143,900144)");assertThrows(AccessDeniedException.class,()->access.physicalRules(18L,"scope_table"));}
    @Test void privateUnassignedAssetStaysCreatorOnly(){jdbc.update("UPDATE da_asset_project_rel SET valid_flag='0' WHERE asset_id=900144");jdbc.update("UPDATE da_asset SET creator_id=927 WHERE id=900144");assertDoesNotThrow(()->access.asset(900144L,"da:asset:query"));actor(926);assertThrows(AccessDeniedException.class,()->access.asset(900144L,"da:asset:query"));}
    @ParameterizedTest @ValueSource(strings={"1=1 UNION SELECT * FROM qdata_asset_preview","scope_value IN (SELECT id FROM secret_table)","1=1; SELECT * FROM secret_table","sleep(1)=0","unknown_field='x'"})
    void previewFilterCannotChangeQueryShapeOrReachUndeclaredFields(String filter){var request=new cn.hutool.json.JSONObject();request.put("filter",filter);assertThrows(IllegalArgumentException.class,()->access.preview(900144L,request));}
    @Test void previewOrderCannotInjectSql(){var request=new cn.hutool.json.JSONObject();request.put("orderBy",List.of(Map.of("orderByColumn","scope_value","isAsc","asc; SELECT 1")));assertThrows(IllegalArgumentException.class,()->access.preview(900144L,request));}
    @Test void currentAssetFilterAndSortRemainUsable(){jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=16");var request=new cn.hutool.json.JSONObject();request.put("filter","scope_value = 'ok' OR scope_value IS NULL");request.put("orderBy",List.of(Map.of("orderByColumn","scope_value","isAsc","asc")));assertDoesNotThrow(()->access.preview(900144L,request));}
    @Test void registeredSortExpressionCannotBecomeSql(){jdbc.update("UPDATE da_asset_column SET column_name='scope_value; SELECT 1' WHERE id=910144");var request=new cn.hutool.json.JSONObject();request.put("orderBy",List.of(Map.of("orderByColumn","scope_value; SELECT 1","isAsc","asc")));assertThrows(IllegalArgumentException.class,()->access.preview(900144L,request));}

}

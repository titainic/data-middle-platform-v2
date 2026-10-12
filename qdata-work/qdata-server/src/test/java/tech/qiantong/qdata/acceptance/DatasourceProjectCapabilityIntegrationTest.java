package tech.qiantong.qdata.acceptance;

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
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.module.da.controller.admin.datasource.*;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.*;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.*;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class DatasourceProjectCapabilityIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    DatasourceProjectAccess access;ProjectDataResourceAccess data;IDaDatasourceService service;DaDatasourceController controller;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_DATASOURCE_PROJECT_URL");assertNotNull(url);assertTrue(url.contains("/qdata_datasource_project_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        data=spy(new ProjectDataResourceAccess(jdbc,mock(ObjectProvider.class)));access=new DatasourceProjectAccess(jdbc,data,new ProjectPermissionService(jdbc));service=mock(IDaDatasourceService.class);controller=new DaDatasourceController(service,mock(tech.qiantong.qdata.module.da.service.asset.IDaAssetService.class),mock(HiveNamespaceBrowser.class));ReflectionTestUtils.setField(controller,"resourceAccess",data);ReflectionTestUtils.setField(controller,"projectAccess",access);
        actor(927);seed(900144,144);seed(900143,143);seed(900145,144);bind(900145,143);seed(900146,144);jdbc.update("UPDATE da_datasource_project_rel SET valid_flag='0' WHERE datasource_id=900146");
    }
    @AfterEach void close(){manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("source-scope-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void seed(long id,long project){jdbc.update("INSERT INTO da_datasource(id,datasource_name,datasource_type,ip,port,datasource_config,valid_flag,del_flag,creator_id,create_by,list_count,sync_count,data_size) VALUES(?,?,'MySql','127.0.0.1',1,'{\"username\":\"fixture\",\"password\":\"fixture-only\",\"dbname\":\"fixture\"}','0','0',927,'original-author',2,3,4)",id,"scope-"+id);bind(id,project);}
    void bind(long source,long project){jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT ?,id,code,'1' FROM att_project WHERE id=?",source,project);}
    DaDatasourceSaveReqVO request(Long id,Long... projects){var value=new DaDatasourceSaveReqVO();value.setId(id);value.setDatasourceName("fixture");value.setProjectList(Arrays.stream(projects).map(project->DaDatasourceProjectRelDO.builder().projectId(project).projectCode("forged").dppAssigned(true).build()).toList());return value;}
    DaDatasourceDO source(long id){var value=new DaDatasourceDO();value.setId(id);value.setDatasourceName("fixture");value.setDatasourceConfig("{\"username\":\"fixture\",\"password\":\"fixture-only\",\"dbname\":\"fixture\"}");value.setValidFlag(false);value.setProjectList(access.bindings(id));return value;}
    @Test void sameUserCanManageOnlyTheWritingProject(){assertTrue(access.capabilities(900144L).get("edit"));assertFalse(access.capabilities(900143L).get("edit"));assertTrue(access.capabilities(900143L).get("query"));}
    @Test void sharedSourceRequiresPermissionsInAllAssignedProjects(){assertFalse(access.canManage(900145L,"edit"));assertThrows(AccessDeniedException.class,()->access.manage(900145L,"remove"));}
    @Test void owningAProjectDoesNotCreateGlobalAuthorityForPrivateSources(){assertFalse(access.canManage(900146L,"edit"));assertThrows(AccessDeniedException.class,()->access.read(900146L,"query"));}
    @Test void listingAndFilteringUseActualTargetProjectCapabilities(){assertTrue(access.visible("list",false,null).containsAll(List.of(900143L,900144L,900145L)));String code=jdbc.queryForObject("SELECT code FROM att_project WHERE id=143",String.class);assertTrue(access.visible("list",false,code).containsAll(List.of(900143L,900145L)));assertFalse(access.visible("list",false,code).contains(900144L));}
    @Test void foreignProjectFilterReturnsNoSources(){assertTrue(access.visible("list",false,jdbc.queryForObject("SELECT code FROM att_project WHERE id=1",String.class)).isEmpty());}
    @Test void readOnlyProjectCannotCreateAndTestConnection(){assertThrows(AccessDeniedException.class,()->controller.add(request(null,143L)));assertThrows(AccessDeniedException.class,()->controller.testConnection(request(null,143L)));verifyNoInteractions(service);}
    @Test void creationUsesActualProjectCodeAndNoForgedTechnicalFlags(){var value=request(8L,144L);value.setListCount(777L);access.validateSave(value,true,"add");assertNull(value.getId());assertEquals(0L,value.getListCount());assertEquals(jdbc.queryForObject("SELECT code FROM att_project WHERE id=144",String.class),value.getProjectList().get(0).getProjectCode());assertFalse(value.getProjectList().get(0).getDppAssigned());}
    @Test void missingOrDuplicateProjectsCannotCreate(){assertThrows(IllegalArgumentException.class,()->access.validateSave(request(null),true,"add"));assertThrows(IllegalArgumentException.class,()->access.validateSave(request(null,144L,144L),true,"add"));}
    @Test void editingCannotAttachAnUnauthorizedProject(){assertThrows(AccessDeniedException.class,()->controller.edit(request(900144L,143L,144L)));verifyNoInteractions(service);}
    @Test void editingSharedSourceCannotDropTheReadOnlyProjectToGainControl(){assertThrows(AccessDeniedException.class,()->controller.edit(request(900145L,144L)));verifyNoInteractions(service);}
    @Test void editingProtectsCreationAuditAndStatistics(){var value=request(900144L,144L);value.setCreateBy("forged");value.setCreatorId(1L);value.setListCount(99L);access.validateSave(value,false,"edit");assertEquals(927L,value.getCreatorId());assertEquals("original-author",value.getCreateBy());assertEquals(2L,value.getListCount());}
    @Test void editingWithoutNewProjectListPreservesCurrentAssignments(){var value=request(900144L);value.setProjectList(null);access.validateSave(value,false,"edit");assertEquals(144L,value.getProjectList().get(0).getProjectId());}
    @Test void noCrossProjectEnableDeleteOrConnectionTest(){assertThrows(AccessDeniedException.class,()->controller.editDatasourceStatus(900143L,1L));assertThrows(AccessDeniedException.class,()->controller.remove(new Long[]{900143L}));assertThrows(AccessDeniedException.class,()->controller.testConnection(request(900143L,143L)));verifyNoInteractions(service);}
    @Test void mixedDeletesValidateBeforeDelegatingAnyMutation(){assertThrows(AccessDeniedException.class,()->controller.remove(new Long[]{900144L,900143L}));assertThrows(AccessDeniedException.class,()->controller.removeDppOrDa(new Long[]{900144L,900145L},0L));verifyNoInteractions(service);}
    @Test void writerReaderAndSharedCanOnlyFetchSafeConnectionLabels(){for(long id:new long[]{900143,900144,900145})when(service.getDaDatasourceById(id)).thenReturn(source(id));for(long id:new long[]{900143,900144,900145}){String config=controller.getInfo(id).getData().getDatasourceConfig();assertFalse(config.contains("fixture-only"));assertFalse(config.contains("password"));assertTrue(config.contains("fixture"));}}
    @Test void listIsSanitizedEvenForTheWriterAndOverridesClientScope(){when(service.getDaDatasourcePage(any())).thenReturn(new PageResult<>(List.of(source(900144L)),1L));var request=new DaDatasourcePageReqVO();request.setAccessibleDatasourceIds(List.of(9L));var response=controller.list(request);assertFalse(response.getData().getRows().get(0).getDatasourceConfig().contains("fixture-only"));assertFalse(request.getAccessibleDatasourceIds().contains(9L));}
    @Test void disabledConfigurationCannotBeTestedOrQueried(){assertDoesNotThrow(()->access.read(900144L,"query"));assertThrows(AccessDeniedException.class,()->controller.clientsTest(900144L));assertThrows(AccessDeniedException.class,()->controller.getTableList(900144L,null));verifyNoInteractions(service);}
    @Test void sqlQueryPermissionCannotBeBorrowedFromAnotherProject(){jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=900143");var request=new DaDatasourcePageReqVO();request.setId(900143L);assertThrows(AccessDeniedException.class,()->controller.executeSqlQuery(request));verifyNoInteractions(service);}
    @Test void bodyQueryKeepsTheSameTargetProjectBoundary(){jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=900143");var request=new DaDatasourcePageReqVO();request.setId(900143L);assertThrows(AccessDeniedException.class,()->controller.executeSqlQueryBody(request));verifyNoInteractions(service);}
    @Test void formExportCannotBorrowAnotherProjectsQueryPermission(){jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=900143");var request=new DaDatasourcePageReqVO();request.setId(900143L);assertThrows(AccessDeniedException.class,()->controller.exportSqlQueryResult(new org.springframework.mock.web.MockHttpServletResponse(),request));verifyNoInteractions(service);}
    @Test void revokedProjectMembershipRevokesSourceAccess(){jdbc.update("UPDATE att_project_user_rel SET valid_flag='0' WHERE project_id=144 AND user_id=927");assertThrows(AccessDeniedException.class,()->access.read(900144L,"query"));}
    @Test void disabledProjectRevokesSharedManagementAndIndividualRead(){jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertThrows(AccessDeniedException.class,()->access.read(900144L,"query"));assertFalse(access.canManage(900145L,"edit"));}
    @Test void assignmentOptionsIncludeOnlyAuthorizedProjects(){var options=access.assignmentOptions(null);assertTrue(options.stream().anyMatch(p->Long.valueOf(144).equals(((Number)p.get("id")).longValue())));assertFalse(options.stream().anyMatch(p->((Number)p.get("id")).longValue()==143));}
    @Test void rawRelationCannotMoveToAnotherProjectOrExposeOtherProject(){Long rel=jdbc.queryForObject("SELECT id FROM da_datasource_project_rel WHERE datasource_id=900143 AND valid_flag='1'",Long.class);var value=new DaDatasourceProjectRelSaveReqVO();value.setId(rel);value.setDatasourceId(900144L);value.setProjectId(144L);assertThrows(AccessDeniedException.class,()->access.validateRelation(value,false));actor(926);Long foreign=jdbc.queryForObject("SELECT id FROM da_datasource_project_rel WHERE datasource_id=900144 AND valid_flag='1'",Long.class);assertThrows(AccessDeniedException.class,()->access.relation(foreign,false));}
    @Test void rawRelationCannotAttachAReadOnlyProject(){var value=new DaDatasourceProjectRelSaveReqVO();value.setDatasourceId(900144L);value.setProjectId(143L);assertThrows(AccessDeniedException.class,()->access.validateRelation(value,true));}
    @Test void importRequiresImportAndCreateAuthorityInSameProject(){var value=request(null,144L);jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=19 AND m.perms='da:dataSource:import'");assertThrows(AccessDeniedException.class,()->access.validateSave(value,true,"import"));}
    void managedSharedSource(){jdbc.update("UPDATE da_datasource SET valid_flag='1',datasource_type='Hive' WHERE id=900145");doReturn(Set.of(143L)).when(data).owningHiveProjects(eq(900145L),anyString());}
    @Test void hiveDatabaseOwnerMustHaveTheRequestedFunctionalPermission(){managedSharedSource();jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=20 AND m.perms='da:dataSource:query'");assertDoesNotThrow(()->access.read(900145L,"query"));assertThrows(AccessDeniedException.class,()->access.tableScope(900145L,"dwd_iot_db.water","query"));assertFalse(access.databaseAllowed(900145L,"dwd_iot_db","query"));}
    @Test void sqlCannotBorrowDataQueryPermissionForAnotherHiveNamespace(){managedSharedSource();assertDoesNotThrow(()->access.read(900145L,"da:dataQuery:list"));assertThrows(AccessDeniedException.class,()->access.queryScope(900145L,"SELECT ph_value FROM dwd_iot_db.water","da:dataQuery:list"));}
    @Test void namespaceMetadataIsAllowedWhenItsOwnProjectHasQueryPermission(){managedSharedSource();assertDoesNotThrow(()->access.tableScope(900145L,"dwd_iot_db.water","query"));}
}

package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.mc.controller.admin.task.*;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.*;
import tech.qiantong.qdata.module.mc.service.metadata.*;
import tech.qiantong.qdata.module.mc.service.task.*;
import tech.qiantong.qdata.module.mc.service.task.impl.McTaskServiceImpl;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class CollectionProjectCapabilityIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    CollectionResourceAccess access;CollectionProjectAccess projects;CollectionExecutionProjectGuard worker;DataSourceFactory transport;
    IMcTaskService service;McTaskController controller;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_COLLECTION_PROJECT_URL");assertNotNull(url);assertTrue(url.contains("/qdata_collection_project_"));
        fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        transport=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);for(long id:new long[]{9,12,13,16,17,18,19})when(sources.getDaDatasourceById(id)).thenAnswer(call->HiveEndpointFixture.source(jdbc,id));
        var namespaces=new HiveNamespaceBrowser(jdbc,sources,transport);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);
        var data=new ProjectDataResourceAccess(jdbc,provider);var permissions=new ProjectPermissionService(jdbc);projects=new CollectionProjectAccess(jdbc,data,permissions);
        access=new CollectionResourceAccess(jdbc,data,new MetadataResourceAccess(jdbc,data,new MetadataProjectAccess(jdbc,data,permissions)),projects);worker=new CollectionExecutionProjectGuard(jdbc);
        service=mock(IMcTaskService.class);controller=new McTaskController();ReflectionTestUtils.setField(controller,"resources",access);ReflectionTestUtils.setField(controller,"projects",projects);ReflectionTestUtils.setField(controller,"mcTaskService",service);
        seed(900144,144,16);seed(900143,143,17);seed(900145,144,18);seed(900146,143,18);actor(927);
    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("collection-project-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){String key="mc_task_instance_log".equals(table)?"task_instance_id":"id";var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE "+key+"=?",old));row.put(key,id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seed(long id,long project,long source){
        copy("mc_task",9,id,Map.of("project_id",project,"datasource_id",source,"collection_scope","2","table_allowlist","","name","collection_"+id,"status","0","creator_id",925L));
        long scheduler=jdbc.queryForObject("SELECT id FROM mc_task_scheduler WHERE task_id=9 AND del_flag='0' LIMIT 1",Long.class);copy("mc_task_scheduler",scheduler,id,Map.of("task_id",id,"status","0"));
        long scope=jdbc.queryForObject("SELECT MIN(id) FROM mc_task_scope WHERE task_id=9",Long.class);copy("mc_task_scope",scope,id,Map.of("task_id",id,"db_name","collection_fixture"));
        long instance=jdbc.queryForObject("SELECT MAX(id) FROM mc_task_instance WHERE task_id=9",Long.class);copy("mc_task_instance",instance,id,Map.of("task_id",id));
        long log=jdbc.queryForObject("SELECT MAX(task_instance_id) FROM mc_task_instance_log WHERE task_id=9",Long.class);copy("mc_task_instance_log",log,id,Map.of("task_id",id,"task_instance_id",id));
    }
    McTaskSaveReqVO edit(long id){var r=new McTaskSaveReqVO();r.setId(id);r.setDescription("scope change");return r;}
    McTaskSaveReqVO create(long project,long source){var r=new McTaskSaveReqVO();r.setProjectId(project);r.setDatasourceId(source);r.setName("collection_scope_draft");r.setCollectionScope("2");return r;}
    @Test void sameUserReadsBothProjectsButEditsOnlyWriter(){assertTrue(access.taskIds().containsAll(List.of(900143L,900144L,900145L,900146L)));assertDoesNotThrow(()->access.task(900144L,"edit"));assertThrows(AccessDeniedException.class,()->access.task(900143L,"edit"));}
    @Test void sharedConnectionDoesNotCombineTaskProjectPermissions(){assertDoesNotThrow(()->access.task(900145L,"edit"));assertThrows(AccessDeniedException.class,()->access.task(900146L,"edit"));}
    @ParameterizedTest @ValueSource(strings={"edit","remove","release","schedule","execute"})
    void writerPermissionCannotOperateReaderTask(String action){assertThrows(AccessDeniedException.class,()->access.task(900143L,action));}
    @ParameterizedTest @EnumSource(CollectionResourceAccess.Child.class)
    void childVisibilityUsesOwnerProject(CollectionResourceAccess.Child kind){actor(926);assertTrue(access.childIds(kind).contains(900143L));assertFalse(access.childIds(kind).contains(900144L));}
    @Test void missingFunctionalPermissionCannotBorrowItFromAnotherProject(){jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=20 AND m.perms LIKE 'mc:%'");assertFalse(access.taskIds().contains(900143L));assertThrows(AccessDeniedException.class,()->access.task(900143L,"read"));assertTrue(access.taskIds().contains(900144L));}
    @Test void forgedAccessibleIdsAreReplacedBeforePaging(){when(service.getMcTaskPage(any())).thenReturn(PageResult.empty());var q=new McTaskPageReqVO();q.setAccessibleIds(List.of(9L));controller.list(q);assertFalse(q.getAccessibleIds().contains(9L));assertTrue(q.getAccessibleIds().contains(900144L));}
    @Test void mixedDeletesAreDeniedBeforeAnyServiceCall(){assertThrows(AccessDeniedException.class,()->controller.remove(new Long[]{900144L,900143L}));verifyNoInteractions(service);}
    @Test void taskProjectCannotBeMoved(){var r=edit(900144);r.setProjectId(143L);assertThrows(AccessDeniedException.class,()->access.save(r,true));}
    @Test void sourceCannotBeSwappedEvenWhenBothAreVisible(){var r=edit(900144);r.setDatasourceId(17L);assertThrows(AccessDeniedException.class,()->access.save(r,true));}
    @Test void readonlyConnectionCannotBeLaunderedThroughWriterProject(){assertThrows(AccessDeniedException.class,()->access.save(create(144,17),false));}
    @Test void newDraftCanUseDisabledSourceWithoutConnecting(){var r=create(144,16);r.setCreatorId(1L);r.setStatus("1");r.setSchedulerStatus("1");access.save(r,false);assertEquals(927L,r.getCreatorId());assertEquals("0",r.getStatus());assertEquals("0",r.getSchedulerStatus());assertEquals("QUARTZ",r.getScheduler());}
    @Test void onlyUnambiguousWritableProjectIsInferred(){var r=create(144,18);r.setProjectId(null);access.save(r,false);assertEquals(144L,r.getProjectId());}
    @Test void editPreservesCreatorAndPublication(){var r=edit(900144);r.setCreatorId(1L);r.setCreateBy("forged");r.setStatus("1");r.setSchedulerStatus("1");access.save(r,true);assertEquals(925L,r.getCreatorId());assertEquals("0",r.getStatus());assertEquals("0",r.getSchedulerStatus());assertEquals(927L,r.getUpdatorId());assertFalse(r.getScopeSaveReqVOS().isEmpty());}
    @Test void schedulerCannotBeChangedByOrdinaryEdit(){var r=edit(900144);r.setScheduler("DOLPHINSCHEDULER");assertThrows(IllegalArgumentException.class,()->access.save(r,true));}
    @Test void disabledConnectionCannotPublish(){var r=edit(900144);r.setStatus("1");assertThrows(AccessDeniedException.class,()->access.existingRequest(r,"release"));r.setStatus("0");assertDoesNotThrow(()->access.existingRequest(r,"release"));}
    @Test void disabledProjectHidesTasks(){jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertFalse(access.taskIds().contains(900144L));assertThrows(AccessDeniedException.class,()->access.save(edit(900144),true));}
    @Test void unassignedLegacyTaskIsPrivateAndCannotBeModified(){jdbc.update("UPDATE mc_task SET project_id=NULL,creator_id=927 WHERE id=900144");assertDoesNotThrow(()->access.task(900144L,"read"));assertThrows(AccessDeniedException.class,()->access.task(900144L,"edit"));actor(926);assertThrows(AccessDeniedException.class,()->access.task(900144L,"read"));}
    @Test void rowCapabilitiesBelongToProject(){var row=new McTaskRespVO();row.setProjectId(143L);assertFalse(access.present(row).getCapabilities().get("edit"));row.setProjectId(144L);assertTrue(access.present(row).getCapabilities().get("edit"));assertNotNull(row.getProjectName());}
    @Test void projectSourceListIsRestrictedAndRedacted(){var sources=projects.sources(144L);assertTrue(sources.stream().anyMatch(r->CollectionProjectAccess.number(r.get("id"))==16L));assertFalse(sources.stream().anyMatch(r->CollectionProjectAccess.number(r.get("id"))==17L));assertTrue(sources.stream().noneMatch(r->String.valueOf(r.get("datasourceConfig")).contains("\"password\"")));assertThrows(AccessDeniedException.class,()->projects.sources(1L));}
    @Test void importPreflightsAllRowsBeforeCreatingAny(){var target=spy(new McTaskServiceImpl());ReflectionTestUtils.setField(target,"collectionAccess",access);doReturn(1L).when(target).createMcTask(any());var a=new McTaskRespVO();a.setProjectId(144L);a.setDatasourceId(16L);a.setCollectionScope("2");var b=new McTaskRespVO();b.setProjectId(143L);b.setDatasourceId(17L);b.setCollectionScope("2");assertThrows(AccessDeniedException.class,()->target.importMcTask(List.of(a,b),false,"fixture"));verify(target,never()).createMcTask(any());}
    @Test void importUsesNormalCreateWithProtectedDraft(){var target=spy(new McTaskServiceImpl());ReflectionTestUtils.setField(target,"collectionAccess",access);doReturn(1L).when(target).createMcTask(any());var a=new McTaskRespVO();a.setProjectId(144L);a.setDatasourceId(16L);a.setCollectionScope("2");a.setStatus("1");target.importMcTask(List.of(a),false,"fixture");verify(target).createMcTask(argThat(r->r.getProjectId()==144L && "0".equals(r.getStatus()) && r.getCreatorId()==927L));}
    @Test void workerRefusesUnpublishedTaskBeforeAnyConnection(){assertThrows(IllegalArgumentException.class,()->worker.validate(900144L));}
    @Test void workerRefusesDisabledSource(){jdbc.update("UPDATE mc_task SET status='1' WHERE id=900144");assertThrows(IllegalArgumentException.class,()->worker.validate(900144L));}
    @Test void workerAcceptsPublishedAuthorizedSourceWithoutOpeningTransport(){jdbc.update("UPDATE mc_task SET status='1' WHERE id=900144");jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=16");assertEquals(144L,worker.validate(900144L));}
    @Test void workerRechecksRevokedAssignment(){jdbc.update("UPDATE mc_task SET status='1' WHERE id=900144");jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=16");jdbc.update("UPDATE da_datasource_project_rel SET valid_flag='0' WHERE datasource_id=16");assertThrows(IllegalArgumentException.class,()->worker.validate(900144L));}
    @Test void workerRechecksDisabledProject(){jdbc.update("UPDATE mc_task SET status='1' WHERE id=900144");jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=16");jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertThrows(IllegalArgumentException.class,()->worker.validate(900144L));}
    @Test void workerChecksHiveNamespaceAgainstTaskProject(){jdbc.update("UPDATE mc_task SET status='1' WHERE id=9");assertEquals(1L,worker.validate(9L));jdbc.update("UPDATE mc_task SET project_id=144 WHERE id=9");jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT 9,144,code,'1' FROM att_project WHERE id=144");assertThrows(IllegalArgumentException.class,()->worker.validate(9L));}
    @Test void workerRejectsAllowlistOutsideFixedDatabases(){jdbc.update("UPDATE mc_task SET status='1',table_allowlist='qdata_test.foreign_table' WHERE id=9");assertThrows(IllegalArgumentException.class,()->worker.validate(9L));}
    @Test void callbackDoesNotReportSuccessForFailedCollector(){jdbc.update("UPDATE da_datasource SET valid_flag='1' WHERE id=16");var callback=new McTaskExecutorController();ReflectionTestUtils.setField(callback,"resources",access);ReflectionTestUtils.setField(callback,"mcTaskService",service);when(service.runDaDiscoveryTask(900144L)).thenReturn(false);assertNotEquals(200,callback.runExecuteTask(900144L).getCode());}
    @Test void sourceSystemLookupUsesTaskProjectPermissionAndMinimalFields(){var rows=projects.sourceSystems(144L);assertFalse(rows.isEmpty());assertTrue(rows.stream().allMatch(r->r.keySet().equals(Set.of("id","name"))));assertThrows(AccessDeniedException.class,()->projects.sourceSystems(1L));}
    @Test void responsibleUserOptionsAreProjectMembers(){assertTrue(projects.leaders(144L).stream().anyMatch(r->CollectionProjectAccess.number(r.get("userId"))==925L));assertFalse(projects.leaders(143L).stream().anyMatch(r->CollectionProjectAccess.number(r.get("userId"))==925L));assertThrows(AccessDeniedException.class,()->projects.leaders(1L));}
    @Test void foreignResponsibleUserIsRejected(){var r=create(144,16);r.setLeader(926L);assertThrows(AccessDeniedException.class,()->access.save(r,false));}
    @Test void sourceSystemNameComesFromRegisteredDefinition(){var r=create(144,16);r.setSourceSystemId(11L);r.setSourceSystemName("forged");access.save(r,false);assertNotEquals("forged",r.getSourceSystemName());r.setSourceSystemId(-99L);assertThrows(IllegalArgumentException.class,()->access.save(r,false));}

}

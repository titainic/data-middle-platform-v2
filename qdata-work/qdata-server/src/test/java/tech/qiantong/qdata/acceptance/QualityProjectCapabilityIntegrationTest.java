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
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.*;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.*;
import tech.qiantong.qdata.module.dpp.service.qa.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class QualityProjectCapabilityIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    QualityProjectAccess projects;QualityResourceAccess access;ProjectDataResourceAccess data;DataSourceFactory transport;
    IDppQualityTaskService tasks;DppQualityTaskController controller;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_QUALITY_PROJECT_URL");assertNotNull(url);assertTrue(url.contains("/qdata_quality_project_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        transport=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);for(long id:new long[]{9,12,13})when(sources.getDaDatasourceById(id)).thenReturn(HiveEndpointFixture.source(jdbc,id));
        var namespaces=new HiveNamespaceBrowser(jdbc,sources,transport);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);data=new ProjectDataResourceAccess(jdbc,provider);projects=new QualityProjectAccess(jdbc,data,new ProjectPermissionService(jdbc));access=new QualityResourceAccess(jdbc,data,projects);
        tasks=mock(IDppQualityTaskService.class);controller=new DppQualityTaskController();ReflectionTestUtils.setField(controller,"qualityAccess",access);ReflectionTestUtils.setField(controller,"dppQualityTaskService",tasks);
        for(long project:new long[]{143,144})seed(900000+project,project);
        actor(927);
    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("quality-project-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seed(long id,long project){var values=new HashMap<String,Object>();values.put("project_id",project);values.put("asset_id",null);values.put("asset_flag","0");values.put("status","1");values.put("creator_id",927L);values.put("task_name","quality-fixture-"+project);values.put("system_job_id",null);values.put("task_id",null);values.put("task_code",null);values.put("node_id",null);values.put("node_code",null);copy("dpp_quality_task",30,id,values);}
    void objects(){
        Long originalObject=jdbc.queryForObject("SELECT MIN(id) FROM dpp_quality_task_obj WHERE task_id=30 AND del_flag='0'",Long.class);Long originalRule=jdbc.queryForObject("SELECT MIN(id) FROM dpp_quality_task_evaluate WHERE task_id=30 AND del_flag='0'",Long.class);
        for(long project:new long[]{143,144}){long task=900000+project,object=910000+project,rule=920000+project;String table="qdata_test.quality_scope_"+project;
            jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT 9,id,code,'1' FROM att_project WHERE id=?",project);
            copy("dpp_quality_task_obj",originalObject,object,Map.of("task_id",task,"datasource_id",9L,"table_name",table,"name",table));
            copy("dpp_quality_task_evaluate",originalRule,rule,Map.of("task_id",task,"obj_id",object,"table_name",table,"status","1"));
        }
    }
    void reports(){for(long project:new long[]{143,144}){long log=930000+project,report=940000+project;copy("dpp_quality_log",557,log,Map.of("quality_id",900000+project));copy("dpp_evaluate_log",583,report,Map.of("task_log_id",String.valueOf(log),"evaluate_id",920000+project,"table_name","qdata_test.quality_scope_"+project));}}
    DppQualityTaskSaveReqVO request(Long project){var value=new DppQualityTaskSaveReqVO();value.setProjectId(project);value.setTaskName("权限验证");value.setAssetFlag("0");value.setDppQualityTaskObjSaveReqVO(List.of());value.setDppQualityTaskEvaluateSaveReqVO(List.of());return value;}
    @Test void sameUserHasWriterAndReadonlyProjectCapabilities(){assertTrue(projects.capabilities(144L).get("edit"));assertFalse(projects.capabilities(143L).get("edit"));assertEquals(Set.of(900143L,900144L),new HashSet<>(access.visibleTasks("index",null)));}
    @Test void foreignProjectCannotReadOriginalTaskOrReport(){assertThrows(AccessDeniedException.class,()->access.task(30L));assertThrows(AccessDeniedException.class,()->access.log(557L));assertThrows(AccessDeniedException.class,()->access.report("583"));}
    @Test void newTaskInfersOnlySingleEligibleProjectAndStartsPaused(){var request=request(null);request.setStatus("0");request.setSystemJobId(777L);request.setTaskCode("foreign");access.validateTask(request,"add");assertEquals(144L,request.getProjectId());assertEquals("1",request.getStatus());assertNull(request.getSystemJobId());assertNull(request.getTaskCode());}
    @Test void readonlyProjectCannotCreateOrEditTask(){assertThrows(AccessDeniedException.class,()->access.validateTask(request(143L),"add"));var value=request(143L);value.setId(900143L);assertThrows(AccessDeniedException.class,()->access.validateTask(value,"edit"));}
    @Test void existingTaskCannotChangeOwningProject(){var value=request(143L);value.setId(900144L);assertThrows(AccessDeniedException.class,()->access.validateTask(value,"edit"));}
    @Test void editingCannotRewriteExecutionIdentitiesOrActivateTask(){jdbc.update("UPDATE dpp_quality_task SET task_code='original',system_job_id=8 WHERE id=900144");var value=request(144L);value.setId(900144L);value.setTaskCode("forged");value.setSystemJobId(99L);value.setStatus("0");access.validateTask(value,"edit");assertEquals("original",value.getTaskCode());assertEquals(8L,value.getSystemJobId());assertEquals("1",value.getStatus());}
    @Test void readonlyTaskExecutionAndScheduleAreDeniedBeforeService(){assertThrows(AccessDeniedException.class,()->controller.startDaDiscoveryTask(900143L));var value=request(143L);value.setId(900143L);value.setCycle("0 0 0 1 1 ? 2099");assertThrows(AccessDeniedException.class,()->controller.updateDaDiscoveryTaskCronExpression(value));verifyNoInteractions(tasks);}
    @Test void taskWithoutObjectsCannotBeActivatedOrExecuted(){var value=request(144L);value.setId(900144L);value.setStatus("0");assertThrows(IllegalArgumentException.class,()->controller.updateDaDiscoveryTaskStatus(value));assertThrows(IllegalArgumentException.class,()->controller.startDaDiscoveryTask(900144L));verifyNoInteractions(tasks);}
    @Test void sourceAndRulesAreRequiredForExecution(){objects();assertDoesNotThrow(()->projects.execute(900144L,"once"));jdbc.update("UPDATE dpp_quality_task_evaluate SET status='0' WHERE task_id=900144");assertThrows(IllegalArgumentException.class,()->projects.execute(900144L,"once"));}
    @Test void disabledDatasourceRemainsReadableForConfigurationButCannotRun(){objects();jdbc.update("UPDATE da_datasource SET valid_flag='0' WHERE id=9");assertDoesNotThrow(()->access.task(900144L));assertThrows(AccessDeniedException.class,()->projects.execute(900144L,"once"));}
    @Test void sharedConnectionCannotImportOriginalProjectsManagedNamespace(){objects();assertThrows(AccessDeniedException.class,()->projects.target(144L,9L,"dwd_iot_db.water_quality",false));}
    @Test void visibleConnectionMustAlsoBeAssignedToTargetTaskProject(){objects();jdbc.update("UPDATE da_datasource_project_rel SET valid_flag='0' WHERE datasource_id=9 AND project_id=144");assertThrows(AccessDeniedException.class,()->projects.target(144L,9L,"qdata_test.quality_scope_143",false));}
    @Test void batchDeleteChecksAllTasksBeforeDeletingAny(){assertThrows(AccessDeniedException.class,()->controller.remove(new Long[]{900144L,900143L}));verifyNoInteractions(tasks);}
    @Test void objectCannotMoveToAnotherParent(){objects();var value=new DppQualityTaskObjSaveReqVO();value.setId(910143L);value.setTaskId(900144L);assertThrows(AccessDeniedException.class,()->access.validateObject(value));}
    @Test void ruleCannotBorrowAnotherTasksObject(){objects();var value=new DppQualityTaskEvaluateSaveReqVO();value.setTaskId(900144L);value.setObjId(910143L);assertThrows(AccessDeniedException.class,()->access.validateRule(value));}
    @Test void ruleSourceMustMatchItsObject(){objects();var value=new DppQualityTaskEvaluateSaveReqVO();value.setTaskId(900144L);value.setObjId(910144L);value.setTableName("other.table");assertThrows(AccessDeniedException.class,()->access.validateRule(value));}
    @Test void completeTaskCannotSilentlyDropUnmatchedRule(){var value=request(144L);var rule=new DppQualityTaskEvaluateSaveReqVO();rule.setDatasourceId(9L);rule.setTableName("qdata_test.missing");value.setDppQualityTaskEvaluateSaveReqVO(List.of(rule));assertThrows(IllegalArgumentException.class,()->access.validateTask(value,"add"));}
    @Test void errorDispositionRequiresEditPermissionInReportProject(){objects();reports();var value=new CheckErrorDataReqDTO();value.setReportId("940143");value.setUpdateType("2");assertDoesNotThrow(()->access.errorData(value));assertThrows(AccessDeniedException.class,()->access.errorData(value,true));value.setReportId("940144");assertDoesNotThrow(()->access.errorData(value,true));}
    @Test void hiveInPlaceRepairRemainsProhibited(){objects();reports();var value=new CheckErrorDataReqDTO();value.setReportId("940144");value.setUpdateType("1");assertThrows(IllegalArgumentException.class,()->access.errorData(value,true));}
    @Test void logsRemainScopedAfterTaskIsDeleted(){objects();reports();jdbc.update("UPDATE dpp_quality_task SET del_flag='1' WHERE id=900144");assertFalse(access.visibleTasks().contains(900144L));assertDoesNotThrow(()->access.log(930144L));actor(926);assertThrows(AccessDeniedException.class,()->access.log(930144L));}
    @Test void disabledProjectRevokesQualityAccess(){objects();reports();jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertThrows(AccessDeniedException.class,()->access.task(900144L));assertThrows(AccessDeniedException.class,()->access.log(930144L));}
    @Test void enablingCannotBorrowSchedulePermissionFromAnotherProject(){objects();jdbc.update("DELETE r FROM system_role_menu r JOIN system_menu m ON m.menu_id=r.menu_id WHERE r.role_id=19 AND m.perms='da:qualityTask:schedule'");jdbc.update("INSERT INTO system_role_menu(role_id,menu_id) SELECT 20,menu_id FROM system_menu WHERE perms='da:qualityTask:schedule' AND status='0'");var value=request(144L);value.setId(900144L);value.setStatus("0");assertThrows(AccessDeniedException.class,()->controller.updateDaDiscoveryTaskStatus(value));verifyNoInteractions(tasks);}
    @Test void statusEndpointCannotRewriteProjectOrSchedulerIdentifiers(){var value=request(143L);value.setId(900144L);value.setStatus("1");value.setSystemJobId(123L);value.setTaskCode("forged");when(tasks.updateDppQualityTaskStatus(any())).thenReturn(true);controller.updateDaDiscoveryTaskStatus(value);var saved=org.mockito.ArgumentCaptor.forClass(DppQualityTaskSaveReqVO.class);verify(tasks).updateDppQualityTaskStatus(saved.capture());assertNull(saved.getValue().getProjectId());assertNull(saved.getValue().getSystemJobId());assertNull(saved.getValue().getTaskCode());}
    @Test void nativeExecutionCannotBypassFixedWarehouseAssessment(){actor(925);Long task=jdbc.queryForObject("SELECT MIN(task_id) FROM dp_warehouse_quality_assessment_task",Long.class);assertNotNull(task);assertThrows(AccessDeniedException.class,()->controller.startDaDiscoveryTask(task));verifyNoInteractions(tasks);}
    @Test void problemCapabilitiesDoNotInheritOtherProjectsEditingRights(){objects();reports();assertFalse(access.problemCapabilities("940143").get("edit"));assertTrue(access.problemCapabilities("940144").get("edit"));}

}

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
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.da.service.datasource.HiveNamespaceBrowser;
import tech.qiantong.qdata.module.dp.architecture.*;
import tech.qiantong.qdata.module.dp.warehouse.*;
import tech.qiantong.qdata.module.dpp.service.etl.DevelopmentResourceAccess;
import tech.qiantong.qdata.module.ds.service.api.ServiceResourceAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class ArchitectureProjectScopeIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;TransactionTemplate tx;ArchitectureResourceAccess resources;ArchitectureController controller;
    HiveNamespaceBrowser namespaces;WarehouseService warehouse;
    @BeforeEach void setup() {
        String url=System.getenv("QDATA_ARCHITECTURE_SCOPE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_architecture_"));
        fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);tx=new TransactionTemplate(new DataSourceTransactionManager(fixture));
        namespaces=mock(HiveNamespaceBrowser.class);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);
        var data=new ProjectDataResourceAccess(jdbc,provider);var permissions=new ProjectPermissionService(jdbc);warehouse=mock(WarehouseService.class);
        when(warehouse.list()).thenAnswer(call->jdbc.query("SELECT id,warehouse_config FROM dp_model WHERE del_flag='0' AND warehouse_config IS NOT NULL",(row,n)->{var d=JSON.parseObject(row.getString(2),WarehouseDefinition.class);d.setId(row.getLong(1));return d;}));
        resources=new ArchitectureResourceAccess(jdbc,data,permissions,warehouse,new DevelopmentResourceAccess(jdbc,data,permissions),new ServiceResourceAccess(jdbc,data,permissions,mock(IDaDatasourceApiService.class)));
        controller=new ArchitectureController(jdbc,resources);actor(927);
    }
    @AfterEach void clear(){try{verifyNoInteractions(namespaces);}finally{SecurityContextHolder.clearContext();fixture.destroy();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("architecture-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    ArchitectureDocument doc(Long project){var d=new ArchitectureDocument();d.setProjectId(project);d.setName("架构隔离验收");return d;}
    void seedDocument(long id,long project){jdbc.update("INSERT INTO dp_architecture_document(id,name,revision,graph_json,project_id,creator_id) VALUES(?,'fixture',1,?, ?,927)",id,JSON.toJSONString(doc(project)),project);}
    void copy(String table,long source,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",source));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seedResources(){
        jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT 9,id,code,'1' FROM att_project WHERE id IN(143,144)");
        for(long project:new long[]{143,144}){
            long id=900000+project;
            copy("dp_warehouse_space",1,id,Map.of("project_id",project,"name","fixture-space-"+project,"environment_code","fixture-"+project));
            var model=new WarehouseDefinition();model.setSpaceId(id);model.setDatasourceId(9L);model.setLayer("ODS");model.setName("fixture-model-"+project);model.setTableName("fixture_"+project);
            Long original=jdbc.queryForObject("SELECT MIN(id) FROM dp_model WHERE warehouse_config IS NOT NULL",Long.class);
            copy("dp_model",original,id,Map.of("warehouse_config",JSON.toJSONString(model),"model_name","fixture_"+project,"database_name","ods_iot_db","datasource_id",9));
            Long api=jdbc.queryForObject("SELECT MIN(id) FROM ds_api WHERE del_flag='0'",Long.class);copy("ds_api",api,id,Map.of("project_id",project,"name","fixture-api-"+project,"api_url","/fixture/architecture/"+project));
        }
    }
    ArchitectureDocument.Node node(String id,String type,Long ref){var n=new ArchitectureDocument.Node();n.setId(id);n.setLabel(id);n.setKind("DATABASE");n.setScope("DEPLOYMENT");n.setRefType(type);n.setRefId(ref);n.setState("REGISTERED");return n;}
    @Test void projectOptionsSeparateWriteAndReadCapabilities(){var options=resources.projects();assertEquals(Set.of(143L,144L),new HashSet<>(options.stream().map(p->((Number)p.get("id")).longValue()).toList()));assertFalse(resources.canEdit(143L));assertTrue(resources.canEdit(144L));}
    @Test void foreignProjectListDoesNotRevealLegacyGraph(){assertTrue(resources.documents().isEmpty());assertThrows(AccessDeniedException.class,()->controller.get(2));assertThrows(AccessDeniedException.class,()->controller.catalog(1L));}
    @Test void originalPrivateGraphRemainsReadonlyForItsCreator(){actor(2);assertTrue(resources.documents().stream().anyMatch(r->((Number)r.get("id")).longValue()==2));assertDoesNotThrow(()->controller.get(2));var d=doc(1L);d.setId(2L);assertThrows(AccessDeniedException.class,()->controller.save(d));}
    @Test void newGraphRequiresExplicitProject(){assertThrows(AccessDeniedException.class,()->controller.save(doc(null)));}
    @Test void cachedGlobalPermissionsDoNotLetReadonlyProjectSave(){assertThrows(AccessDeniedException.class,()->controller.save(doc(143L)));}
    @Test void createUsesRealProjectCreatorAndCurrentTimestamp(){tx.executeWithoutResult(status->{var d=doc(144L);d.setCanEdit(false);long before=System.currentTimeMillis();controller.save(d);var row=jdbc.queryForMap("SELECT * FROM dp_architecture_document WHERE id=?",d.getId());assertEquals(927L,((Number)row.get("creator_id")).longValue());assertEquals(144L,((Number)row.get("project_id")).longValue());assertEquals(1,d.getRevision());assertTrue(d.isCanEdit());long time=jdbc.queryForObject("SELECT create_time FROM dp_architecture_document WHERE id=?",(rs,n)->rs.getTimestamp(1).getTime(),d.getId());assertTrue(time>=before-1000 && time<=System.currentTimeMillis()+1000);status.setRollbackOnly();});}
    @Test void documentsAreVisibleOnlyWithinTheirProject(){tx.executeWithoutResult(status->{seedDocument(900143,143);seedDocument(900144,144);assertEquals(2,resources.documents().size());actor(925);assertEquals(List.of(900144L),resources.documents().stream().map(r->((Number)r.get("id")).longValue()).toList());assertThrows(AccessDeniedException.class,()->controller.get(900143));status.setRollbackOnly();});}
    @Test void readAndWriteChecksUseStoredOwner(){tx.executeWithoutResult(status->{seedDocument(900143,143);var d=doc(144L);d.setId(900143L);d.setRevision(1);assertThrows(AccessDeniedException.class,()->controller.save(d));assertDoesNotThrow(()->controller.get(900143));status.setRollbackOnly();});}
    @Test void ownedGraphCannotMoveIntoAnotherProject(){tx.executeWithoutResult(status->{seedDocument(900144,144);var d=doc(143L);d.setId(900144L);d.setRevision(1);assertThrows(AccessDeniedException.class,()->controller.save(d));assertEquals(144L,jdbc.queryForObject("SELECT project_id FROM dp_architecture_document WHERE id=900144",Long.class));status.setRollbackOnly();});}
    @Test void staleRevisionKeepsTheSavedGraph(){tx.executeWithoutResult(status->{seedDocument(900144,144);var d=doc(144L);d.setId(900144L);d.setRevision(1);controller.save(d);String saved=jdbc.queryForObject("SELECT graph_json FROM dp_architecture_document WHERE id=900144",String.class);d.setRevision(1);d.setName("stale");assertThrows(IllegalArgumentException.class,()->controller.save(d));assertEquals(saved,jdbc.queryForObject("SELECT graph_json FROM dp_architecture_document WHERE id=900144",String.class));status.setRollbackOnly();});}
    @Test void manualPlanningDoesNotRequireDatasourceMenu(){assertDoesNotThrow(()->controller.catalog(144L));assertEquals(List.of(),resources.catalog(144L).get("datasources"));}
    @Test void datasourceReferenceRequiresItsOwnProjectAndFunctionPermission(){tx.executeWithoutResult(status->{seedResources();var d=doc(144L);d.getNodes().add(node("source","DATASOURCE",9L));assertThrows(AccessDeniedException.class,()->controller.validate(d));status.setRollbackOnly();});}
    @Test void foreignApiAndTaskReferencesAreRejectedEvenWhenCallerCanReadBothProjects(){tx.executeWithoutResult(status->{seedResources();assertDoesNotThrow(()->resources.api(144L,900144L));assertThrows(AccessDeniedException.class,()->resources.api(144L,900143L));assertDoesNotThrow(()->resources.task(144L,57L));assertThrows(AccessDeniedException.class,()->resources.task(144L,55L));status.setRollbackOnly();});}
    @Test void catalogContainsOnlyTargetProjectModelsApisAndTasks(){tx.executeWithoutResult(status->{seedResources();var c=resources.catalog(144L);assertEquals(List.of(900144L),((List<WarehouseDefinition>)c.get("models")).stream().map(WarehouseDefinition::getId).toList());assertEquals(1,((List<?>)c.get("apis")).size());assertEquals(3,((List<?>)c.get("tasks")).size());assertEquals(144L,resources.document(saveDocument(),"query").get("project_id"));status.setRollbackOnly();});}
    Long saveDocument(){var d=doc(144L);controller.save(d);return d.getId();}
    @Test void invalidOrCrossProjectModelReferenceCannotBeImported(){tx.executeWithoutResult(status->{seedResources();var d=doc(144L);var n=node("model","MODEL",900143L);n.setScope("DATA");n.setLayer("ODS");n.setEngine("Hive");n.setKind("TABLE");d.getNodes().add(n);assertThrows(AccessDeniedException.class,()->controller.validate(d));n.setRefId(900144L);assertDoesNotThrow(()->controller.validate(d));status.setRollbackOnly();});}
    @Test void disabledProjectRevokesDocumentAndCatalogAccess(){tx.executeWithoutResult(status->{seedDocument(900144,144);jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertThrows(AccessDeniedException.class,()->controller.get(900144));assertThrows(AccessDeniedException.class,()->controller.catalog(144L));status.setRollbackOnly();});}
    @Test void connectionVisibleThroughAnotherMembershipDoesNotBelongToSelectedProject(){tx.executeWithoutResult(status->{
        jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT 9,id,code,'1' FROM att_project WHERE id=143");
        jdbc.update("INSERT INTO system_role_menu(role_id,menu_id) SELECT 19,menu_id FROM system_menu WHERE perms='da:dataSource:query' AND status='0'");
        assertThrows(AccessDeniedException.class,()->resources.datasource(144L,9L));
        assertEquals(List.of(),resources.catalog(144L).get("datasources"));status.setRollbackOnly();});}

}
